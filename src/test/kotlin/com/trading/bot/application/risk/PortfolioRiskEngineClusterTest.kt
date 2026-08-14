package com.trading.bot.application.risk

import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.PortfolioDataQuality
import com.trading.bot.domain.risk.PortfolioRiskRequest
import com.trading.bot.domain.risk.ResolvedCorrelationMatrix
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Candle
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
import java.time.LocalDateTime
import java.time.Month
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
        Mockito.`when`(correlationProvider.resolvedWithQuality(tickers, "MINUTE_10", 50)).thenReturn(
            ResolvedCorrelationMatrix(
                matrix =
                    tickers.map { a ->
                        tickers.map { b -> if (a == b) 1.0 else (corr ?: 0.0) }
                    },
                quality = if (corr == null) PortfolioDataQuality.INSUFFICIENT else PortfolioDataQuality.KNOWN,
            ),
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
            stubCorrelations(listOf("A"), 1.0)

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
            assertEquals(1.0, report.dataQualityScale.toDouble(), 1e-9)
            assertEquals(1.0, report.scaleDownFactor.toDouble(), 1e-9)
        }

    @Test
    fun `moderate var scales down position size instead of blocking`() =
        runBlocking {
            riskConfig.portfolioVarWarnPercent = 3.0
            riskConfig.maxPortfolioVaRPercent = 5.0
            riskConfig.minPortfolioScaleFactor = 0.25
            // Stress Loss отключён — тест интерполяции SCALE по parametric VaR.
            riskConfig.portfolioStressSigma = 0.0
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

    @Test
    fun `no volatility data blocks instead of fail open`() =
        runBlocking {
            // Прежний fail-open (allowed=true при пустых данных о волатильности) запрещён.
            Mockito
                .`when`(candleCache.calculateRealizedVolatility(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(null)
            stubCorrelations(listOf("A"), 1.0)

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

            assertFalse(report.allowed)
            assertTrue("PORTFOLIO_DATA_INSUFFICIENT" in report.reasons)
            assertEquals(PortfolioDataQuality.INSUFFICIENT, report.volatilityDataQuality)
            assertEquals(0.0, report.dataQualityScale.toDouble(), 1e-9)
            assertEquals(0.0, report.scaleDownFactor.toDouble(), 1e-9)
        }

    @Test
    fun `no volatility data in soft mode scales to zero instead of fail open`() =
        runBlocking {
            riskConfig.portfolioRiskBlocked = false
            Mockito
                .`when`(candleCache.calculateRealizedVolatility(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(null)
            stubCorrelations(listOf("A"), 1.0)

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
            assertEquals(PortfolioDataQuality.INSUFFICIENT, report.volatilityDataQuality)
            assertEquals(0.0, report.scaleDownFactor.toDouble(), 1e-9)
        }

    @Test
    fun `estimated intraday volatility scales size to half`() =
        runBlocking {
            // Stress Loss отключён — тест шкалы качества данных ESTIMATED.
            riskConfig.portfolioStressSigma = 0.0
            Mockito.`when`(candleCache.calculateRealizedVolatility("A", "DAY_1", 20)).thenReturn(null)
            Mockito.`when`(candleCache.calculateRealizedVolatility("A", "MINUTE_10", 20)).thenReturn(2.0)
            stubCorrelations(listOf("A"), 1.0)

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
            assertEquals(PortfolioDataQuality.ESTIMATED, report.volatilityDataQuality)
            assertEquals(0.5, report.dataQualityScale.toDouble(), 1e-9)
            assertEquals(0.5, report.scaleDownFactor.toDouble(), 1e-9)
        }

    @Test
    fun `insufficient correlation data scales size to a quarter`() =
        runBlocking {
            stubVols(listOf("A", "B"), 1.0)
            // Пара A-B без данных — качество корреляций INSUFFICIENT.
            stubCorrelations(listOf("A", "B"), null)

            val report =
                engine.evaluate(
                    PortfolioRiskRequest(
                        candidateTicker = "B",
                        candidateDirection = PositionDirection.LONG,
                        candidateNotionalRub = BigDecimal("100"),
                        openPositions = listOf(position("A", BigDecimal("100"))),
                        aum = BigDecimal("1000"),
                    ),
                )

            assertTrue(report.allowed)
            assertEquals(PortfolioDataQuality.INSUFFICIENT, report.correlationDataQuality)
            assertEquals(0.25, report.dataQualityScale.toDouble(), 1e-9)
            assertEquals(0.25, report.scaleDownFactor.toDouble(), 1e-9)
        }

    @Test
    fun `estimated vol and insufficient correlation take the worse data quality scale`() =
        runBlocking {
            Mockito.`when`(candleCache.calculateRealizedVolatility("A", "DAY_1", 20)).thenReturn(null)
            Mockito.`when`(candleCache.calculateRealizedVolatility("A", "MINUTE_10", 20)).thenReturn(2.0)
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
            assertEquals(PortfolioDataQuality.ESTIMATED, report.volatilityDataQuality)
            assertEquals(PortfolioDataQuality.INSUFFICIENT, report.correlationDataQuality)
            assertEquals(0.25, report.dataQualityScale.toDouble(), 1e-9)
        }

    private fun singlePositionRequest(ticker: String = "A"): PortfolioRiskRequest =
        PortfolioRiskRequest(
            candidateTicker = ticker,
            candidateDirection = PositionDirection.LONG,
            candidateNotionalRub = BigDecimal("100"),
            openPositions = emptyList(),
            aum = BigDecimal("1000"),
        )

    private fun stubDailyCandles(
        ticker: String,
        closes: List<Double>,
    ) {
        var t = LocalDateTime.of(2026, Month.AUGUST, 3, 10, 0)
        Mockito.`when`(candleCache.getRecentCandles(ticker, "DAY_1", 61)).thenReturn(
            closes.map { v ->
                Candle(
                    ticker = ticker,
                    timeframe = "DAY_1",
                    openPrice = BigDecimal(v),
                    highPrice = BigDecimal(v),
                    lowPrice = BigDecimal(v),
                    closePrice = BigDecimal(v),
                    volume = 0,
                    time = t,
                ).also { t = t.plusDays(1) }
            },
        )
    }

    @Test
    fun `historical var and cvar reflect worst historical day and drive effective var`() =
        runBlocking {
            stubVols(listOf("A"), 0.5)
            stubCorrelations(listOf("A"), 1.0)
            // 8 дней падения по 2%, затем 52 дня роста по 1%: 5%-квантиль в зоне убытков.
            val closes =
                buildList {
                    add(100.0)
                    repeat(8) { add(last() * 0.98) }
                    repeat(52) { add(last() * 1.01) }
                }
            stubDailyCandles("A", closes)

            val report = engine.evaluate(singlePositionRequest())

            assertTrue(report.allowed)
            assertEquals(0.82, report.var95Rub.toDouble(), 0.05) // parametric 1.645*0.005*100
            assertEquals(2.02, report.historicalVar95Rub.toDouble(), 0.05)
            assertEquals(2.02, report.cvar95Rub.toDouble(), 0.05)
            assertEquals(1.25, report.stressLossRub.toDouble(), 0.05) // 100*0.025*0.5
            assertEquals(2.02, report.effectiveVar95Rub.toDouble(), 0.05) // worst of the four
        }

    @Test
    fun `stress loss assumes fully correlated shock and drives effective var without history`() =
        runBlocking {
            stubVols(listOf("A"), 10.0)
            stubCorrelations(listOf("A"), 1.0)
            // История не стабится -> historical/cvar = 0, worst = stress.
            val report = engine.evaluate(singlePositionRequest())

            assertEquals(16.45, report.var95Rub.toDouble(), 0.1) // 1.645*0.1*100
            assertEquals(25.0, report.stressLossRub.toDouble(), 0.1) // 100*0.025*10
            assertEquals(0.0, report.historicalVar95Rub.toDouble(), 1e-9)
            assertEquals(25.0, report.effectiveVar95Rub.toDouble(), 0.1)
        }

    @Test
    fun `historical series blends signed weights so a hedged book nets to zero`() =
        runBlocking {
            stubVols(listOf("A", "B"), 1.0)
            stubCorrelations(listOf("A", "B"), 0.0)
            val closes =
                buildList {
                    add(100.0)
                    repeat(8) { add(last() * 0.98) }
                    repeat(52) { add(last() * 1.01) }
                }
            stubDailyCandles("A", closes)
            stubDailyCandles("B", closes)

            val report =
                engine.evaluate(
                    PortfolioRiskRequest(
                        candidateTicker = "B",
                        candidateDirection = PositionDirection.SHORT,
                        candidateNotionalRub = BigDecimal("100"),
                        openPositions = listOf(position("A", BigDecimal("100"))),
                        aum = BigDecimal("1000"),
                    ),
                )

            assertTrue(report.allowed)
            assertEquals(0.0, report.historicalVar95Rub.toDouble(), 0.05) // хедж 0.5/-0.5
            assertEquals(0.0, report.cvar95Rub.toDouble(), 0.05)
            assertEquals(5.0, report.stressLossRub.toDouble(), 0.1) // 200*0.025*(0.5+0.5)
        }
}
