package com.trading.bot.application.risk

import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.PortfolioRiskRequest
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.CorrelationMatrixProvider
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import kotlin.math.abs

/**
 * Портфельный риск-движок: агрегированная дисперсия Markowitz, VaR95,
 * эффективное число независимых ставок и направленная концентрация.
 *
 * Ключевой сценарий (BTC/ETH/SOL): три коррелированные позиции с ρ=0.75 < 0.8
 * проходят попарный фильтр, но блокируются агрегатом как «одна ставка на рынок».
 */
class PortfolioRiskEngineClusterTest {
    private val riskConfig = RiskConfig()
    private val correlationProvider = Mockito.mock(CorrelationMatrixProvider::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val engine = PortfolioRiskEngineImpl(riskConfig, correlationProvider, candleCache, meterRegistry)

    private fun position(
        ticker: String,
        price: BigDecimal,
        qty: Int = 1,
    ): Position =
        Position(
            id = ticker.hashCode().toLong(),
            ticker = ticker,
            direction = PositionDirection.LONG,
            quantity = qty,
            entryPrice = price,
        )

    private fun stubVols(
        tickers: List<String>,
        volPercent: Double,
    ) {
        tickers.forEach { t ->
            Mockito.`when`(candleCache.calculateRealizedVolatility(t, "DAY_1", 20)).thenReturn(volPercent)
        }
    }

    private fun stubCorrelations(
        tickers: List<String>,
        corr: Double?,
    ) {
        Mockito.`when`(correlationProvider.correlations(tickers, "MINUTE_10", 50)).thenReturn(
            tickers.associateWith { a ->
                tickers.associateWith { b -> if (a == b) 1.0 else corr }
            },
        )
    }

    @Test
    fun `three correlated longs are one market bet and get blocked`() =
        runBlocking {
            riskConfig.portfolioEffectiveWarnPositions = 2.0
            riskConfig.minEffectivePositions = 1.5
            riskConfig.maxPortfolioVaRPercent = 5.0
            stubVols(listOf("BTC", "ETH", "SOL"), 1.0)
            stubCorrelations(listOf("BTC", "ETH", "SOL"), 0.75)

            val report =
                engine.evaluate(
                    PortfolioRiskRequest(
                        candidateTicker = "SOL",
                        candidateDirection = PositionDirection.LONG,
                        candidateNotionalRub = BigDecimal("100"),
                        openPositions = listOf(position("BTC", BigDecimal("100")), position("ETH", BigDecimal("100"))),
                        aum = BigDecimal("1000"),
                    ),
                )

            assertFalse(report.allowed)
            assertTrue("PORTFOLIO_CONCENTRATION" in report.reasons)
            assertEquals(1.2, report.effectivePositions.toDouble(), 0.01)
            assertEquals(0.75, report.maxPairCorrelation, 1e-9)
        }

    @Test
    fun `perfectly correlated cluster has effective positions of one`() =
        runBlocking {
            stubVols(listOf("A", "B", "C"), 1.0)
            stubCorrelations(listOf("A", "B", "C"), 1.0)

            val report =
                engine.evaluate(
                    PortfolioRiskRequest(
                        candidateTicker = "C",
                        candidateDirection = PositionDirection.LONG,
                        candidateNotionalRub = BigDecimal("100"),
                        openPositions = listOf(position("A", BigDecimal("100")), position("B", BigDecimal("100"))),
                        aum = BigDecimal("1000"),
                    ),
                )

            assertEquals(1.0, report.effectivePositions.toDouble(), 1e-6)
            assertTrue("PORTFOLIO_CONCENTRATION" in report.reasons)
        }

    @Test
    fun `independent equal positions are not a cluster and allowed`() =
        runBlocking {
            stubVols(listOf("A", "B", "C"), 1.0)
            stubCorrelations(listOf("A", "B", "C"), 0.0)

            val report =
                engine.evaluate(
                    PortfolioRiskRequest(
                        candidateTicker = "C",
                        candidateDirection = PositionDirection.LONG,
                        candidateNotionalRub = BigDecimal("100"),
                        openPositions = listOf(position("A", BigDecimal("100")), position("B", BigDecimal("100"))),
                        aum = BigDecimal("1000"),
                    ),
                )

            assertTrue(report.allowed, "independent positions should be allowed: $report")
            assertEquals(3.0, report.effectivePositions.toDouble(), 0.01)
        }

    @Test
    fun `single position is baseline and not blocked by concentration`() =
        runBlocking {
            stubVols(listOf("A"), 1.0)
            stubCorrelations(listOf("A"), null)

            val report =
                engine.evaluate(
                    PortfolioRiskRequest(
                        candidateTicker = "A",
                        candidateDirection = PositionDirection.LONG,
                        candidateNotionalRub = BigDecimal("100"),
                        openPositions = emptyList(),
                        aum = BigDecimal("1000"),
                    ),
                )

            assertTrue(report.allowed)
            assertTrue(report.reasons.isEmpty())
        }

    @Test
    fun `moderate var scales down position size instead of blocking`() =
        runBlocking {
            riskConfig.portfolioVarWarnPercent = 3.0
            riskConfig.maxPortfolioVaRPercent = 5.0
            riskConfig.minPortfolioScaleFactor = 0.25
            stubVols(listOf("A", "B", "C"), 5.0)
            stubCorrelations(listOf("A", "B", "C"), 0.0)

            val report =
                engine.evaluate(
                    PortfolioRiskRequest(
                        candidateTicker = "C",
                        candidateDirection = PositionDirection.LONG,
                        candidateNotionalRub = BigDecimal("300"),
                        openPositions = listOf(position("A", BigDecimal("300")), position("B", BigDecimal("300"))),
                        aum = BigDecimal("1000"),
                    ),
                )

            assertTrue(report.allowed)
            assertTrue(report.scaleDownFactor < BigDecimal.ONE, "should scale down: $report")
            assertTrue(report.scaleDownFactor > BigDecimal(riskConfig.minPortfolioScaleFactor.toString()))
            assertEquals(0.52, report.scaleDownFactor.toDouble(), 0.03)
        }

    @Test
    fun `blocked mode off only scales and never rejects`() =
        runBlocking {
            riskConfig.portfolioRiskBlocked = false
            stubVols(listOf("BTC", "ETH", "SOL"), 10.0)
            stubCorrelations(listOf("BTC", "ETH", "SOL"), 0.95)

            val report =
                engine.evaluate(
                    PortfolioRiskRequest(
                        candidateTicker = "SOL",
                        candidateDirection = PositionDirection.LONG,
                        candidateNotionalRub = BigDecimal("100"),
                        openPositions = listOf(position("BTC", BigDecimal("100")), position("ETH", BigDecimal("100"))),
                        aum = BigDecimal("1000"),
                    ),
                )

            assertTrue(report.allowed)
            assertTrue(report.scaleDownFactor <= BigDecimal.ONE)
        }

    @Test
    fun `disabled engine allows everything`() =
        runBlocking {
            riskConfig.portfolioRiskEnabled = false

            val report =
                engine.evaluate(
                    PortfolioRiskRequest(
                        candidateTicker = "A",
                        candidateDirection = PositionDirection.LONG,
                        candidateNotionalRub = BigDecimal("100"),
                        openPositions = emptyList(),
                        aum = BigDecimal("1000"),
                    ),
                )

            assertTrue(report.allowed)
            assertTrue(abs(report.scaleDownFactor.toDouble() - 1.0) < 1e-9)
        }
}
