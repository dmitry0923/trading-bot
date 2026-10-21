package com.trading.bot.backtest.pairs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.math.exp

class PairsWalkForwardTest {
    private val start = LocalDateTime.of(2025, 1, 1, 0, 0)

    private fun legSpec(ticker: String) =
        PairsBacktestEngine.LegSpec(
            ticker = ticker,
            lotSize = 1000,
            commissionPerContractSide = 0.0,
            slippageBps = 0.0,
            fundingPerContractClearing = 0.0,
        )

    /**
     * Детерминированный mean-reverting спред: каждые 40 баров 3-барный спайк +0.5
     * и возврат к нулю. При lookback 60 спайк короток относительно окна → z >> 2,
     * вход на пике, выход при возврате. Без издержек каждый фолд прибылен.
     */
    private fun spikyBars(n: Int): List<PairsBacktestEngine.Bar> {
        val lb = DoubleArray(n) { 2.4 + 0.0005 * it }
        val spread = DoubleArray(n) { i -> if (i % 40 < 3) 0.5 else 0.0 }
        val la = DoubleArray(n) { lb[it] + spread[it] }
        return la.indices.map { i ->
            val a = exp(la[i])
            val b = exp(lb[i])
            PairsBacktestEngine.Bar(start.plusMinutes(10L * i), a, a, b, b)
        }
    }

    private fun params() = PairsBacktestEngine.Params(lookbackBars = 60, stopZ = 6.0, capitalSlice = 0.5)

    @Test
    fun `walk forward splits folds and aggregates trades`() {
        val result =
            PairsWalkForward.run(
                bars = spikyBars(1200),
                specA = legSpec("CNYRUBF"),
                specB = legSpec("USDRUBF"),
                params = params(),
                folds = 6,
            )
        assertEquals(6, result.folds.size, "expected 6 folds")
        assertTrue(result.aggregate.totalTrades > 0, "aggregate must contain fold trades")
        assertTrue(result.aggregate.totalReturn > 0.0, "mean reversion profitable without costs")
        assertEquals(1.0, result.consistency, 0.001, "all folds should be positive")
    }

    @Test
    fun `fold returns are consistent with signs across folds`() {
        val result =
            PairsWalkForward.run(
                bars = spikyBars(1200),
                specA = legSpec("CNYRUBF"),
                specB = legSpec("USDRUBF"),
                params = params(),
                folds = 6,
            )
        val positive = result.folds.count { it.result.totalReturn > 0 }
        assertEquals(result.folds.size, positive, "each fold should be profitable")
    }

    @Test
    fun `insufficient bars yields inconclusive`() {
        val result =
            PairsWalkForward.run(
                bars = spikyBars(100),
                specA = legSpec("CNYRUBF"),
                specB = legSpec("USDRUBF"),
                params = PairsBacktestEngine.Params(lookbackBars = 60),
                folds = 6,
            )
        assertTrue(result.folds.isEmpty(), "no folds when history too short")
        assertTrue(result.verdict.contains("INCONCLUSIVE"), "verdict was ${result.verdict}")
    }
}
