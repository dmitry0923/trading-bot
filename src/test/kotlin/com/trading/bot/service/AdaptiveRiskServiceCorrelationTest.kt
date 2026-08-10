package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.MarketRegime
import com.trading.bot.domain.risk.MarketRegimeProvider
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Корреляционный фильтр AdaptiveRiskService: запрет входа при корреляции
 * с открытой позицией > 0.8 (исключение — фьючерсный хедж Si).
 *
 * Сама математика Пирсона — в [CorrelationMatrixProvider] (тест там);
 * здесь проверяется только логика фильтра.
 */
class AdaptiveRiskServiceCorrelationTest {
    private val riskConfig = RiskConfig()
    private val tradeAnalysis = Mockito.mock(TradeAnalysisService::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val drawdownProtection = Mockito.mock(DrawdownProtectionService::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val correlationProvider = Mockito.mock(CorrelationMatrixProvider::class.java)
    private val marketRegimeProvider: MarketRegimeProvider = { MarketRegime.NORMAL }

    private val service =
        AdaptiveRiskService(
            riskConfig,
            tradeAnalysis,
            positionRepo,
            candleCache,
            drawdownProtection,
            meterRegistry,
            correlationProvider,
            marketRegimeProvider,
            Mockito.mock(AumProvider::class.java),
        )

    private fun stubCorrelation(value: Double?) {
        Mockito.`when`(correlationProvider.correlationOf("A", "B", "MINUTE_10", 50)).thenReturn(value)
    }

    @Test
    fun `entry blocked when correlated with open position`() {
        stubCorrelation(1.0)
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
        stubCorrelation(null)
        val openPosition =
            Position(
                id = 1,
                ticker = "B",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("120"),
            )

        assertFalse(service.exceedsCorrelationLimit("A", listOf(openPosition)))
        assertTrue(service.correlationOf("A", "B") == null)
    }

    @Test
    fun `sector correlation filter blocks second position in same sector`() =
        runBlocking {
            riskConfig.sectors = mapOf("A" to "ENERGY", "B" to "ENERGY")
            riskConfig.maxSectorCorrelation = 0.7
            stubCorrelation(0.9)
            val openPosition =
                Position(
                    id = 1,
                    ticker = "B",
                    direction = PositionDirection.LONG,
                    quantity = 1,
                    entryPrice = BigDecimal("120"),
                )

            assertTrue(service.exceedsSectorCorrelationLimit("A", listOf(openPosition)))
        }

    @Test
    fun `sector correlation filter passes when correlation below threshold`() =
        runBlocking {
            riskConfig.sectors = mapOf("A" to "ENERGY", "B" to "ENERGY")
            riskConfig.maxSectorCorrelation = 0.7
            stubCorrelation(0.5)
            val openPosition =
                Position(
                    id = 1,
                    ticker = "B",
                    direction = PositionDirection.LONG,
                    quantity = 1,
                    entryPrice = BigDecimal("120"),
                )

            assertFalse(service.exceedsSectorCorrelationLimit("A", listOf(openPosition)))
        }
}
