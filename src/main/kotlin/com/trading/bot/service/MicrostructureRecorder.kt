package com.trading.bot.service

import com.trading.bot.client.QuoteTick
import com.trading.bot.config.MicrostructureConfig
import com.trading.bot.domain.microstructure.MicropriceCalculator
import com.trading.bot.domain.microstructure.ObiCalculator
import com.trading.bot.model.entity.MicrostructureSnapshot
import com.trading.bot.repository.MicrostructureSnapshotRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Сбор и персистенс микроструктуры стакана из live L1-котировок Alor.
 *
 * ## Зачем
 * Исторических L1-данных не существует ни в одном доступном источнике, поэтому
 * OBI/microprice/spread невозможно проверить на истории — их можно только
 * **начинать собирать сейчас** и валидировать forward. Этот класс закрывает эту дыру.
 *
 * ## Почему агрегация, а не сырые тики
 * Частота котировок на порядки выше торговой (десятки тысяч сделок в день на
 * инструмент), и запись каждого тика в БД несовместима с R2DBC-пулом и даёт
 * миллионы строк. Поэтому тики агрегируются в бакеты ([MicrostructureConfig.bucketMs],
 * по умолчанию 1с) со средними OBI/microprice/spread.
 *
 * ## Не влияет на торговлю
 * [record] — чисто in-memory O(1) без БД, вызывается из hot-path котировок.
 * Запись выполняет отдельная корутина по расписанию ([flush]). Сбор не читается
 * торговыми решениями: это наблюдательный слой для research, а не гейт.
 *
 * ## Fail-soft, а не fail-closed
 * При переполнении очереди дропается **самый старый** бакет (потеря наблюдения
 * допустима), а не новый — так свежие данные, ради которых всё затевалось,
 * сохраняются. Потеря фиксируется метрикой `microstructure.flush.dropped`.
 */
