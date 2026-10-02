package com.trading.bot.marketdata

import com.trading.bot.domain.microstructure.MicropriceCalculator
import com.trading.bot.domain.microstructure.MicrostructureFeatures
import com.trading.bot.domain.microstructure.ObiCalculator
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.charset.Charset
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/**
 * Стриминговый парсер платных архивов MOEX «Реестры заявок и сделок»
 * (Тип В — Top of Book; Тип А читается тем же кодом, но реконструируется
 * только BBO верхнего уровня). Закупка и формат — `docs/22-paid-market-data.md`.
 *
 * ## Формат (замерен на бесплатном образце 2024-10-01)
 * `YYYYMMDD_fut_tick.csv` — заявки:
 * ```
 * #SYMBOL,SYSTEM,TYPE,MOMENT,DEAL_ID,PRICE,VOLUME
 * CNYRUBF,F,B,20240930185802963,,11.11100,50
 * ```
 * `SYSTEM=F` (биржевой счётчик площадки «Фьючерсы»). `TYPE`: `B` — bid,
 * `S` — ask. Поле `DEAL_ID` в тиковом файле **пустое** и идентификатором
 * сделки не является — в таблицу `ticks` не пишется.
 *
 * `YYYYMMDD_fut_deal.csv` — сделки:
 * ```
 * #SYMBOL,SYSTEM,MOMENT,ID_DEAL,PRICE_DEAL,VOLUME,OPEN_POS,DIRECTION
 * CNYRUBF,F,20240930190505117,2024117270837460993,13.22400,5,8128542,B
 * ```
 * `DIRECTION`: `B` — инициатор покупатель, `S` — инициатор продавец.
 *
 * ## Ключевые нюансы реконструкции BBO
 * 1. **Сторона может отсутствовать в MOMENT.** На фиксированный момент MOEX
 *    печатает изменившуюся сторону, противоположная переносится по carry-forward.
 *    Это не крайний случай: на проверенном дне односторонние моменты
 *    составляют ~60% (26146 только-B и 25421 только-S из 68300), и без
 *    переноса треть стакана была бы потеряна целиком, а спред необоснованно
 *    завышен.
 * 2. **Несколько уровней в одном MOMENT.** В первый момент сессии файл несёт
 *    лестницу из 34 bid-уровней (37 строк на две стороны), поэтому «последняя
 *    строка момента» — не лучшая цена. BBO = `max(PRICE)` среди `B` и
 *    `min(PRICE)` среди `S`, размер берётся у лучшего уровня. Полный depth
 *    восстанавливается только из Типа А.
 * 3. **Агрессивные заявки не обновляют стакан.** Строка, цена которой
 *    пересекает уже известную противоположную сторону (bid ≥ ask или
 *    ask ≤ bid), — это заявка, исполненная сразу по встречной цене: в стакане
 *    она не остаётся. Если такие строки включать в `max`/`min`, стакан
 *    систематически скрещён: на проверенном дне без фильтра 40% секундных
 *    бакетов имели `bid >= ask` (6829 из 17059), тогда как возраст «протухшей»
 *    стороны в половине случаев равнялся 0 мс (p75 = 10 мс, p90 = 2.7 с) —
 *    пересечение вызвано не устареванием переноса, а агрессивной строкой.
 *    Поэтому TTL для перенесённой стороны не нужен и не вводится.
 *    Дополнительно MOMENT, у которого обе стороны после фильтра всё ещё
 *    скрещены, отбрасывается целиком (338 MOMENT за день). Итог: 0 скрещённых
 *    бакетов из 16857 при спреде 0.75…9.11 bps. Исполнение агрессивных заявок
 *    фиксируется отдельно в таблице `ticks` (файл сделок), поэтому фильтр не
 *    теряет сведений о торговле.
 * 4. **Артефакт открытия сессии.** Первый MOMENT несёт остатки предыдущей
 *    сессии: bid 12.995 при ask 13.31 при рыночных ~13.22, т.е. спред ~239 bps
 *    вместо типичных единиц bps. Это ломает знак OBI и размах спреда, поэтому
 *    начало **каждой** сессии отбрасывается. Сессии разделяются разрывом в
 *    потоке MOMENT.
 * 5. **MOMENT не совпадает с датой файла.** Файл `20241001_...` начинается
 *    `20240930185802` — вечерняя сессия лежит в предыдущих календарных сутках.
 *    Время берётся из MOMENT и кладётся наивным московским, как в `Candle.time`
 *    и в forward-L1; дату торговой сессии даёт имя файла.
 *
 * ## Потоковость
 * Ни один файл не материализуется целиком: строки читаются через
 * [BufferedReader], агрегаты отдаются колбэками [parseDay] по мере закрытия
 * бакета. Иначе 512 торговых дней (≈5 ГБ только по CNYRUBF) не поместились бы
 * в кучу. Строки чужих тикеров отсекаются префиксным сравнением до `split`.
 */
