package com.trading.bot.backtest.pairs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.math.exp
import kotlin.random.Random

class PairsBacktestEngineTest {
    private val start = LocalDateTime.of(2025, 1, 1, 0, 0)

    private fun legSpec(
        ticker: String,
        commission: Double = 0.0,
        slippage: Double = 0.0,
    ) = PairsBacktestEngine.LegSpec(
        ticker = ticker,
        lotSize = 1000,
        commissionPerContractSide = commission,
        slippageBps = slippage,
        fundingPerContractClearing = 0.0,
    )

    private fun barsFromLogs(
        la: DoubleArray,
        lb: DoubleArray,
    ): List<PairsBacktestEngine.Bar> =
        la.indices.map { i ->
            val a = exp(la[i])
            val b = exp(lb[i])
            PairsBacktestEngine.Bar(start.plusMinutes(10L * i), a, a, b, b)
        }

    /** Спред с периодическими импульсами вверх +0.1 и возвратом к нулю. */
    private fun pulseSpreadTrades(commission: Double): Pair<PairsBacktestEngine.Result, Double> {
        val n = 400
        val rnd = Random(7)
        val lb = DoubleArray(n)
        lb[0] = 2.4
        for (i in 1 until n) lb[i] = lb[i - 1] + (rnd.nextDouble() - 0.5) * 0.002
        val spread =
            DoubleArray(n) { i ->
                val t = i % 40
                when {
                    t in 0..5 -> 0.1 * (t / 5.0)
                    t in 6..15 -> 0.1 * ((15 - t) / 10.0)
                    else -> 0.0
                }
            }
        val la = DoubleArray(n) { lb[it] + spread[it] }
        val result =
            PairsBacktestEngine.run(
                bars = barsFromLogs(la, lb),
                specA = legSpec("CNYRUBF", commission = commission),
                specB = legSpec("USDRUBF", commission = commission),
                params =
                    PairsBacktestEngine.Params(
                        initialCapital = 100_000.0,
                        lookbackBars = 20,
                        entryZ = 2.0,
                        exitZ = 0.5,
                        stopZ = 6.0,
                        capitalSlice = 0.5,
                    ),
            )
        return result to result.trades.sumOf { it.netPnl }
    }

    @Test
    fun `mean reverting spread produces profitable trades without costs`() {
        val (result, total) = pulseSpreadTrades(commission = 0.0)
        assertTrue(result.trades.isNotEmpty(), "expected trades on pulsing spread")
        assertTrue(result.trades.any { it.side == "SHORT_SPREAD" }, "upward pulses → short spread")
        assertTrue(total > 0.0, "mean reversion should be profitable without costs, got $total")
    }

    @Test
    fun `commissions reduce net pnl`() {
        val (_, noCost) = pulseSpreadTrades(commission = 0.0)
        val (_, withCost) = pulseSpreadTrades(commission = 200.0)
        assertTrue(withCost < noCost, "commissions must reduce net P&L: $withCost vs $noCost")
    }

    @Test
    fun `insufficient history yields empty result`() {
        val n = 25
        val la = DoubleArray(n) { 2.4 + 0.001 * it }
        val lb = DoubleArray(n) { 2.4 }
        val result =
            PairsBacktestEngine.run(
                bars = barsFromLogs(la, lb),
                specA = legSpec("CNYRUBF"),
                specB = legSpec("USDRUBF"),
                params = PairsBacktestEngine.Params(lookbackBars = 60),
            )
        assertTrue(result.trades.isEmpty())
        assertTrue(result.equityCurve.isEmpty())
    }

    @Test
    fun `persistent divergence hits stop`() {
        val n = 200
        val rnd = Random(11)
        val lb = DoubleArray(n)
        lb[0] = 2.4
        for (i in 1 until n) lb[i] = lb[i - 1] + (rnd.nextDouble() - 0.5) * 0.004
        val spread =
            DoubleArray(n) { i ->
                if (i < 50) 0.0 else 0.3
            }
        val la = DoubleArray(n) { lb[it] + spread[it] }
        val result =
            PairsBacktestEngine.run(
                bars = barsFromLogs(la, lb),
                specA = legSpec("CNYRUBF"),
                specB = legSpec("USDRUBF"),
                params =
                    PairsBacktestEngine.Params(
                        lookbackBars = 20,
                        entryZ = 2.0,
                        exitZ = 0.5,
                        stopZ = 2.5,
                        maxHoldBars = 5,
                    ),
            )
        assertTrue(result.trades.isNotEmpty(), "persistent divergence should open a trade")
        assertTrue(
            result.trades.any { it.closeReason == "STOP" || it.closeReason == "MAX_HOLD" },
            "expected a hard exit, got ${result.trades.map { it.closeReason }}",
        )
    }

    @Test
    fun `correlation detects coupled legs`() {
        val n = 300
        val rnd = Random(42)
        val lb = DoubleArray(n)
        val la = DoubleArray(n)
        lb[0] = 2.4
        la[0] = 2.4
        for (i in 1 until n) {
            val shock = (rnd.nextDouble() - 0.5) * 0.02
            lb[i] = lb[i - 1] + shock
            la[i] = lb[i] + (rnd.nextDouble() - 0.5) * 0.002
        }
        val result =
            PairsBacktestEngine.run(
                bars = barsFromLogs(la, lb),
                specA = legSpec("CNYRUBF"),
                specB = legSpec("USDRUBF"),
                params = PairsBacktestEngine.Params(lookbackBars = 20),
            )
        assertTrue(result.spreadReturnCorrelation > 0.7, "coupled legs must be highly correlated")
        assertEquals(300, result.bars)
    }
}
