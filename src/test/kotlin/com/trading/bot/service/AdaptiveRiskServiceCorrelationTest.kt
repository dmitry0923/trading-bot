package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.model.Candle
import com.trading.bot.model.Position
import com.trading.bot.model.PositionDirection
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.Month
import kotlin.math.abs

/**
 * Корреляционный фильтр AdaptiveRiskService: запрет входа при корреляции
 * с открытой позицией > 0.8 (исключение — фьючерсный хедж Si).
 */
class AdaptiveRiskServiceCorrelationTest {
    private val riskConfig = RiskConfig()
    private val tradeAnalysis = Mockito.mock(TradeAnalysisService::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val service = AdaptiveRiskService(riskConfig, tradeAnalysis, positionRepo, candleCache, meterRegistry)

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

        val corr = service.correlationOf("A", "B")

        assertEquals(1.0, corr!!, 1e-6)
    }

    @Test
    fun `inverted series have correlation minus 1`() {
        stubSeries("A", (1..50).map { i -> 100.0 + i })
        stubSeries("B", (1..50).map { i -> 200.0 - i })

        val corr = service.correlationOf("A", "B")

        assertEquals(-1.0, corr!!, 1e-6)
    }

    @Test
    fun `correlation is null when fewer than 30 samples`() {
        stubSeries("A", (1..10).map { i -> 100.0 + i })
        stubSeries("B", (1..10).map { i -> 100.0 + i })

        assertNull(service.correlationOf("A", "B"))
    }

    @Test
    fun `entry blocked when correlated with open position`() {
        val series = (1..50).map { i -> 100.0 + i }
        stubSeries("A", series)
        stubSeries("B", series)
        val openPosition =
            Position(
                id = 1,
                ticker = "B",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("120"),
            )

        assertTrue(service.exceedsCorrelationLimit("A", listOf(openPosition)))
    }

    @Test
    fun `si hedge is never blocked by correlation`() {
        val series = (1..50).map { i -> 100.0 + i }
        stubSeries("Si", series)
        stubSeries("B", series)
        val openPosition =
            Position(
                id = 1,
                ticker = "B",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("120"),
            )

        assertFalse(service.exceedsCorrelationLimit("Si", listOf(openPosition)))
    }

    @Test
    fun `same ticker open position is not a correlation conflict`() {
        val openPosition =
            Position(
                id = 1,
                ticker = "A",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("120"),
            )

        assertFalse(service.exceedsCorrelationLimit("A", listOf(openPosition)))
    }

    @Test
    fun `insufficient data does not block entry`() {
        stubSeries("A", emptyList())
        stubSeries("B", emptyList())
        val openPosition =
            Position(
                id = 1,
                ticker = "B",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("120"),
            )

        assertFalse(service.exceedsCorrelationLimit("A", listOf(openPosition)))
        assertTrue(abs(service.correlationOf("A", "B") ?: 0.0 - 0.0) < 1e-9 || service.correlationOf("A", "B") == null)
    }
}
