package com.trading.bot.service

import com.trading.bot.client.QuoteTick
import com.trading.bot.config.MicrostructureConfig
import com.trading.bot.model.entity.MicrostructureSnapshot
import com.trading.bot.repository.MicrostructureSnapshotRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Duration

/**
 * Тесты бакетизации и агрегации микроструктуры.
 *
 * Проверяются инварианты, на которых держится пригодность данных для research:
 * корректность средних, игнорирование неполных котировок, защита от
 * lookahead из устаревших котировок и fail-soft поведение при ошибке записи.
 */
class MicrostructureRecorderTest {
    private val repository: MicrostructureSnapshotRepository = mock()
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry()
    private var now = 0L

    private fun recorder(
        enabled: Boolean = true,
        bucketMs: Long = 1_000L,
        maxQueue: Int = 20_000,
    ) = MicrostructureRecorder(
        config =
            MicrostructureConfig().apply {
                this.enabled = enabled
                this.bucketMs = bucketMs
                this.maxQueueSize = maxQueue
            },
        repository = repository,
        meterRegistry = meterRegistry,
        timeSource = { now },
    )

    private fun tick(
        price: String = "100.0",
        bid: String? = "99.9",
        ask: String? = "100.1",
        bidSize: Long? = 10,
        askSize: Long? = 10,
        ticker: String = "CNYRUBF",
    ) = QuoteTick(
        ticker = ticker,
        price = BigDecimal(price),
        bid = bid?.let(::BigDecimal),
        ask = ask?.let(::BigDecimal),
        bidSize = bidSize,
        askSize = askSize,
    )

    @Test
    fun `record aggregates multiple quotes of the same bucket into one snapshot`() {
        val rec = recorder()
        // Три котировки внутри бакета [1000, 2000)
        listOf(1_000L, 1_400L, 1_900L).forEach {
            now = it
            rec.record(tick(bidSize = 30, askSize = 10))
        }
        // Переход в следующий бакет закрывает первый
        now = 2_000L
        rec.record(tick())

        runBlocking { rec.flush() }

        val saved = captureSaved()
        assertEquals(1, saved.size)
        val snapshot = saved.first()
        assertEquals("CNYRUBF", snapshot.ticker)
        assertEquals(3L, snapshot.updateCount)
        // OBI = (30-10)/40 = 0.5 на всех трёх котировках
        assertBigDecimalEquals("0.5", snapshot.obi)
    }

    @Test
    fun `snapshot keeps last observed prices and sizes of the bucket`() {
        val rec = recorder()
        now = 1_000L
        rec.record(tick(price = "100.0", bidSize = 5, askSize = 5))
        now = 1_500L
        rec.record(tick(price = "101.0", bidSize = 7, askSize = 3))
        now = 2_000L
        rec.record(tick())

        runBlocking { rec.flush() }

        val snapshot = captureSaved().first()
        assertBigDecimalEquals("101.0", snapshot.price)
        assertEquals(7L, snapshot.bidSize)
        assertEquals(3L, snapshot.askSize)
    }

    @Test
    fun `quotes without bid ask or sizes are counted but excluded from microstructure metrics`() {
        val rec = recorder()
        // Котировка только с ценой: bid/ask и размеры отсутствуют
        now = 1_000L
        rec.record(tick(bid = null, ask = null, bidSize = null, askSize = null))
        // Котировка с ценами, но без размеров: OBI/microprice не определены
        now = 1_200L
        rec.record(tick(bidSize = null, askSize = null))
        now = 2_000L
        rec.record(tick())

        runBlocking { rec.flush() }

        val snapshot = captureSaved().first()
        assertEquals(2L, snapshot.updateCount)
        assertNull(snapshot.obi)
        assertNull(snapshot.microprice)
        assertNull(snapshot.micropriceDeviationBps)
    }

    @Test
    fun `microprice deviation is signed and points to the side with more liquidity`() {
        val rec = recorder()
        // Много ликвидности по bid -> microprice ближе к ask, т.е. выше mid
        now = 1_000L
        rec.record(tick(bidSize = 90, askSize = 10))
        now = 2_000L
        rec.record(tick())

        runBlocking { rec.flush() }

        val deviation = requireNotNull(captureSaved().first().micropriceDeviationBps)
        assertTrue(deviation.signum() > 0, "ожидалось смещение вверх, получено $deviation")
    }

    @Test
    fun `stale quote from earlier bucket is discarded to preserve chronology`() {
        val rec = recorder()
        now = 5_000L
        rec.record(tick(price = "110.0"))
        // Котировка из прошлого бакета (реконнект WS / задержка сети)
        now = 4_000L
        rec.record(tick(price = "90.0"))
        now = 6_000L
        rec.record(tick())

        runBlocking { rec.flush() }

        val saved = captureSaved()
        // Устаревшая котировка (price=90.0) не должна была ни закрыть бакет,
        // ни попасть в агрегаты: сохраняется только бакет 5000 с одной котировкой.
        assertEquals(1, saved.size)
        assertEquals(1L, saved.first().updateCount)
        assertBigDecimalEquals("110.0", saved.first().price)
    }

    @Test
    fun `separate tickers are bucketed independently`() {
        val rec = recorder()
        now = 1_000L
        rec.record(tick(ticker = "CNYRUBF"))
        rec.record(tick(ticker = "EURRUBF"))
        now = 2_000L
        rec.record(tick(ticker = "CNYRUBF"))
        rec.record(tick(ticker = "EURRUBF"))

        runBlocking { rec.flush() }

        val saved = captureSaved()
        assertEquals(2, saved.size)
        assertEquals(setOf("CNYRUBF", "EURRUBF"), saved.map { it.ticker }.toSet())
    }