object MoexOrderLogParser {
    private val MOMENT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")

    /** MOEX отдаёт архивы в windows-1251; ASCII-подмножество совпадает с UTF-8. */
    private val CHARSET: Charset = Charset.forName("windows-1251")

    private val MOEX_ZONE: ZoneId = ZoneId.of("Europe/Moscow")

    /** Разрыв в потоке MOMENT, которым определяется новая сессия, с. */
    const val DEFAULT_SESSION_GAP_SECONDS: Long = 30 * 60

    /** Отбрасываемое начало каждой сессии, мс (артефакт остатков). */
    const val DEFAULT_OPEN_SESSION_SKIP_MS: Long = 50

    /**
     * Ширина бакета по умолчанию, мс (паритет с forward-L1
     * `microstructure.bucket-ms=1000`).
     *
     * MOMENT в файле Типа B имеет миллисекунды (`yyyyMMddHHmmssSSS`, 17 цифр),
     * поэтому `bucketMillis = 1` даёт состояние стакана после каждого
     * изменения — это разрешение, на котором вообще имеет смысл считать OBI и
     * microprice. Секундный агрегат нужен только для сопоставимости с
     * forward-сбором микроструктуры.
     */
    const val DEFAULT_BUCKET_MILLIS: Long = 1000

    /** Ширина бакета по умолчанию, с. Синоним [DEFAULT_BUCKET_MILLIS] для читаемости. */
    const val DEFAULT_BUCKET_SECONDS: Long = 1

    private const val BUFFER_SIZE = 1 shl 16

    private const val MOMENT_LENGTH = 17

    private const val TICK_FILE_MARKER = "fut_tick"

    private const val DEAL_FILE_MARKER = "fut_deal"

    private const val TICK_COLUMNS = 7

    private const val DEAL_COLUMNS = 8

    private const val TYPE_BID = 'B'

    private const val TYPE_ASK = 'S'

    /** Счётчики парсинга: диагностика и доказательство, что архив прочитан целиком. */
    class ParseStats {
        var tickLines: Long = 0
        var tickRows: Long = 0
        var tickRowsForeignTicker: Long = 0
        var tickRowsUnknownType: Long = 0
        var malformedTick: Long = 0
        var dealLines: Long = 0
        var dealRows: Long = 0
        var dealRowsForeignTicker: Long = 0
        var malformedDeal: Long = 0
        var bboBuckets: Long = 0
        var bboBucketsDroppedOpening: Long = 0
        var bboBucketsSingleSided: Long = 0
        var bboBucketsCrossed: Long = 0
        var sessions: Long = 0

        /** Закоммиченных MOMENT (агрегированных моментов), не строк. */
        var momentsCommitted: Long = 0

        /**
         * MOMENT, после агрессивной фильтрации оставшийся скрещённым (`bid >= ask`).
         * На реальном дне единицы — это случаи, когда обе стороны момента
         * пересекли друг друга; состояние стакана в такой момент неизвестно.
         */
        var momentsCrossed: Long = 0

