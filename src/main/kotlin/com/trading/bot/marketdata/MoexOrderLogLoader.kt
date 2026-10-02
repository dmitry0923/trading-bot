package com.trading.bot.marketdata

import com.trading.bot.model.entity.OrderbookBbo
import com.trading.bot.repository.HistoricalBboRepository
import com.trading.bot.repository.TradeTickRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service
import java.io.File
import com.trading.bot.model.entity.TradeTick as TradeTickEntity

/**
 * Загрузка исторического BBO и тиков MOEX Типа B в `orderbook_bbo` / `ticks`.
 *
 * Разбор поручается [MoexOrderLogParser], который отдаёт агрегаты колбэками
 * и **не материализует файлы целиком**: за 2 года CNYRUBF это ≈5 ГБ, поэтому
 * строки уходят в БД по мере разбора, а не после конца файла.
 *
 * Парсер синхронный, а репозитории R2DBC — suspend, поэтому приём идёт через
 * каналы: колбэки парсера только складывают готовые строки в буфер
 * (`trySend`, без suspend), а запись выполняется отдельной корутиной. Так
 * чтение файла не блокирует event-loop и не держит транзакцию открытой на
 * весь файл.
 *
 * Идемпотентность обеспечивают репозитории: `UNIQUE (ticker, ts)` и
 * `UNIQUE (ticker, ts, deal_id)` + `ON CONFLICT DO NOTHING`. Повторный прогон
 * (ретрай, докачка, перезапуск) не дублирует и не перетирает уже записанные
 * строки, поэтому загрузку можно безопасно повторять целиком или по дням.
 *
 * Fail-soft по строке: битая строка считается в `ParseStats.malformedTick` /
 * `malformedDeal` и не прерывает загрузку, но итог проверяется вызывающим
 * кодом — см. [LoadResult].
 */