    @Test
    fun `disabled recorder does not touch repository`() {
        val rec = recorder(enabled = false)
        now = 1_000L
        rec.record(tick())
        now = 2_000L
        rec.record(tick())
        runBlocking { rec.flush() }

        runBlocking { verify(repository, never()).saveAll(any()) }
    }

    @Test
    fun `failed flush returns snapshots to queue for the next attempt`() {
        val rec = recorder()
        runBlocking { whenever(repository.saveAll(any())).thenThrow(IllegalStateException("db down")) }
        now = 1_000L
        rec.record(tick())
        now = 2_000L
        rec.record(tick())

        runBlocking {
            rec.flush()
            verify(repository, times(1)).saveAll(any())
            // Второй вызов повторно пытается записать возвращённые бакеты
            rec.flush()
            verify(repository, times(2)).saveAll(any())
        }
    }

    @Test
    fun `overflowing queue drops oldest snapshot instead of blocking`() {
        val rec = recorder(maxQueue = 1)
        // Три последовательных закрытых бакета при очереди на 1 элемент
        for (i in 1..3) {
            now = i * 1_000L
            rec.record(tick(price = "${100 + i}.0"))
            now = (i + 1) * 1_000L
            rec.record(tick(price = "${100 + i}.5"))
        }

        runBlocking { rec.flush() }

        val saved = captureSaved()
        assertEquals(1, saved.size)
        // Выжил самый свежий из закрытых бакетов ([3000,4000), две котировки),
        // а не самый старый ([1000,2000)) и не текущий незакрытый бакет 4000.
        assertEquals(2L, saved.first().updateCount)
        assertBigDecimalEquals("103.0", saved.first().price)
    }

    @Test
    fun `stale bucket of idle ticker is sealed and persisted instead of dropped`() {
        val rec = recorder()
        now = 1_000L
        rec.record(tick(price = "100.0"))
        // Тикер встал на паузу: следующей котировки не будет, а бакет протух
        // (STALE_BUCKETS=3 x bucketMs=1000, т.е. лаг больше 3с).
        now = 5_000L
        rec.sealStale()

        runBlocking { rec.flush() }

        val saved = captureSaved()
        assertEquals(1, saved.size)
        assertEquals(1L, saved.first().updateCount)
        assertBigDecimalEquals("100.0", saved.first().price)
    }

    @Test
    fun `stale sweep keeps fresh bucket of active ticker open`() {
        val rec = recorder()
        now = 5_000L
        rec.record(tick(price = "100.0"))
        // Бакет ещё не протух: тикер продолжает котироваться
        now = 6_000L
        rec.sealStale()

        runBlocking {
            rec.flush()
            verify(repository, never()).saveAll(any())
        }
    }

    @Test
    fun `close seals open buckets and flushes them synchronously`() {
        val rec = recorder()
        stubSaveSucceeds()
        now = 1_000L
        rec.record(tick(price = "100.0"))
        now = 2_000L
        rec.record(tick(price = "101.0"))

        rec.close()

        // Бакет [1000,2000) закрыт переходом, текущий бакет 2000 — только остановкой.
        // Оба обязаны уйти в БД: незакрытый бакет иначе потерял бы все наблюдения.
        val saved = captureSaved().sortedBy { it.time }
        assertEquals(2, saved.size)
        assertEquals(1L, saved[0].updateCount)
        assertBigDecimalEquals("100.0", saved[0].price)
        assertBigDecimalEquals("101.0", saved[1].price)
    }

    @Test
    fun `close does not hang when database write keeps failing`() {
        val rec = recorder()
        runBlocking { whenever(repository.saveAll(any())).thenThrow(IllegalStateException("db down")) }
        now = 1_000L
        rec.record(tick())
        now = 2_000L
        rec.record(tick())

        // Батчи возвращаются в очередь при ошибке; ретраи на shutdown ограничены
        assertTimeoutPreemptively(Duration.ofSeconds(10)) { rec.close() }

        runBlocking { verify(repository, atLeastOnce()).saveAll(any()) }
    }

    /**
     * Стабует успешную запись.
     *
     * Мок suspend-функции без стаба возвращает `null`, что recorder честно
     * трактует как ошибку БД и возвращает батчи в очередь — тесты на успешный
     * путь обязаны стабить результат явно.
     */
    private fun stubSaveSucceeds() {
        runBlocking {
            whenever(repository.saveAll(any())).thenReturn(0)
        }
    }

    /**
     * Сравнение по значению, а не по scale: расчётные величины округляются
     * до 6 знаков, и `0.500000.compareTo(0.5) == 1` без stripTrailingZeros.
     */
    private fun assertBigDecimalEquals(
        expected: String,
        actual: BigDecimal?,
    ) {
        assertEquals(
            0,
            BigDecimal(expected).stripTrailingZeros().compareTo(actual?.stripTrailingZeros()),
            "ожидалось $expected, получено $actual",
        )
    }

    private fun captureSaved(): List<MicrostructureSnapshot> {
        val captor = argumentCaptor<List<MicrostructureSnapshot>>()
        runBlocking { verify(repository, atLeastOnce()).saveAll(captor.capture()) }
        return captor.allValues.flatten()
    }
}