        /**
         * Агрессивные заявки — строки, отброшенные как обновление стакана
         * (см. [MoexOrderLogParser] и `BboAccumulator.onQuote`). Это не потеря:
         * такая заявка исполняется сразу и в стакане не остаётся, а её факт
         * исполнения фиксируется в таблице `ticks` из файла сделок.
         */
        var quotesAggressiveBid: Long = 0
        var quotesAggressiveAsk: Long = 0

        /**
         * Учёт котировок по исходам. Сумма [quotesEmitted], трёх отброшенных
         * счётчиков и [quotesAggressive] равна `tickRows` — на этом держится
         * проверка, что ни одна строка источника не потеряна и не посчитана
         * дважды.
         */
        var quotesEmitted: Long = 0
        var quotesDroppedOpening: Long = 0
        var quotesDroppedSingleSided: Long = 0
        var quotesDroppedCrossed: Long = 0

        /** Сумма агрессивных строк обеих сторон. */
        val quotesAggressive: Long get() = quotesAggressiveBid + quotesAggressiveAsk

        override fun toString(): String =
            "MoexOrderLogParser[" +
                "tickLines=$tickLines, tickRows=$tickRows, " +
                "tickRowsForeignTicker=$tickRowsForeignTicker, tickRowsUnknownType=$tickRowsUnknownType, " +
                "malformedTick=$malformedTick, dealLines=$dealLines, dealRows=$dealRows, " +
                "dealRowsForeignTicker=$dealRowsForeignTicker, malformedDeal=$malformedDeal, " +
                "bboBuckets=$bboBuckets, sessions=$sessions, moments=$momentsCommitted, " +
                "droppedOpening=$bboBucketsDroppedOpening/$quotesDroppedOpening, " +
                "singleSided=$bboBucketsSingleSided/$quotesDroppedSingleSided, " +
                "crossed=$bboBucketsCrossed/$quotesDroppedCrossed, " +
                "aggressive=$quotesAggressiveBid/$quotesAggressiveAsk, " +
                "momentsCrossed=$momentsCrossed, quotesEmitted=$quotesEmitted]"
    }

    /** Одна сделка (строка `*_fut_deal.csv`), готовая к записи в таблицу `ticks`. */
    data class TradeTick(
        val ticker: String,
        val time: LocalDateTime,
        val dealId: Long,
        val price: BigDecimal,
        val volume: Long,
        val direction: String,
        val openInterest: Long?,
    )

    /**
     * Секундный бакет реконструированного BBO.
     *
     * Набор полей повторяет `MicrostructureSnapshot`, чтобы исследовательские
     * запросы к forward (`microstructure_snapshots`) и истории (`orderbook_bbo`)
     * выглядели одинаково. Содержательные отличия:
     * - колонка `price` исторического BBO остаётся `NULL` намеренно: тиковый файл
     *   сделок не содержит, а выдумывать цену из bid/ask значило бы подмешать в
     *   признак величину, которой в источнике нет. Точная цена последней сделки
     *   берётся из таблицы `ticks` условием `ts <= bucket.ts` (сделки внутри
     *   бакета относятся к его началу, поэтому строгое `ts <=` исключает
     *   lookahead);
     * - [quoteCount] — число строк-котировок в бакете, т.е. реальная частота
     *   обновлений стакана, а не число секунд.
     */
    data class BboBucket(
        val ticker: String,
        val time: LocalDateTime,
        val quoteCount: Long,
        val bid: BigDecimal,
        val ask: BigDecimal,
        val bidSize: Long,
        val askSize: Long,
        val spreadBps: BigDecimal?,
        val obi: BigDecimal?,
        val microprice: BigDecimal?,
        val micropriceDeviationBps: BigDecimal?,
    )

