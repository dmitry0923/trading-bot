package com.trading.bot.service

import com.trading.bot.domain.risk.PortfolioDataQuality
import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    private fun stubSeries(
        ticker: String,
        values: List<Double>,
    ) {
        Mockito.`when`(candleCache.getRecentCandles(ticker, "MINUTE_10", 50)).thenReturn(closes(values))
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
}
