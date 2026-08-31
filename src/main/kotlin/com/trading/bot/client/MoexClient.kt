package com.trading.bot.client

import com.trading.bot.domain.risk.OptionKind
import com.trading.bot.domain.risk.OptionQuote
import com.trading.bot.model.entity.Candle
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * REST-клиент биржи MOEX (ISS API).
 *
 * - Загружает 10-минутные свечи (candles.json) для акций (TQBR) и фьючерсов (FORTS/RFUD)
 * - Парсит ответ ISS: columns + data в модели Candle
 * - Таймаут запроса 10s, при ошибке возвращает пустой список (не роняет бота)
 * - Возвращает до 500 последних свечей, отсортированных по времени
 */
@Component
class MoexClient(
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}
    private val webClient =
        WebClient
            .builder()
            .codecs { it.defaultCodecs().maxInMemorySize(64 * 1024 * 1024) }
            .build()
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val baseUrl = "https://iss.moex.com/iss"

    /**
     * Загружает свечи по тикеру за период с указанного времени.
     * Сначала ищет на фондовом рынке (акции), при пустом результате — на FORTS (фьючерсы).
     *
     * @param ticker тикер инструмента
     * @param from начальная дата-время
     * @return список свечей (до 500, отсортированных по времени)
     */
    suspend fun getCandles(
        ticker: String,
        from: LocalDateTime,
    ): List<Candle> {
        val stock = fetchCandles("stock", "shares", "TQBR", ticker, from)
        if (stock.isNotEmpty()) return stock
        // Фьючерсы (Si и др.) торгуются на FORTS — ищем там
        val futures = fetchCandles("futures", "forts", "RFUD", ticker, from)
        return futures.ifEmpty { stock }
    }

    /**
     * Загружает ВСЕ свечи за период [from, to] с пагинацией по ISS API
     * (до 500 строк на страницу, параметр start). Используется для бэктеста
     * на многолетних данных (2 года ~ 26k свечей по тикеру).
     *
     * @param ticker тикер инструмента
     * @param from начало периода
     * @param to конец периода
     * @param intervalMinutes таймфрейм в минутах (по умолчанию 10)
     * @return полный список свечей, отсортированный по времени, без дубликатов
     */
    suspend fun getCandlesPaged(
        ticker: String,
        from: LocalDateTime,
        to: LocalDateTime,
        intervalMinutes: Int = 10,
    ): List<Candle> {
        val stock = fetchCandlesPaged("stock", "shares", "TQBR", ticker, from, to, intervalMinutes)
        val result =
            stock.ifEmpty {
                fetchCandlesPaged("futures", "forts", "RFUD", ticker, from, to, intervalMinutes)
            }
        return result.sortedBy { it.time }.distinctBy { it.time }
    }

    /**
     * Пагинированный запрос к ISS: загружает страницы по 500 свечей через `start`,
     * пока не вернётся пустая или неполная страница.
     */
    private suspend fun fetchCandlesPaged(
        engine: String,
        market: String,
        board: String,
        ticker: String,
        from: LocalDateTime,
        to: LocalDateTime,
        intervalMinutes: Int,
    ): List<Candle> {
        val all = ArrayList<Candle>()
        var start = 0
        val fromStr = from.format(timeFormatter)
        val toStr = to.format(timeFormatter)
        while (true) {
            try {
                val url =
                    "$baseUrl/engines/$engine/markets/$market/boards/$board/securities/$ticker/candles.json" +
                        "?interval=$intervalMinutes&from=$fromStr&until=$toStr&start=$start"
                val raw: String =
                    webClient
                        .get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(Duration.ofSeconds(15))
                        .awaitSingle()
                val page = parseCandlesAll(raw, ticker)
                if (page.isEmpty()) break
                all.addAll(page)
                start += page.size
                if (page.size < 500 || start > 200_000) break
            } catch (e: Exception) {
                logger.warn(e) { "MOEX paged candles request failed for $ticker ($engine/$board) at start=$start" }
                break
            }
        }
        return all
    }

    private suspend fun fetchCandles(
        engine: String,
        market: String,
        board: String,
        ticker: String,
        from: LocalDateTime,
    ): List<Candle> =
        try {
            val url =
                "$baseUrl/engines/$engine/markets/$market/boards/$board/securities/$ticker/candles.json" +
                    "?interval=10&from=${from.format(timeFormatter)}&until=${LocalDateTime.now().format(timeFormatter)}"

            val raw: String =
                webClient
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .awaitSingle()

            parseCandles(raw, ticker)
        } catch (e: Exception) {
            logger.warn(e) { "MOEX candles request failed for $ticker ($engine/$board)" }
            emptyList()
        }

    private fun parseCandles(
        raw: String,
        ticker: String,
    ): List<Candle> = parseCandlesAll(raw, ticker).takeLast(500)

    /**
     * Текущее значение индекса волатильности MOEX (по умолчанию RVI) из ISS.
     *
     * Используется фильтром волатильности [com.trading.bot.service.VolatilityIndexService]:
     * при аномальном скачке индекса торговля ставится на паузу. Значение — последняя
     * зафиксированная цена индекса (пунктов волатильности, для RVI — процентная волатильность).
     *
     * @param ticker тикер индекса волатильности (по умолчанию "RVI")
     * @return последнее значение индекса или null при недоступности/ошибке парсинга
     */
    suspend fun getVolatilityIndex(ticker: String = "RVI"): BigDecimal? =
        try {
            val url = "$baseUrl/engines/stock/markets/index/securities/$ticker.json?iss.meta=off&iss.only=marketdata"
            val raw: String =
                webClient
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .awaitSingle()
            parseLastPrice(raw)
        } catch (e: Exception) {
            logger.warn(e) { "MOEX volatility index request failed for $ticker" }
            null
        }

    /**
     * Загружает полную опционную таблицу FORTS (все контракты всех базовых активов).
     *
     * Внимание: ISS-эндпоинт options/securities.json игнорирует фильтры (assetcode,
     * underlyingasset, strike) и пагинацию (limit/start) — отдаёт всегда всю таблицу
     * (~37k строк за ~1.2s). Поэтому фильтрация по инструменту выполняется клиентски,
     * здесь возвращается весь срез (securities + marketdata одной таблицей).
     *
     * @return список опционных котировок или пустой список при недоступности ISS
     */
    suspend fun getFortsOptions(): List<OptionQuote> =
        try {
            val url =
                "$baseUrl/engines/futures/markets/options/securities.json" +
                    "?securities.columns=SECID,ASSETCODE,OPTIONTYPE,STRIKE,LASTTRADEDATE,UNDERLYINGASSET,UNDERLYINGSETTLEPRICE" +
                    "&marketdata.columns=SECID,LAST,BID,OPENPOSITION"
            val raw: String =
                webClient
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofSeconds(15))
                    .awaitSingle()
            parseFortsOptions(raw)
        } catch (e: Exception) {
            logger.warn(e) { "MOEX FORTS options request failed" }
            emptyList()
        }

    /**
     * Дневные закрытия индекса волатильности (RVI) — распределение для режима рынка.
     *
     * Свечи interval=24 по доске SNDX (индексный рынок). Возвращаются цены закрытия,
     * не завершённая текущая свеча исключается (end <= now).
     *
     * @param ticker тикер индекса волатильности (по умолчанию "RVI")
     * @param from нижняя граница периода (включительно)
     * @return список дневных закрытий по возрастанию времени
     */
    suspend fun getVolatilityIndexDailyCloses(
        ticker: String = "RVI",
        from: LocalDate,
    ): List<Double> =
        try {
            val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
            val url =
                "$baseUrl/engines/stock/markets/index/boards/SNDX/securities/$ticker/candles.json" +
                    "?interval=24&from=${from.format(dateFormatter)}&until=${LocalDate.now().format(dateFormatter)}"
            val raw: String =
                webClient
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .awaitSingle()
            parseCloses(raw)
        } catch (e: Exception) {
            logger.warn(e) { "MOEX volatility index candles request failed for $ticker" }
            emptyList()
        }

    /**
     * Извлекает последнюю цену (столбец LAST) из блока marketdata ответа ISS.
     */
    private fun parseLastPrice(raw: String): BigDecimal? {
        val marketdata = objectMapper.readTree(raw).path("marketdata")
        val columns = marketdata.path("columns").toList().map { it.asString() }
        val lastIdx = columns.indexOf("LAST")
        if (lastIdx < 0) return null
        val firstRow = marketdata.path("data").path(0) ?: return null
        if (!firstRow.isArray || firstRow.isEmpty) return null
        val value = firstRow.get(lastIdx)
        if (value == null || value.isNull || value.asString().isBlank()) return null
        return value.asString().toBigDecimalOrNull()
    }

    /**
     * Парсит опционную таблицу: блоки securities и marketdata с общим индексом строк.
     *
     * Невалидные строки (неизвестный тип опциона, непарсящийся страйк/дата)
     * пропускаются — полная таблица содержит служебные строки (маржа и пр.).
     */
    private fun parseFortsOptions(raw: String): List<OptionQuote> {
        val root = objectMapper.readTree(raw)
        val securities = root.path("securities")
        val marketdata = root.path("marketdata")
        if (!securities.path("data").isArray || !marketdata.path("data").isArray) return emptyList()

        val sColumns = securities.path("columns").toList().map { it.asString() }
        val secidIdx = sColumns.indexOf("SECID")
        val assetCodeIdx = sColumns.indexOf("ASSETCODE")
        val optionTypeIdx = sColumns.indexOf("OPTIONTYPE")
        val strikeIdx = sColumns.indexOf("STRIKE")
        val lastTradeDateIdx = sColumns.indexOf("LASTTRADEDATE")
        val underlyingAssetIdx = sColumns.indexOf("UNDERLYINGASSET")
        val underlyingSettlePriceIdx = sColumns.indexOf("UNDERLYINGSETTLEPRICE")
        if (listOf(secidIdx, assetCodeIdx, optionTypeIdx, strikeIdx, lastTradeDateIdx, underlyingAssetIdx).any { it < 0 }) {
            return emptyList()
        }

        val mColumns = marketdata.path("columns").toList().map { it.asString() }
        val lastIdx = mColumns.indexOf("LAST")
        val bidIdx = mColumns.indexOf("BID")
        val openPositionIdx = mColumns.indexOf("OPENPOSITION")

        val rows = securities.path("data")
        val result = ArrayList<OptionQuote>(rows.size())
        for (i in 0 until rows.size()) {
            val sRow = rows.get(i)
            val kind =
                when (sRow.get(optionTypeIdx).asString()) {
                    "C" -> OptionKind.CALL
                    "P" -> OptionKind.PUT
                    else -> null
                }
            if (kind == null) continue
            val strike = sRow.get(strikeIdx).asString().toBigDecimalOrNull() ?: continue
            val lastTradeDate = runCatching { LocalDate.parse(sRow.get(lastTradeDateIdx).asString()) }.getOrNull() ?: continue

            val mRow = marketdata.path("data").get(i)
            if (mRow == null || mRow.isNull) continue
            result.add(
                OptionQuote(
                    secid = sRow.get(secidIdx).asString(),
                    assetCode = sRow.get(assetCodeIdx).asString(),
                    kind = kind,
                    strike = strike,
                    lastTradeDate = lastTradeDate,
                    underlyingAsset = sRow.get(underlyingAssetIdx).asString(),
                    underlyingSettlePrice = decimalOrNull(sRow, underlyingSettlePriceIdx),
                    last = decimalOrNull(mRow, lastIdx),
                    bid = decimalOrNull(mRow, bidIdx),
                    openPosition =
                        if (openPositionIdx >= 0 && mRow.has(openPositionIdx)) {
                            mRow.get(openPositionIdx).asLong()
                        } else {
                            0L
                        },
                ),
            )
        }
        return result
    }

    /**
     * Извлекает дневные цены закрытия (столбец close) из блока candles ответа ISS.
     */
    private fun parseCloses(raw: String): List<Double> {
        val candles = objectMapper.readTree(raw).path("candles")
        if (!candles.path("data").isArray) return emptyList()
        val columns = candles.path("columns").toList().map { it.asString() }
        val closeIdx = columns.indexOf("close")
        if (closeIdx < 0) return emptyList()
        return candles
            .path("data")
            .mapNotNull { row -> row.get(closeIdx)?.asString()?.toDoubleOrNull() }
            .sorted()
    }

    private fun decimalOrNull(
        row: JsonNode,
        idx: Int,
    ): BigDecimal? {
        if (idx < 0 || !row.has(idx)) return null
        val node = row.get(idx)
        if (node == null || node.isNull) return null
        val text = node.asString()
        if (text.isBlank() || text == "0") return null
        return text.toBigDecimalOrNull()
    }

    private fun parseCandlesAll(
        raw: String,
        ticker: String,
    ): List<Candle> {
        val candles = objectMapper.readTree(raw).path("candles")
        if (!candles.path("data").isArray) return emptyList()

        val columns = candles.path("columns").toList().map { it.asString() }
        val indexOf = { name: String -> columns.indexOf(name) }
        val beginIdx = indexOf("begin")
        val openIdx = indexOf("open")
        val highIdx = indexOf("high")
        val lowIdx = indexOf("low")
        val closeIdx = indexOf("close")
        val volumeIdx = indexOf("volume")
        if (listOf(beginIdx, openIdx, highIdx, lowIdx, closeIdx, volumeIdx).any { it < 0 }) return emptyList()

        return candles
            .path("data")
            .mapNotNull { row -> toCandle(row, ticker, beginIdx, openIdx, highIdx, lowIdx, closeIdx, volumeIdx) }
            .sortedBy { it.time }
    }

    private fun toCandle(
        row: JsonNode,
        ticker: String,
        beginIdx: Int,
        openIdx: Int,
        highIdx: Int,
        lowIdx: Int,
        closeIdx: Int,
        volumeIdx: Int,
    ): Candle? =
        try {
            Candle(
                ticker = ticker,
                timeframe = "MINUTE_10",
                time = LocalDateTime.parse(row.get(beginIdx).asString(), timeFormatter),
                openPrice = BigDecimal(row.get(openIdx).asString()),
                highPrice = BigDecimal(row.get(highIdx).asString()),
                lowPrice = BigDecimal(row.get(lowIdx).asString()),
                closePrice = BigDecimal(row.get(closeIdx).asString()),
                volume = row.get(volumeIdx).asLong(),
            )
        } catch (e: Exception) {
            logger.debug { "Skipping malformed candle row for $ticker: ${e.message}" }
            null
        }

    /**
     * Загружает свечи фьючерса по базовому тикеру (напр. "RI") с клейкой контрактов.
     * ISS не отдаёт свечи по generic-тикеру — нужен конкретный (RIU6, SiU6).
     * Метод запрашивает список активных контрактов, загружает свечи по каждому,
     * склеивает в хронологическом порядке без дубликатов.
     */
    suspend fun getFuturesCandlesPaged(
        baseTicker: String,
        from: LocalDateTime,
        to: LocalDateTime,
        intervalMinutes: Int = 10,
    ): List<Candle> {
        val contracts = resolveFuturesContracts(baseTicker)
        if (contracts.isEmpty()) {
            logger.warn { "No futures contracts found for base ticker $baseTicker" }
            return emptyList()
        }
        logger.info { "Resolved $baseTicker -> ${contracts.joinToString()}" }

        val allCandles = ArrayList<Candle>()
        for (contractTicker in contracts) {
            val candles = fetchCandlesPaged("futures", "forts", "RFUD", contractTicker, from, to, intervalMinutes)
            logger.info { "  $contractTicker: ${candles.size} candles" }
            allCandles.addAll(candles)
        }
        return allCandles.sortedBy { it.time }.distinctBy { it.time }
    }

    /**
     * Запрашивает у ISS список активных фьючерсных контрактов для базового тикера.
     *
     * Срочные контракты имеют тикер <БАЗА><код месяца><цифра> (напр. RIU6, SiU6),
     * а ASSETCODE (напр. RTS) не совпадает с базовым тикером — фильтруем по префиксу SECID.
     * Бессрочные (вечные) контракты (напр. CNYRUBF, LASTDELDATE 2100) не имеют суффикса
     * месяца — их SECID совпадает с базовым тикером точнo.
     */
    private suspend fun resolveFuturesContracts(baseTicker: String): List<String> {
        return try {
            val url = "$baseUrl/engines/futures/markets/forts/boards/RFUD/securities.json"
            val raw: String =
                webClient
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofSeconds(15))
                    .awaitSingle()
            val root = objectMapper.readTree(raw)
            val securities = root.path("securities")
            val columns = securities.path("columns").toList().map { it.asString() }
            val tickerIdx = columns.indexOf("SECID")
            val expiresIdx = columns.indexOf("LASTDELDATE")
            if (tickerIdx < 0) return emptyList()

            val rows = securities.path("data").toList()
            val prefixLen = baseTicker.length
            rows
                .filter { row ->
                    if (row.size() <= tickerIdx) return@filter false
                    val ticker = row[tickerIdx].asString()
                    // срочный контракт <БАЗА><месяц><цифра> ИЛИ бессрочный (совпадение 1-в-1)
                    ticker == baseTicker || (ticker.startsWith(baseTicker) && ticker.length == prefixLen + 2)
                }.map { row ->
                    val ticker = row[tickerIdx].asString()
                    val expiry = if (expiresIdx >= 0 && row.size() > expiresIdx) row.get(expiresIdx)?.asString() ?: "" else ""
                    ticker to expiry
                }.sortedBy { it.second }
                .map { it.first }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to resolve futures contracts for $baseTicker" }
            emptyList()
        }
    }
}