    /**
     * Разбирает один торговый день.
     *
     * @param path каталог с распакованными CSV либо `.7z`/`.zip`-архив MOEX
     * @param ticker SECID, который нужно оставить; остальные отбрасываются
     * @param bucketMillis ширина бакета в миллисекундах. `1000` — секундный
     *   агрегат (по умолчанию), `1` — состояние стакана после каждого MOMENT
     * @param openSessionSkipMs отбрасывать начало каждой сессии, мс
     * @param sessionGapSeconds разрыв, которым определяется новая сессия, с
     * @param bucketSink получает закрытые бакеты по возрастанию времени
     * @param tickSink получает сделки по возрастанию времени
     * @param onChunk прогресс после каждого прочитанного файла: (сделки, бакеты)
     */
    @Suppress("LongParameterList")
    fun parseDay(
        path: File,
        ticker: String,
        bucketMillis: Long = DEFAULT_BUCKET_MILLIS,
        openSessionSkipMs: Long = DEFAULT_OPEN_SESSION_SKIP_MS,
        sessionGapSeconds: Long = DEFAULT_SESSION_GAP_SECONDS,
        bucketSink: (BboBucket) -> Unit,
        tickSink: (TradeTick) -> Unit,
        onChunk: ((ticks: Long, buckets: Long) -> Unit)? = null,
    ): ParseStats {
        // Без проверки bucketMillis = 0 падал бы ArithmeticException внутри
        // горячего цикла на каждой строке файла, а отрицательная ширина
        // молча склеивала бы соседние моменты в один бакет.
        require(bucketMillis > 0) { "bucketMillis должен быть положительным, получено $bucketMillis" }
        val stats = ParseStats()
        val accumulator =
            BboAccumulator(
                ticker = ticker,
                bucketMillis = bucketMillis,
                openSessionSkipMs = openSessionSkipMs,
                sessionGapSeconds = sessionGapSeconds,
                bucketSink = bucketSink,
                stats = stats,
            )
        // Порядок файлов важен: тиковый файл плотнее в ~10 раз, а сделки нужны
        // для lastTradePrice, поэтому тики читаются первыми и держат carry-forward.
        try {
            open(path) { name, stream ->
                when {
                    name.contains(TICK_FILE_MARKER) -> readTicks(stream, ticker, stats, accumulator)

                    name.contains(DEAL_FILE_MARKER) -> readDeals(stream, ticker, stats, tickSink)

                    // Прочие файлы (futs_options, микротики) не читаем:
                    // они относятся к другому тикеру и не должны влиять на BBO.
                    else -> Unit
                }
                onChunk?.invoke(stats.dealRows, stats.bboBuckets)
            }
        } finally {
            accumulator.finish()
        }
        return stats
    }

    /** Открывает каталог/`.7z`/`.zip` как последовательность `(имя, поток)`. */
    private fun open(
        path: File,
        consume: (name: String, stream: InputStream) -> Unit,
    ) {
        when {
            path.isDirectory -> {
                val files = path.listFiles()?.sortedBy { it.name }.orEmpty()
                val tickFiles = files.filter { it.name.contains(TICK_FILE_MARKER) }
                val dealFiles = files.filter { it.name.contains(DEAL_FILE_MARKER) }
                tickFiles.forEach { consume(it.name, it.inputStream()) }
                dealFiles.forEach { consume(it.name, it.inputStream()) }
            }

            path.name.endsWith(".7z", ignoreCase = true) -> {
                readSevenZ(path, consume)
            }

            path.name.endsWith(".zip", ignoreCase = true) -> {
                readZip(path, consume)
            }

            else -> {
                throw IllegalArgumentException(
                    "unsupported order log source: ${path.name} (expected directory, .7z or .zip)",
                )
            }
        }
    }

    private fun readSevenZ(
        archive: File,
        consume: (name: String, stream: InputStream) -> Unit,
    ) {
        SevenZFile.builder().setFile(archive).get().use { sevenZ ->
            var entry: SevenZArchiveEntry? = sevenZ.nextEntry
            while (entry != null) {
                val current = entry
                if (!current.isDirectory) {
                    sevenZ.getInputStream(current).use { consume(current.name, it) }
                }
                entry = sevenZ.nextEntry
            }
        }
    }

