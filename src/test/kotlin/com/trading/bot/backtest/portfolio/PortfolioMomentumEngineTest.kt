package com.trading.bot.backtest.portfolio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class PortfolioMomentumEngineTest {
    private val start = LocalDateTime.of(2025, 1, 1, 0, 0)

    private fun spec(
        ticker: String,
        commission: Double = 0.0,
    ) = PortfolioMomentumEngine.LegSpec(
        ticker = ticker,
        lotSize = 1,
        commissionPerContractSide = commission,
        slippageBps = 0.0,
        fundingPerContractClearing = 0.0,
    )

    /** UP растёт, FLAT стоит, DOWN падает — детерминированное кросс-сечение. */
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

    @Test
    fun `momentum book is long the winner and short the loser`() {
        val result =
            PortfolioMomentumEngine.run(
                bars = trendingBars(400),
                specs = listOf(spec("UP"), spec("FLAT"), spec("DOWN")),
                params = params(),
            )
        assertTrue(result.trades.isNotEmpty(), "expected rebalance trades")
        val longTickers =
            result.trades
                .filter { it.side == "LONG" }
                .map { it.ticker }
                .toSet()
        val shortTickers =
            result.trades
                .filter { it.side == "SHORT" }
                .map { it.ticker }
                .toSet()
        assertEquals(setOf("UP"), longTickers)
        assertEquals(setOf("DOWN"), shortTickers)
    }

    @Test
    fun `reversal book is long the loser and short the winner`() {
        val result =
            PortfolioMomentumEngine.run(
                bars = trendingBars(400),
                specs = listOf(spec("UP"), spec("FLAT"), spec("DOWN")),
                params = params().copy(reversalEnabled = true),
            )
        assertTrue(result.trades.isNotEmpty(), "expected rebalance trades")
        val longTickers =
            result.trades
                .filter { it.side == "LONG" }
                .map { it.ticker }
                .toSet()
        val shortTickers =
            result.trades
                .filter { it.side == "SHORT" }
                .map { it.ticker }
                .toSet()
        assertEquals(setOf("DOWN"), longTickers)
        assertEquals(setOf("UP"), shortTickers)
    }

    /** Плоское сечение (изолируем carry от momentum). */
    private fun flatBars(n: Int): List<PortfolioMomentumEngine.Bar> =
        (0 until n).map { i ->
            val p = exp(2.4)
            PortfolioMomentumEngine.Bar(
                time = start.plusMinutes(10L * i),
                opens = doubleArrayOf(p, p, p),
                closes = doubleArrayOf(p, p, p),
            )
        }

    @Test
    fun `carry-only book longs the payer of funding and shorts the receiver`() {
        // carryYield = -funding/notional: отрицательный funding (лонг получает) → выше score.
        val day = LocalDate.of(2024, 12, 31)
        val funding =
            listOf(
                mapOf(day to -5.0),
                mapOf(day to 0.0),
                mapOf(day to 5.0),
            )
        val result =
            PortfolioMomentumEngine.run(
                bars = flatBars(200),
                specs = listOf(spec("NEG"), spec("ZERO"), spec("POS")),
                params = params().copy(momentumWeight = 0.0, carryWeight = 1.0),
                funding = funding,
            )
        assertTrue(result.trades.isNotEmpty(), "expected rebalance trades")
        val longTickers =
            result.trades
                .filter { it.side == "LONG" }
                .map { it.ticker }
                .toSet()
        val shortTickers =
            result.trades
                .filter { it.side == "SHORT" }
                .map { it.ticker }
                .toSet()
        assertEquals(setOf("NEG"), longTickers)
        assertEquals(setOf("POS"), shortTickers)
    }

    @Test
    fun `momentum book is profitable without costs`() {
        val result =
            PortfolioMomentumEngine.run(
                bars = trendingBars(400),
                specs = listOf(spec("UP"), spec("FLAT"), spec("DOWN")),
                params = params(),
            )
        assertTrue(result.trades.sumOf { it.netPnl } > 0.0, "trending cross-section must profit")
    }

    @Test
    fun `commissions reduce net pnl`() {
        val cheap =
            PortfolioMomentumEngine.run(
                bars = trendingBars(400),
                specs = listOf(spec("UP"), spec("FLAT"), spec("DOWN")),
                params = params(),
            )
        val costly =
            PortfolioMomentumEngine.run(
                bars = trendingBars(400),
                specs = listOf(spec("UP", commission = 50.0), spec("FLAT", 50.0), spec("DOWN", 50.0)),
                params = params(),
            )
        assertTrue(
            costly.trades.sumOf { it.netPnl } < cheap.trades.sumOf { it.netPnl },
            "commissions must reduce net P&L",
        )
    }

    /** LOWVOL — плавный рост, HIGHVOL — падение с большой амплитудой, MID — между ними. */
    private fun volatileBars(n: Int): List<PortfolioMomentumEngine.Bar> =
        (0 until n).map { i ->
            val low = exp(2.4 + 0.0010 * i + 0.0010 * sin(i * 0.9))
            val mid = exp(2.4)
            val high = exp(2.4 - 0.0030 * i + 0.010 * sin(i * 0.9))
            PortfolioMomentumEngine.Bar(
                time = start.plusMinutes(10L * i),
                opens = doubleArrayOf(low, mid, high),
                closes = doubleArrayOf(low, mid, high),
            )
        }

    private data class LongShortWeights(
        val long: Double,
        val short: Double,
    )

    private fun weights(result: PortfolioMomentumEngine.Result): LongShortWeights {
        val longNotional = result.trades.filter { it.side == "LONG" }.sumOf { it.qty * it.entryPrice }
        val shortNotional = result.trades.filter { it.side == "SHORT" }.sumOf { it.qty * it.entryPrice }
        val total = longNotional + shortNotional
        return LongShortWeights(longNotional / total, shortNotional / total)
    }

    @Test
    fun `vol scaling overweights the lower volatility leg`() {
        val bars = volatileBars(110)
        val specs = listOf(spec("LOWVOL"), spec("MID"), spec("HIGHVOL"))
        val equal = PortfolioMomentumEngine.run(bars, specs, params())
        val scaled =
            PortfolioMomentumEngine.run(
                bars,
                specs,
                params().copy(volScalingEnabled = true, volLookbackBars = 40),
            )
        val wEqual = weights(equal)
        val wScaled = weights(scaled)
        assertTrue(abs(wEqual.long - wEqual.short) < 0.05, "equal weights expected: $wEqual")
        assertTrue(wScaled.long > wScaled.short + 0.2, "low-vol long must be overweighted: $wScaled")
    }

    @Test
    fun `vol scaling falls back to equal weights when volatility window unavailable`() {
        val bars = volatileBars(110)
        val specs = listOf(spec("LOWVOL"), spec("MID"), spec("HIGHVOL"))
        val scaled =
            PortfolioMomentumEngine.run(
                bars,
                specs,
                params().copy(volScalingEnabled = true, volLookbackBars = 200),
            )
        val w = weights(scaled)
        assertTrue(abs(w.long - 0.5) < 0.05, "fallback to equal weights expected: $w")
    }

    private fun exposure(result: PortfolioMomentumEngine.Result): Double = result.trades.sumOf { it.qty * it.entryPrice }

    @Test
    fun `portfolio vol targeting keeps base exposure when target above realized vol`() {
        val bars = volatileBars(400)
        val specs = listOf(spec("LOWVOL"), spec("MID"), spec("HIGHVOL"))
        val base = PortfolioMomentumEngine.run(bars, specs, params())
        val capped =
            PortfolioMomentumEngine.run(
                bars,
                specs,
                params().copy(volTargetEnabled = true, volTargetAnnualized = 100.0, volLookbackBars = 40),
            )
        assertEquals(exposure(base), exposure(capped), 1e-6, "high target must not lever above base")
    }

    @Test
    fun `portfolio vol targeting reduces exposure when target below realized vol`() {
        val bars = volatileBars(400)
        val specs = listOf(spec("LOWVOL"), spec("MID"), spec("HIGHVOL"))
        val base = PortfolioMomentumEngine.run(bars, specs, params())
        val reduced =
            PortfolioMomentumEngine.run(
                bars,
                specs,
                params().copy(volTargetEnabled = true, volTargetAnnualized = 0.01, volLookbackBars = 40),
            )
        assertTrue(exposure(reduced) < exposure(base) * 0.5, "low target must de-risk the book")
    }

    @Test
    fun `portfolio vol targeting falls back to base exposure without volatility history`() {
        val bars = volatileBars(400)
        val specs = listOf(spec("LOWVOL"), spec("MID"), spec("HIGHVOL"))
        val base = PortfolioMomentumEngine.run(bars, specs, params())
        val noHistory =
            PortfolioMomentumEngine.run(
                bars,
                specs,
                params().copy(volTargetEnabled = true, volTargetAnnualized = 0.01, volLookbackBars = 100_000),
            )
        assertEquals(exposure(base), exposure(noHistory), 1e-6, "missing volatility history must fall back to base")
    }

    @Test
    fun `insufficient history yields empty result`() {
        val result =
            PortfolioMomentumEngine.run(
                bars = trendingBars(50),
                specs = listOf(spec("UP"), spec("FLAT"), spec("DOWN")),
                params = params(),
            )
        assertTrue(result.trades.isEmpty())
        assertTrue(result.equityCurve.isEmpty())
        assertEquals(0, result.rebalances)
    }

    @Test
    fun `too small universe yields empty result`() {
        val result =
            PortfolioMomentumEngine.run(
                bars = trendingBars(400),
                specs = listOf(spec("UP"), spec("FLAT")),
                params = params(),
            )
        assertTrue(result.trades.isEmpty())
    }
}
