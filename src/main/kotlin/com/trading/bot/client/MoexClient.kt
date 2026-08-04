package com.trading.bot.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.model.Candle
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration
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
    private val webClient = WebClient.create()
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val baseUrl = "https://iss.moex.com/iss"

    /**
     * Загружает свечи по тикеру за N дней, начиная с указанного времени.
     * Сначала ищет на фондовом рынке (акции), при пустом результате — на FORTS (фьючерсы).
     *
     * @param ticker тикер инструмента
     * @param days количество дней истории
     * @param from начальная дата-время
     * @return список свечей (до 500, отсортированных по времени)
     */
    suspend fun getCandles(
        ticker: String,
        days: Int,
        from: LocalDateTime,
    ): List<Candle> {
        val stock = fetchCandles("stock", "shares", "TQBR", ticker, from)
        if (stock.isNotEmpty()) return stock
        // Фьючерсы (Si и др.) торгуются на FORTS — ищем там
        val futures = fetchCandles("futures", "forts", "RFUD", ticker, from)
        return if (futures.isNotEmpty()) futures else stock
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
            if (stock.isNotEmpty()) {
                stock
            } else {
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

    private fun parseCandlesAll(
        raw: String,
        ticker: String,
    ): List<Candle> {
        val candles = objectMapper.readTree(raw).path("candles")
        if (!candles.path("data").isArray) return emptyList()

        val columns = candles.path("columns").map { it.asText() }
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
            .map { row -> toCandle(row, ticker, beginIdx, openIdx, highIdx, lowIdx, closeIdx, volumeIdx) }
            .filterNotNull()
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
                time = LocalDateTime.parse(row.get(beginIdx).asText(), timeFormatter),
                openPrice = BigDecimal(row.get(openIdx).asText()),
                highPrice = BigDecimal(row.get(highIdx).asText()),
                lowPrice = BigDecimal(row.get(lowIdx).asText()),
                closePrice = BigDecimal(row.get(closeIdx).asText()),
                volume = row.get(volumeIdx).asLong(),
            )
        } catch (e: Exception) {
            logger.debug { "Skipping malformed candle row for $ticker: ${e.message}" }
            null
        }
}