@Service
class MoexOrderLogLoader(
    private val tickRepository: TradeTickRepository,
    private val bboRepository: HistoricalBboRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Разбор одного файла/каталога MOEX Типа B с записью в БД.
     *
     * @param path файл `.7z`/`.zip` либо каталог с распакованными CSV
     * @param ticker SECID (CNYRUBF)
     * @param bucketMillis ширина BBO-бакета в миллисекундах: `1000` — секундный
     *   агрегат, `1` — состояние стакана после каждого MOMENT
     * @param openSessionSkipMs отбрасываемое начало каждой сессии: первый
     *   MOMENT несёт остатки предыдущей сессии (артефакт открытия)
     */
    suspend fun load(
        path: File,
        ticker: String,
        bucketMillis: Long = MoexOrderLogParser.DEFAULT_BUCKET_MILLIS,
        openSessionSkipMs: Long = MoexOrderLogParser.DEFAULT_OPEN_SESSION_SKIP_MS,
    ): LoadResult {
        val ticks = Channel<TradeTickEntity>(Channel.UNLIMITED)
        val buckets = Channel<OrderbookBbo>(Channel.UNLIMITED)
        var savedTicks = 0
        var savedBuckets = 0
        var lastReported = 0L

        val stats =
            coroutineScope {
                // Писатели накапливают батч и отдают его одним multi-row INSERT.
                // Запись по одной строке означала бы ~23 тыс. отдельных
                // round-trip'ов на один день (и ~12 млн на два года), поэтому
                // батчевание здесь не оптимизация, а условие применимости.
                val writer =
                    launch {
                        val batch = ArrayList<TradeTickEntity>(WRITE_BATCH)
                        for (tick in ticks) {
                            batch += tick
                            if (batch.size >= WRITE_BATCH) {
                                savedTicks += tickRepository.saveAll(ArrayList(batch))
                                if (savedTicks % PROGRESS_STEP < WRITE_BATCH) {
                                    logger.info { "MoexOrderLogLoader: $ticker сохранено сделок=$savedTicks" }
                                }
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) savedTicks += tickRepository.saveAll(ArrayList(batch))
                    }
                val bboWriter =
                    launch {
                        val batch = ArrayList<OrderbookBbo>(WRITE_BATCH)
                        for (bucket in buckets) {
                            batch += bucket
                            if (batch.size >= WRITE_BATCH) {
                                savedBuckets += bboRepository.saveAll(ArrayList(batch))
                                if (savedBuckets % PROGRESS_STEP < WRITE_BATCH) {
                                    logger.info { "MoexOrderLogLoader: $ticker сохранено бакетов=$savedBuckets" }
                                }
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) savedBuckets += bboRepository.saveAll(ArrayList(batch))
                    }
                val parsed =
                    MoexOrderLogParser.parseDay(
                        path = path,
                        ticker = ticker,
                        bucketMillis = bucketMillis,
                        openSessionSkipMs = openSessionSkipMs,
                        bucketSink = { bucket ->
                            buckets.trySend(
                                OrderbookBbo(
                                    ticker = bucket.ticker,
                                    time = bucket.time,
                                    quoteCount = bucket.quoteCount,
                                    price = null,
                                    bid = bucket.bid,
                                    ask = bucket.ask,
                                    bidSize = bucket.bidSize,
                                    askSize = bucket.askSize,
                                    spreadBps = bucket.spreadBps,
                                    obi = bucket.obi,
                                    microprice = bucket.microprice,
                                    micropriceDeviationBps = bucket.micropriceDeviationBps,
                                ),
                            )
                        },
                        tickSink = { tick ->
                            ticks.trySend(
                                TradeTickEntity(
                                    ticker = tick.ticker,
                                    time = tick.time,
                                    dealId = tick.dealId,
                                    price = tick.price,
                                    volume = tick.volume,
                                    direction = tick.direction,
                                    openInterest = tick.openInterest,
                                ),
                            )
                        },
                        onChunk = { tickRows, bboRows ->
                            val total = tickRows + bboRows
                            if (total - lastReported >= PROGRESS_STEP) {
                                lastReported = total
                                logger.info { "MoexOrderLogLoader: $ticker обработано строк=$total" }
                            }
                        },
                    )
                ticks.close()
                buckets.close()
                writer.join()
                bboWriter.join()
                parsed
            }

        if (stats.tickRows > 0 && stats.bboBuckets == 0L && stats.dealRows == 0L) {
            logger.error {
                "MoexOrderLogLoader: $ticker $path — ${stats.tickRows} строк тиков прочитано, " +
                    "но ни бакета, ни сделки не записано"
            }
        }
        meterRegistry.counter("moex.orderlog.saved", Tags.of("ticker", ticker, "table", "ticks")).increment(savedTicks.toDouble())
        meterRegistry
            .counter("moex.orderlog.saved", Tags.of("ticker", ticker, "table", "orderbook_bbo"))
            .increment(savedBuckets.toDouble())
        logger.info {
            "MoexOrderLogLoader: $ticker $path — bbo=$savedBuckets/${stats.bboBuckets}, " +
                "ticks=$savedTicks/${stats.dealRows}, агрессивных отброшено=${stats.quotesAggressive}, " +
                "скрещённых MOMENT=${stats.momentsCrossed}, " +
                "malformed=${stats.malformedTick + stats.malformedDeal}"
        }
        return LoadResult(
            ticker = ticker,
            bboBuckets = savedBuckets,
            ticks = savedTicks,
            stats = stats,
        )
    }

    /**
     * Итог загрузки одного файла.
     *
     * `stats` сохраняется целиком, чтобы загрузчик не «молчал» о потерянных
     * строках: расхождение `saved*` с `stats.*` означает сработавший
     * `ON CONFLICT DO NOTHING` (повторная загрузка), а ненулевой `malformed*`
     * означает повреждённые строки в исходнике.
     */
    data class LoadResult(
        val ticker: String,
        val bboBuckets: Int,
        val ticks: Int,
        val stats: MoexOrderLogParser.ParseStats,
    )

    private companion object {
        /** Как часто логировать прогресс на длинных файлах. */
        const val PROGRESS_STEP = 500_000

        /**
         * Строк в одном multi-row INSERT. Совпадает с батчем репозиториев:
         * 500 значений на строку при 7 колонках даёт SQL примерно в 40 КБ —
         * в пределах лимита statement, но уже без потерь на round-trip.
         */
        const val WRITE_BATCH = 500
    }
}