@Service
class MicrostructureRecorder(
    private val config: MicrostructureConfig,
    private val repository: MicrostructureSnapshotRepository,
    private val meterRegistry: MeterRegistry,
    private val timeSource: MicrostructureTimeSource,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val moscowZone: ZoneId = ZoneId.of("Europe/Moscow")
    private val bucketMs: Long = config.bucketMs.coerceAtLeast(1L)

    /** Открытый (накапливаемый) бакет на тикер. Замена атомарна через [ConcurrentHashMap.compute]. */
    private val open = ConcurrentHashMap<String, Bucket>()

    /** Закрытые бакеты, ожидающие записи. Ограничение — через [pendingSize], drop-oldest. */
    private val pending = ConcurrentLinkedQueue<MicrostructureSnapshot>()
    private val pendingSize = AtomicInteger(0)

    /** Не допускает наложения flush'ей, если запись в БД медленнее интервала расписания. */
    private val flushInFlight = AtomicBoolean(false)

    /**
     * Финальный сброс при остановке контекста.
     *
     * Сброс **синхронный**: `launch` здесь был отменён немедленно следующим же
     * `scope.cancel()`, и последний бакет тикера (в т.ч. неполный, закрытый
     * только из-за остановки) просто терялся. Повтор при ошибке БД ограничен:
     * батч возвращается в очередь, и бесконечный retry повесил бы shutdown.
     */
    @PreDestroy
    fun close() {
        sealAll()
        runBlocking { drain() }
        scope.cancel()
    }

    /** Сбрасывает очередь до конца, но не более [MAX_SHUTDOWN_PASSES] проходов. */
    private suspend fun drain() {
        repeat(MAX_SHUTDOWN_PASSES) {
            if (pendingSize.get() == 0) return
            flush()
        }
    }

    /**
     * Точка входа из hot-path котировок. Только in-memory, без БД и без блокировок.
     *
     * Устаревшие котировки (часы/минуты назад) игнорируются: иначе после
     * реконнекта WS или задержки сети закрылся бы бакет из будущего и записался
     * раньше актуальных данных, нарушив хронологию (и point-in-time признаки
     * получили бы lookahead).
     */
    fun record(tick: QuoteTick) {
        if (!config.enabled) return

        val now = timeSource.nowMillis()
        val bucketStart = now - (now % bucketMs)
        val existing = open[tick.ticker]
        if (existing != null && existing.startMs > bucketStart) {
            meterRegistry.counter("microstructure.skipped", Tags.of("ticker", tick.ticker, "reason", "stale")).increment()
            return
        }

        open.compute(tick.ticker) { _, current ->
            if (current == null) {
                Bucket(tick.ticker, bucketStart).also { it.add(tick) }
            } else if (current.startMs == bucketStart) {
                current.also { it.add(tick) }
            } else {
                seal(current)
                Bucket(tick.ticker, bucketStart).also { it.add(tick) }
            }
        }

        meterRegistry.counter("microstructure.recorded", Tags.of("ticker", tick.ticker)).increment()
    }

    /**
     * Периодический сброс закрытых бакетов в БД батчем.
     *
     * Ошибка записи fail-soft: батчи возвращаются в начало очереди, чтобы не
     * потерять данные, и логируются с частотой, не создавая retry-шторм.
     */
    @Scheduled(fixedDelayString = "#{@microstructureConfig.flushIntervalMs}")
    fun scheduledFlush() {
        if (!config.enabled) return
        sealStale()
        // Без флага медленный БД накапливал бы по одной корутине на каждый тик
        // расписания; перекрывающиеся flush безопасны для очереди, но не нужны.
        if (!flushInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                flush()
            } finally {
                flushInFlight.set(false)
            }
        }
    }

    /** Запись накопленных закрытых бакетов. Вынесено отдельно для тестов. */
    suspend fun flush() {
        val batch = mutableListOf<MicrostructureSnapshot>()
        while (batch.size < BATCH_LIMIT) {
            val snapshot = pending.poll() ?: break
            pendingSize.decrementAndGet()
            batch += snapshot
        }
        if (batch.isEmpty()) return

        try {
            val inserted = repository.saveAll(batch)
            meterRegistry.counter("microstructure.flush.rows").increment(inserted.toDouble())
            if (inserted < batch.size) {
                // Конфликты означают повторную запись того же бакета (ретрай/рестарт) — не ошибка.
                logger.debug { "microstructure: ${batch.size - inserted} already persisted" }
            }
        } catch (e: Exception) {
            batch.asReversed().forEach(::enqueue)
            meterRegistry.counter("microstructure.flush.errors").increment()
            logger.warn(e) { "microstructure flush failed, ${batch.size} snapshots returned to queue" }
        }
    }

    /**
     * Кладёт закрытый бакет в очередь с drop-oldest политикой.
     *
     * Используется и при закрытии бакета, и при возврате батча после ошибки
     * записи: иначе повторный батч раздувал бы очередь сверх [MicrostructureConfig.maxQueueSize].
     */
    private fun enqueue(snapshot: MicrostructureSnapshot) {
        if (pendingSize.get() >= config.maxQueueSize) {
            // Drop-oldest: свежие наблюдения ценнее, чем хранение backlog'а.
            if (pending.poll() != null) {
                pendingSize.decrementAndGet()
                meterRegistry.counter("microstructure.flush.dropped").increment()
            }
        }
        pending.add(snapshot)
        pendingSize.incrementAndGet()
    }

    /**
     * Закрывает бакеты, по которым давно не было котировок (тикер перестал торговаться).
     *
     * Именно закрывает, а не отбрасывает: тикер, вставший на паузу, не пришлёт
     * следующую котировку, поэтому незакрытый бакет иначе потерял бы все наблюдения.
     */
    internal fun sealStale() {
        val now = timeSource.nowMillis()
        open.entries.forEach { (ticker, bucket) ->
            if (now - bucket.startMs > STALE_BUCKETS * bucketMs && open.remove(ticker, bucket)) {
                seal(bucket)
            }
        }
    }

    private fun sealAll() {
        open.entries.forEach { (ticker, bucket) -> if (open.remove(ticker, bucket)) seal(bucket) }
    }

    private fun seal(bucket: Bucket) {
        bucket.snapshot()?.let(::enqueue)
    }

    /**
     * Накопитель одного бакета. Суммы + счётчики: средние считаются при
     * закрытии, а не скользящим средним, поэтому пропуски (тик без bid/ask или
     * без размеров) не искажают остальные наблюдения.
     */
    private inner class Bucket(
        val ticker: String,
        val startMs: Long,
    ) {
        private var count: Long = 0
        private var lastPrice: BigDecimal? = null
        private var lastBid: BigDecimal? = null
        private var lastAsk: BigDecimal? = null
        private var lastBidSize: Long? = null
        private var lastAskSize: Long? = null

        private var obiSum: BigDecimal = BigDecimal.ZERO
        private var obiCount: Long = 0
        private var micropriceSum: BigDecimal = BigDecimal.ZERO
        private var micropriceCount: Long = 0
        private var deviationSum: BigDecimal = BigDecimal.ZERO
        private var deviationCount: Long = 0
        private var spreadSum: BigDecimal = BigDecimal.ZERO
        private var spreadCount: Long = 0

        fun add(tick: QuoteTick) {
            count++
            lastPrice = tick.price
            if (tick.bid != null && tick.ask != null) {
                lastBid = tick.bid
                lastAsk = tick.ask
                val mid = tick.bid.add(tick.ask).divide(TWO, BPS_SCALE, RoundingMode.HALF_UP)
                if (mid.signum() > 0) {
                    val ratio = tick.ask.subtract(tick.bid).divide(mid, BPS_SCALE, RoundingMode.HALF_UP)
                    spreadSum = spreadSum.add(ratio.multiply(BPS))
                    spreadCount++
                }
            }
            if (tick.bidSize != null) lastBidSize = tick.bidSize
            if (tick.askSize != null) lastAskSize = tick.askSize

            val obi = ObiCalculator.calculate(tick.bidSize, tick.askSize)
            if (obi != null) {
                obiSum = obiSum.add(obi)
                obiCount++
            }
            val microprice = MicropriceCalculator.calculate(tick.bid, tick.ask, tick.bidSize, tick.askSize)
            if (microprice != null) {
                micropriceSum = micropriceSum.add(microprice)
                micropriceCount++
                val mid = tick.bid!!.add(tick.ask!!).divide(TWO, BPS_SCALE, RoundingMode.HALF_UP)
                if (mid.signum() > 0) {
                    val ratio = microprice.subtract(mid).divide(mid, BPS_SCALE, RoundingMode.HALF_UP)
                    deviationSum = deviationSum.add(ratio.multiply(BPS))
                    deviationCount++
                }
            }
        }

        fun snapshot(): MicrostructureSnapshot? {
            if (count == 0L) return null
            return MicrostructureSnapshot(
                ticker = ticker,
                time = LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), moscowZone),
                updateCount = count,
                price = lastPrice,
                bid = lastBid,
                ask = lastAsk,
                bidSize = lastBidSize,
                askSize = lastAskSize,
                spreadBps = spreadSum.mean(spreadCount),
                obi = obiSum.mean(obiCount),
                microprice = micropriceSum.mean(micropriceCount),
                micropriceDeviationBps = deviationSum.mean(deviationCount),
            )
        }

        private fun BigDecimal.mean(n: Long): BigDecimal? {
            if (n == 0L) return null
            return divide(BigDecimal(n), MEAN_SCALE, RoundingMode.HALF_UP)
        }
    }

    private companion object {
        const val BATCH_LIMIT = 500
        const val STALE_BUCKETS = 3L
        const val MAX_SHUTDOWN_PASSES = 3
        const val BPS_SCALE = 8
        const val MEAN_SCALE = 6
        val TWO = BigDecimal(2)
        val BPS = BigDecimal(10_000)
    }
}
