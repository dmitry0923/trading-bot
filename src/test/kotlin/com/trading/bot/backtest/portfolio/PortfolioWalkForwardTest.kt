package com.trading.bot.backtest.portfolio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.math.exp

class PortfolioWalkForwardTest {
    private val start = LocalDateTime.of(2025, 1, 1, 0, 0)

    private fun spec(ticker: String) =
        PortfolioMomentumEngine.LegSpec(
            ticker = ticker,
            lotSize = 1,
            commissionPerContractSide = 0.0,
            slippageBps = 0.0,
            fundingPerContractClearing = 0.0,
        )

    private fun trendingBars(n: Int): List<PortfolioMomentumEngine.Bar> =
        (0 until n).map { i ->
            val up = exp(2.4 + 0.0008 * i)
            val flat = exp(2.4)
            val down = exp(2.4 - 0.0008 * i)
            PortfolioMomentumEngine.Bar(
                time = start.plusMinutes(10L * i),
                opens = doubleArrayOf(up, flat, down),
                closes = doubleArrayOf(up, flat, down),
            )
        }

    private fun params() =
        PortfolioMomentumEngine.Params(
            initialCapital = 100_000.0,
            lookbackBars = 60,
            rebalanceBars = 40,
            capitalSlice = 0.5,
            minUniverse = 3,
        )

    private val specs = listOf(spec("UP"), spec("FLAT"), spec("DOWN"))

    @Test
    fun `walk forward splits folds and aggregates trades`() {
        val result =
            PortfolioWalkForward.run(
                bars = trendingBars(1200),
                specs = specs,
                params = params(),
                folds = 6,
            )
        assertEquals(6, result.folds.size, "expected 6 folds")
        assertTrue(result.aggregate.totalTrades > 0, "aggregate must contain fold trades")
        assertTrue(result.aggregate.totalReturn > 0.0, "momentum book profitable without costs")
        assertEquals(1.0, result.consistency, 0.001, "all folds should be positive")
    }

    @Test
    fun `insufficient bars yields inconclusive`() {
        val result =
            PortfolioWalkForward.run(
                bars = trendingBars(100),
                specs = specs,
                params = params(),
                folds = 6,
            )
        assertTrue(result.folds.isEmpty(), "no folds when history too short")
        assertTrue(result.verdict.contains("INCONCLUSIVE"), "verdict was ${result.verdict}")
    }
}