    private fun readZip(
        archive: File,
        consume: (name: String, stream: InputStream) -> Unit,
    ) {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) consume(entry.name, zip)
                entry = zip.nextEntry
            }
        }
    }

    private fun readTicks(
        stream: InputStream,
        ticker: String,
        stats: ParseStats,
        accumulator: BboAccumulator,
    ) {
        lines(stream).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                stats.tickLines++
                if (isSkippable(line)) continue
                if (!startsWithTicker(line, ticker)) {
                    stats.tickRowsForeignTicker++
                    continue
                }
                val columns = line.split(',')
                if (columns.size < TICK_COLUMNS) {
                    stats.malformedTick++
                    continue
                }
                val time = parseMoment(columns[3])
                if (time == null) {
                    stats.malformedTick++
                    continue
                }
                val price = columns[5].toBigDecimalOrNull()
                if (price == null) {
                    stats.malformedTick++
                    continue
                }
                val volume = columns[6].trim().toLongOrNull()
                if (volume == null) {
                    stats.malformedTick++
                    continue
                }
                when (columns[2].trim().firstOrNull()) {
                    TYPE_BID -> accumulator.onBid(time, price, volume)
                    TYPE_ASK -> accumulator.onAsk(time, price, volume)
                    else -> stats.tickRowsUnknownType++
                }
                stats.tickRows++
            }
        }
    }

    private fun readDeals(
        stream: InputStream,
        ticker: String,
        stats: ParseStats,
        tickSink: (TradeTick) -> Unit,
    ) {
        lines(stream).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                stats.dealLines++
                if (isSkippable(line)) continue
                if (!startsWithTicker(line, ticker)) {
                    stats.dealRowsForeignTicker++
                    continue
                }
                val columns = line.split(',')
                if (columns.size < DEAL_COLUMNS) {
                    stats.malformedDeal++
                    continue
                }
                val tick = parseTradeTick(columns, ticker)
                if (tick == null) {
                    stats.malformedDeal++
                    continue
                }
                stats.dealRows++
                tickSink(tick)
            }
        }
    }

    private fun parseTradeTick(
        columns: List<String>,
        ticker: String,
    ): TradeTick? {
        val time = parseMoment(columns[2].trim()) ?: return null
        val dealId = columns[3].trim().toLongOrNull() ?: return null
        val price = columns[4].trim().toBigDecimalOrNull() ?: return null
        val volume = columns[5].trim().toLongOrNull() ?: return null
        val direction = columns[7].trim().take(1)
        if (direction.isEmpty()) return null
        return TradeTick(
            ticker = ticker,
            time = time,
            dealId = dealId,
            price = price,
            volume = volume,
            direction = direction,
            openInterest = columns[6].trim().toLongOrNull(),
        )
    }

    private fun isSkippable(line: String): Boolean = line.isEmpty() || line[0] == '#'

    /**
     * Проверка «строка нашего тикера» без аллокации массива полей: строка
     * начинается ровно с `SECID,`. На реальном дне так отсекается ~98.5% строк
     * до `split`, что удешевляет разбор 14.6 млн строк рынка.
     */
    private fun startsWithTicker(
        line: String,
        ticker: String,
    ): Boolean =
        line.startsWith(ticker) &&
            line.length > ticker.length &&
            line[ticker.length] == ','

    private fun lines(stream: InputStream): BufferedReader = BufferedReader(InputStreamReader(stream, CHARSET), BUFFER_SIZE)

    private fun parseMoment(text: String): LocalDateTime? {
        if (text.length != MOMENT_LENGTH) return null
        return try {
            LocalDateTime.parse(text, MOMENT_FORMAT)
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }
    }

    /**
     * Накопитель BBO: агрегирует **MOMENT**, а не отдельные строки.
     *
     * В одном MOMENT MOEX печатает лестницу уровней (замерено: 37 строк на 34
     * bid-уровня в первый момент), поэтому «последняя строка» — не лучшая
     * цена. Здесь bid = `max(PRICE)` среди `B`, ask = `min(PRICE)` среди `S`,
     * а размер берётся у лучшего уровня.
     *
     * Сторона, отсутствующая в MOMENT, переносится с прошлого момента
     * (carry-forward): на реальном дне односторонние моменты составляют ~60%,
     * и без переноса треть стакана была бы потеряна целиком, а спред
     * необоснованно завышен.
     *
     * Строка, пересекающая уже известную противоположную сторону, в стакан не
     * попадает: это агрессивная заявка, исполненная сразу по встречной цене.
     * Сравнение выполняется построчно, до агрегации по MOMENT, — иначе
     * `max`/`min` впитали бы исполненные заявки и скрещивали стакан в 40%
     * бакетов. Счётчики [ParseStats.quotesAggressiveBid] и
     * [ParseStats.quotesAggressiveAsk] показывают объём отброшенного.
     *
     * Память ограничена текущим моментом и бакетом — история не накапливается.
     */
    private class BboAccumulator(
        private val ticker: String,
        private val bucketMillis: Long,
        private val openSessionSkipMs: Long,
        private val sessionGapSeconds: Long,
        private val bucketSink: (BboBucket) -> Unit,
        private val stats: ParseStats,
    ) {
        /** Состояние стакана, известное на текущий момент (с учётом переноса). */
        private var bidPrice: BigDecimal? = null
        private var askPrice: BigDecimal? = null
        private var bidSize: Long? = null
        private var askSize: Long? = null

        /** Лучшие уровни текущего MOMENT (ещё не закоммичены в состояние). */
        private var momentTime: LocalDateTime? = null
        private var momentBidPrice: BigDecimal? = null
        private var momentBidSize: Long? = null
        private var momentAskPrice: BigDecimal? = null
        private var momentAskSize: Long? = null

        private var bucketStartTime: LocalDateTime? = null
        private var quoteCount = 0L
        private var lastEventTime: LocalDateTime? = null
        private var sessionStartTime: LocalDateTime? = null

        fun onBid(
            time: LocalDateTime,
            price: BigDecimal,
            volume: Long,
        ) = onQuote(time, price, volume, isBid = true)

        fun onAsk(
            time: LocalDateTime,
            price: BigDecimal,
            volume: Long,
        ) = onQuote(time, price, volume, isBid = false)

        fun finish() {
            commitMoment()
            seal()
        }

        private fun onQuote(
            time: LocalDateTime,
            price: BigDecimal,
            volume: Long,
            isBid: Boolean,
        ) {
            if (price.signum() <= 0 || volume < 0) {
                stats.malformedTick++
                return
            }
            if (sessionStartTime == null) {
                sessionStartTime = time
                stats.sessions++
            }
            val previousEvent = lastEventTime
            lastEventTime = time
            if (previousEvent != null && isNewSession(previousEvent, time)) {
                commitMoment()
                seal()
                sessionStartTime = time
                stats.sessions++
            }
            val openMoment = momentTime
            if (openMoment != null && openMoment != time) {
                // Новый MOMENT: лучшие уровни предыдущего фиксируются в состоянии
                // стакана до закрытия бакета, иначе последний момент секунды
                // потерялся бы.
                commitMoment()
            }
            val start = bucketStart(time)
            val openBucket = bucketStartTime
            if (openBucket == null) {
                bucketStartTime = start
            } else if (start != openBucket) {
                // Смена бакета (в т.ч. откат MOMENT на границе сессии) — закрываем
                // бакет, чтобы не смешать моменты разных интервалов и сессий.
                seal()
                bucketStartTime = start
            }
            if (isBid) {
                val opposite = askPrice
                if (opposite != null && price >= opposite) {
                    stats.quotesAggressiveBid++
                    return
                }
                if (momentBidPrice == null || price > momentBidPrice!!) {
                    momentBidPrice = price
                    momentBidSize = volume
                }
            } else {
                val opposite = bidPrice
                if (opposite != null && price <= opposite) {
                    stats.quotesAggressiveAsk++
                    return
                }
                if (momentAskPrice == null || price < momentAskPrice!!) {
                    momentAskPrice = price
                    momentAskSize = volume
                }
            }
            momentTime = time
            quoteCount++
        }

        /** Переносит лучшие уровни текущего MOMENT в состояние стакана. */
        private fun commitMoment() {
            val moment = momentTime
            if (moment != null) {
                stats.momentsCommitted++
                val momentBid = momentBidPrice
                val momentAsk = momentAskPrice
                // Обе стороны момента пересекли друг друга: состояние стакана
                // после такого MOMENT неизвестно, обе стороны в перенос не берутся.
                // Поля сбрасываются в любом случае, иначе утекут в следующий момент.
                val crossed = momentBid != null && momentAsk != null && momentBid >= momentAsk
                if (crossed) {
                    stats.momentsCrossed++
                } else {
                    momentBid?.let { bidPrice = it }
                    momentBidSize?.let { bidSize = it }
                    momentAsk?.let { askPrice = it }
                    momentAskSize?.let { askSize = it }
                }
            }
            momentTime = null
            momentBidPrice = null
            momentBidSize = null
            momentAskPrice = null
            momentAskSize = null
        }

        private fun isNewSession(
            previous: LocalDateTime,
            current: LocalDateTime,
        ): Boolean =
            current.isBefore(previous) ||
                Duration.between(previous, current).seconds >= sessionGapSeconds

        private fun bucketStart(time: LocalDateTime): LocalDateTime {
            val epochMilli = time.atZone(MOEX_ZONE).toInstant().toEpochMilli()
            val floored = Math.floorDiv(epochMilli, bucketMillis) * bucketMillis
            return Instant.ofEpochMilli(floored).atZone(MOEX_ZONE).toLocalDateTime()
        }

        private fun seal() {
            val start = bucketStartTime
            val quotes = quoteCount
            bucketStartTime = null
            quoteCount = 0
            if (start == null || quotes == 0L) return
            val sessionStart = sessionStartTime
            if (sessionStart != null && openSessionSkipMs > 0) {
                val skipBefore = sessionStart.atZone(MOEX_ZONE).toInstant().toEpochMilli() + openSessionSkipMs
                if (start.atZone(MOEX_ZONE).toInstant().toEpochMilli() < skipBefore) {
                    stats.bboBucketsDroppedOpening++
                    stats.quotesDroppedOpening += quotes
                    return
                }
            }
            emit(start, quotes)
        }

        private fun emit(
            start: LocalDateTime,
            quotes: Long,
        ) {
            val bid = bidPrice
            val ask = askPrice
            if (bid == null || ask == null) {
                stats.bboBucketsSingleSided++
                stats.quotesDroppedSingleSided += quotes
                return
            }
            if (bid >= ask) {
                // Скрещённый BBO невозможен, поэтому такие наблюдения в признаки
                // не попадают: после агрессивной фильтрации их единицы процента,
                // остаток — моменты, где обе стороны обновились взаимно
                // пересекающимися ценами. Счётчик виден в [ParseStats].
                stats.bboBucketsCrossed++
                stats.quotesDroppedCrossed += quotes
                return
            }
            val bidQty = bidSize
            val askQty = askSize
            stats.bboBuckets++
            stats.quotesEmitted += quotes
            bucketSink(
                BboBucket(
                    ticker = ticker,
                    time = start,
                    quoteCount = quotes,
                    bid = bid,
                    ask = ask,
                    bidSize = bidQty ?: 0L,
                    askSize = askQty ?: 0L,
                    spreadBps = MicrostructureFeatures.spreadBps(bid, ask),
                    obi = ObiCalculator.calculate(bidQty, askQty),
                    microprice = MicropriceCalculator.calculate(bid, ask, bidQty, askQty),
                    micropriceDeviationBps = MicrostructureFeatures.deviationBps(bid, ask, bidQty, askQty),
                ),
            )
        }
    }
}
