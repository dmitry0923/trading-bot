package com.trading.bot.application.risk

import com.trading.bot.config.InstrumentsConfig
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

/**
 * Портфельный движок — граничные ветки: пустая книга/депозит, каждый BLOCK-код,
 * fallback волатильности по максимуму, деградация исторической серии и все ветки
 * SCALE-интерполяции (включая вырожденные пороги).
 */
class PortfolioRiskEngineBranchesTest {
    private val riskConfig = RiskConfig()
    private val correlationProvider = Mockito.mock(CorrelationMatrixProvider::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val engine =
        PortfolioRiskEngineImpl(riskConfig, InstrumentsConfig(), correlationProvider, candleCache, meterRegistry)

    private fun position(
        ticker: String,
        price: BigDecimal,
        qty: Int = 1,
        direction: PositionDirection = PositionDirection.LONG,
    ): Position =
        Position(
            id = ticker.hashCode().toLong(),
            ticker = ticker,
            direction = direction,
            quantity = qty,
            entryPrice = price,
        )

    private fun stubVols(vararg volPercent: Pair<String, Double?>) {
        volPercent.forEach { (ticker, vol) ->
            Mockito.`when`(candleCache.calculateRealizedVolatility(ticker, "DAY_1", 20)).thenReturn(vol)
        }
    }

    private fun stubCorrelations(
        tickers: List<String>,
        corr: Double,
        quality: PortfolioDataQuality = PortfolioDataQuality.KNOWN,
    ) {
        Mockito.`when`(correlationProvider.resolvedWithQuality(tickers, "MINUTE_10", 50)).thenReturn(
            ResolvedCorrelationMatrix(
                matrix = tickers.map { a -> tickers.map { b -> if (a == b) 1.0 else corr } },
                quality = quality,
            ),
        )
    }

    private fun request(
        candidate: String = "A",
        direction: PositionDirection = PositionDirection.LONG,
        notional: String = "100",
        open: List<Position> = emptyList(),
        aum: String = "1000",
    ) = PortfolioRiskRequest(
        candidateTicker = candidate,
        candidateDirection = direction,
        candidateNotionalRub = BigDecimal(notional),
        openPositions = open,
        aum = BigDecimal(aum),
    )

    private fun stubCandles(
        ticker: String,
        closes: List<Double>,
    ) {
        var time = LocalDateTime.of(2026, Month.AUGUST, 3, 10, 0)
        Mockito.`when`(candleCache.getRecentCandles(ticker, "DAY_1", 61)).thenReturn(
            closes.map { value ->
                Candle(
                    ticker = ticker,
                    timeframe = "DAY_1",
                    openPrice = BigDecimal(value),
                    highPrice = BigDecimal(value),
                    lowPrice = BigDecimal(value),
                    closePrice = BigDecimal(value),
                    volume = 0,
                    time = time,
                ).also { time = time.plusDays(1) }
            },
        )
    }

    @Test
    fun `zero notional book is allowed without data`() =
        runBlocking {
            val report = engine.evaluate(request(notional = "0"))

            assertTrue(report.allowed)
            assertEquals(1.0, report.scaleDownFactor.toDouble(), 1e-9)
        }

    @Test
    fun `zero aum is allowed instead of dividing by zero`() =
        runBlocking {
            val report = engine.evaluate(request(aum = "0"))

            assertTrue(report.allowed)
            assertTrue(report.reasons.isEmpty())
        }

    @Test
    fun `var breach is reported as PORTFOLIO_VAR`() =
        runBlocking {
            riskConfig.maxPortfolioVarPercent = 0.01
            riskConfig.portfolioVarWarnPercent = 0.01
            riskConfig.portfolioStressSigma = 0.0
            stubVols("A" to 10.0)
            stubCorrelations(listOf("A"), 0.0)

            val report = engine.evaluate(request())

            assertFalse(report.allowed)
            assertTrue(report.reasons.contains("PORTFOLIO_VAR"))
        }

    @Test
    fun `directional concentration above its own limit is PORTFOLIO_DIRECTIONAL`() =
        runBlocking {
            riskConfig.maxDirectionalConcentrationPercent = 10.0
            riskConfig.portfolioConcentrationWarnPercent = 100.0
            riskConfig.portfolioStressSigma = 0.0
            stubVols("A" to 0.1)
            stubCorrelations(listOf("A"), 0.0)

            val report = engine.evaluate(request())

            assertFalse(report.allowed)
            assertTrue(report.reasons.contains("PORTFOLIO_DIRECTIONAL"))
        }

    @Test
    fun `missing volatility falls back to the maximum available one`() =
        runBlocking {
            stubVols("A" to 1.0, "B" to null)
            Mockito.`when`(candleCache.calculateRealizedVolatility("B", "MINUTE_10", 20)).thenReturn(null)
            stubCorrelations(listOf("A", "B"), 0.0)

            val report =
                engine.evaluate(
                    request(
                        candidate = "B",
                        open = listOf(position("A", BigDecimal("100"))),
                    ),
                )

            // оба тикера получают σ = 1% → σp = sqrt(2 * 0.25 * 0.0001) = 0.7071%
            assertEquals(0.7071, report.portfolioDailyVolPercent.toDouble(), 0.0005)
            assertEquals(PortfolioDataQuality.INSUFFICIENT, report.volatilityDataQuality)
            assertEquals(0.0, report.dataQualityScale.toDouble(), 1e-9)
            assertFalse(report.allowed)
        }

    @Test
    fun `effective positions below the block threshold scale to the floor`() =
        runBlocking {
            riskConfig.minEffectivePositions = 1.5
            riskConfig.portfolioEffectiveWarnPositions = 2.0
            riskConfig.portfolioVarWarnPercent = 50.0
            riskConfig.maxPortfolioVarPercent = 100.0
            riskConfig.portfolioStressSigma = 0.0
            stubVols("A" to 1.0, "B" to 1.0, "C" to 1.0)
            stubCorrelations(listOf("A", "B", "C"), 1.0)

            val report =
                engine.evaluate(
                    request(
                        candidate = "C",
                        open = listOf(position("A", BigDecimal("100")), position("B", BigDecimal("100"))),
                    ),
                )

            assertEquals(0.25, report.scaleDownFactor.toDouble(), 1e-9)
            assertTrue(report.reasons.contains("PORTFOLIO_CONCENTRATION"))
        }

    @Test
    fun `effective positions between warn and block interpolate the scale`() =
        runBlocking {
            riskConfig.minEffectivePositions = 1.5
            riskConfig.portfolioEffectiveWarnPositions = 3.0
            riskConfig.portfolioVarWarnPercent = 50.0
            riskConfig.maxPortfolioVarPercent = 100.0
            riskConfig.portfolioStressSigma = 0.0
            stubVols("A" to 1.0, "B" to 1.0, "C" to 1.0)
            stubCorrelations(listOf("A", "B", "C"), 0.2)

            val report =
                engine.evaluate(
                    request(
                        candidate = "C",
                        open = listOf(position("A", BigDecimal("100")), position("B", BigDecimal("100"))),
                    ),
                )

            // eff = 1 / (3*(1/9) + 6*0.2/9) = 2.1429 → линейная интерполяция
            assertTrue(report.allowed)
            assertTrue(report.scaleDownFactor.toDouble() < 1.0)
            assertTrue(report.scaleDownFactor.toDouble() > 0.25)
            assertEquals(0.5714, report.scaleDownFactor.toDouble(), 0.002)
        }

    @Test
    fun `degenerate effective-position thresholds disable that scale factor`() =
        runBlocking {
            riskConfig.minEffectivePositions = 2.0
            riskConfig.portfolioEffectiveWarnPositions = 1.0
            riskConfig.portfolioVarWarnPercent = 50.0
            riskConfig.maxPortfolioVarPercent = 100.0
            riskConfig.portfolioStressSigma = 0.0
            stubVols("A" to 1.0, "B" to 1.0)
            stubCorrelations(listOf("A", "B"), 0.0)

            val report =
                engine.evaluate(
                    request(
                        candidate = "B",
                        open = listOf(position("A", BigDecimal("100"))),
                    ),
                )

            assertEquals(1.0, report.scaleDownFactor.toDouble(), 1e-9)
        }

    @Test
    fun `non positive prices are skipped from the historical series`() =
        runBlocking {
            stubVols("A" to 1.0)
            stubCorrelations(listOf("A"), 0.0)
            // 25 баров, но 10 цен <= 0 → валидных доходностей < minSamples (20)
            stubCandles("A", List(10) { 0.0 } + List(15) { 100.0 + it })

            val report = engine.evaluate(request())

            assertEquals(0.0, report.historicalVar95Rub.toDouble(), 1e-9)
            assertEquals(0.0, report.cvar95Rub.toDouble(), 1e-9)
            assertTrue(report.stressLossRub > BigDecimal.ZERO)
        }

    @Test
    fun `integer percentile index uses the exact sorted value`() =
        runBlocking {
            stubVols("A" to 1.0)
            stubCorrelations(listOf("A"), 0.0)
            // 22 закрытия → 21 доходность: два падения, затем 19 ростов.
            // (21-1)*0.05 = 1.0 → lower == upper, квантиль = второе худшее падение.
            val closes = mutableListOf(100.0, 95.0, 93.1)
            repeat(19) { closes += closes.last() * 1.01 }
            stubCandles("A", closes)

            val report = engine.evaluate(request())

            assertEquals(2.0, report.historicalVar95Rub.toDouble(), 0.1)
            assertTrue(report.cvar95Rub > report.historicalVar95Rub)
        }

    @Test
    fun `single ticker portfolio has no pair correlation`() =
        runBlocking {
            stubVols("A" to 1.0)
            stubCorrelations(listOf("A"), 0.0)

            val report = engine.evaluate(request())

            assertEquals(0.0, report.maxPairCorrelation, 1e-9)
        }

    @Test
    fun `known instrument notional uses lot size`() =
        runBlocking {
            stubVols("SBER" to 1.0, "A" to 1.0)
            stubCorrelations(listOf("SBER", "A"), 0.0)

            val report =
                engine.evaluate(
                    request(
                        candidate = "A",
                        notional = "1000",
                        open = listOf(position("SBER", BigDecimal("100"))),
                        aum = "1000000",
                    ),
                )

            // SBER lotSize=10 → notional 1000; веса 0.5/0.5, обе σ=1%
            assertEquals(0.7071, report.portfolioDailyVolPercent.toDouble(), 0.0005)
            assertEquals(2.0, report.effectivePositions.toDouble(), 0.01)
            assertTrue(report.allowed)
        }

    @Test
    fun `duplicate ticker position and candidate are grouped into one leg`() =
        runBlocking {
            riskConfig.minEffectivePositions = 1.5
            stubVols("A" to 1.0)
            stubCorrelations(listOf("A", "A"), 1.0, PortfolioDataQuality.KNOWN)

            val report =
                engine.evaluate(
                    request(
                        candidate = "A",
                        open = listOf(position("A", BigDecimal("100"))),
                    ),
                )

            assertFalse(report.allowed)
            assertTrue(report.reasons.contains("PORTFOLIO_CONCENTRATION"))
        }

    @Test
    fun `estimated correlation quality is treated as full data`() =
        runBlocking {
            riskConfig.portfolioStressSigma = 0.0
            stubVols("A" to 1.0)
            stubCorrelations(listOf("A"), 0.0, PortfolioDataQuality.ESTIMATED)

            val report = engine.evaluate(request())

            assertEquals(PortfolioDataQuality.ESTIMATED, report.correlationDataQuality)
            assertEquals(1.0, report.dataQualityScale.toDouble(), 1e-9)
        }
}
