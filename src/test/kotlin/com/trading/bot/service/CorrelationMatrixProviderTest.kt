package com.trading.bot.service

import com.trading.bot.domain.risk.PortfolioDataQuality
import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.Month

/**
 * Математика коэффициента корреляции Пирсона в [CorrelationMatrixProvider].
 */
class CorrelationMatrixProviderTest {
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val provider = CorrelationMatrixProvider(candleCache)

    private fun closes(values: List<Double>): List<Candle> {
        var t = LocalDateTime.of(2026, Month.AUGUST, 3, 10, 0)
        return values.map { v ->
            Candle(
                ticker = "X",
                timeframe = "MINUTE_10",
                openPrice = BigDecimal(v),
                highPrice = BigDecimal(v),
                lowPrice = BigDecimal(v),
                closePrice = BigDecimal(v),
                volume = 0,
                time = t,
            ).also { t = t.plusMinutes(10) }
        }
    }

    /** Ряд с пропусками: остаются только бары с индексами из [keepIndexes]. */
    private fun closesWithGaps(
        values: List<Double>,
        keepIndexes: (Int) -> Boolean,
    ): List<Candle> = closes(values).filterIndexed { index, _ -> keepIndexes(index) }

    private fun stubSeries(
        ticker: String,
        values: List<Double>,
    ) {
        stubCandles(ticker, closes(values))
    }

    private fun stubCandles(
        ticker: String,
        candles: List<Candle>,
    ) {
        Mockito.`when`(candleCache.getRecentCandles(ticker, "MINUTE_10", 50)).thenReturn(candles)
    }

    @Test
    fun `identical series have correlation 1`() {
        val series = (1..50).map { i -> 100.0 + i }
        stubSeries("A", series)
        stubSeries("B", series)

        val corr = provider.correlationOf("A", "B")

        assertEquals(1.0, corr!!, 1e-6)
    }

    @Test
    fun `inverted series have correlation minus 1`() {
        stubSeries("A", (1..50).map { i -> 100.0 + i })
        stubSeries("B", (1..50).map { i -> 200.0 - i })

        val corr = provider.correlationOf("A", "B")

        assertEquals(-1.0, corr!!, 1e-6)
    }

    @Test
    fun `same ticker has correlation 1 without cache access`() {
        assertEquals(1.0, provider.correlationOf("A", "A")!!, 1e-9)
    }

    @Test
    fun `correlation is null when fewer than 30 samples`() {
        stubSeries("A", (1..10).map { i -> 100.0 + i })
        stubSeries("B", (1..10).map { i -> 100.0 + i })

        assertNull(provider.correlationOf("A", "B"))
    }

    @Test
    fun `independent series have near zero correlation`() {
        stubSeries("A", (1..50).map { i -> 100.0 + i })
        stubSeries("B", (1..50).map { i -> 100.0 + ((i * 37) % 11) })

        val corr = provider.correlationOf("A", "B")

        assertEquals(0.0, corr!!, 0.5)
    }

    @Test
    fun `resolvedWithQuality is known when all pairs have data`() {
        stubSeries("A", (1..50).map { i -> 100.0 + i })
        stubSeries("B", (1..50).map { i -> 200.0 - i })

        val result = provider.resolvedWithQuality(listOf("A", "B"))

        assertEquals(PortfolioDataQuality.KNOWN, result.quality)
        assertEquals(-1.0, result.matrix[0][1], 1e-6)
        assertEquals(-1.0, result.matrix[1][0], 1e-6)
    }

    @Test
    fun `resolvedWithQuality is insufficient when a pair has no data`() {
        stubSeries("A", (1..10).map { i -> 100.0 + i })
        stubSeries("B", (1..10).map { i -> 100.0 + i })

        val result = provider.resolvedWithQuality(listOf("A", "B"))

        assertEquals(PortfolioDataQuality.INSUFFICIENT, result.quality)
        assertEquals(1.0, result.matrix[0][1], 1e-9)
    }

    @Test
    fun `series are aligned by time so missing bars do not shift the pairs`() {
        // A торгует каждые 10 минут (60 баров), B — только по чётным барам (30 из 60).
        // Значения в общих точках равны → корреляция совместного движения = 1.
        // Сопоставление по индексу (старое поведение) дало бы 0.5: A.takeLast(30)
        // (i=30..59) с B целиком — это РАЗНЫЕ моменты времени.
        val values = (0 until 60).map { i -> 100.0 + 10 * (i % 5) }
        stubSeries("A", values)
        stubCandles("B", closesWithGaps(values) { it % 2 == 0 })

        assertEquals(1.0, provider.correlationOf("A", "B")!!, 1e-9)
    }

    @Test
    fun `too few overlapping timestamps yield null instead of pairing different moments`() {
        // 60 баров A и 20 общих с B: общих наблюдений меньше correlationMinSamples,
        // честный ответ — null, а не корреляция по индексу на разных моментах.
        val values = (0 until 60).map { i -> 100.0 + 10 * (i % 5) }
        stubSeries("A", values)
        stubCandles("B", closesWithGaps(values) { it % 3 == 0 })

        assertNull(provider.correlationOf("A", "B"))
    }

    @Test
    fun `aligned closes keep only timestamps present in both series`() {
        val a = closes((1..5).map { it.toDouble() })
        val b = closes((1..5).map { it.toDouble() * 2 }).drop(2)

        val pairs = provider.alignedCloses(a, b)

        assertEquals(3, pairs.size)
        assertEquals(3.0, pairs.first().first, 1e-9)
        assertEquals(6.0, pairs.first().second, 1e-9)
    }

    @Test
    fun `correlationOf rejects a period below the minimum sample count`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                provider.correlationOf("A", "B", period = provider.correlationMinSamples - 1)
            }

        assertTrue(error.message!!.contains("correlationMinSamples"), error.message)
    }

    @Test
    fun `correlations rejects a too small period instead of returning an all-null matrix`() {
        // /risk/correlation?period=10 раньше возвращал матрицу из null с HTTP 200 —
        // «нет данных» было неотличимо от «корреляция не считается».
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                provider.correlations(listOf("A", "B"), period = 10)
            }

        assertTrue(error.message!!.contains("period=10"), error.message)
        Mockito.verifyNoInteractions(candleCache)
    }
}
