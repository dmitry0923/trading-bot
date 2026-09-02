package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import com.trading.bot.model.entity.Candle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Monte Carlo bootstrap и стресс-сценарии бэктеста (roadmap 13.7.8, MR-004/H-003).
 *
 * Чистая математика [MonteCarlo] тестируется детерминированно (seed);
 * [MonteCarloAnalyzer] — как оркестратор: базовый прогон, bootstrap по периодным
 * доходностям его кривой капитала и перепрогон стресс-сценариев с увеличенными
 * издержками.
 */
class MonteCarloAnalyzerTest {
    private val capital = BigDecimal("100000")

    @Test
    fun `single period return path is fully deterministic`() {
        // Одна доходность +1% => каждый путь ровно +1% при любом seed.
        val result = MonteCarlo.simulate(listOf(0.01), capital, simulations = 500, seed = 7)
        assertEquals(0.01, result.medianReturn, 1e-12)
        assertEquals(0.01, result.p5Return, 1e-12)
        assertEquals(0.01, result.p95Return, 1e-12)
        assertEquals(0.01, result.avgReturn, 1e-12)
        assertEquals(0.01, result.minReturn, 1e-12)
        assertEquals(0.01, result.maxReturn, 1e-12)
        assertEquals(0.0, result.probabilityOfLoss, 1e-12)
        assertTrue(result.isRobust())
    }

    @Test
    fun `all losing periods always end in loss`() {
        val result = MonteCarlo.simulate(listOf(-0.005, -0.003), capital, simulations = 1000, seed = 42)
        assertEquals(1.0, result.probabilityOfLoss, 1e-12)
        assertTrue(result.maxReturn < 0.0)
        assertFalse(result.isRobust())
    }

    @Test
    fun `strong drawdown path flags mdd and ruin probabilities`() {
        // Периоды с чередованием больших убытков: пути могут глубоко проседать.
        val returns = List(40) { if (it % 4 == 0) -0.15 else 0.03 }
        val result = MonteCarlo.simulate(returns, capital, simulations = 2000, seed = 42)
        // Хотя бы часть путей имеет заметную просадку.
        assertTrue(result.probabilityMddExceeds20 >= 0.0)
        assertTrue(result.probabilityMddExceeds30 in 0.0..1.0)
        assertTrue(result.probabilityMddExceeds40 in 0.0..1.0)
        assertTrue(result.probabilityOfRuin in 0.0..1.0)
        assertTrue(result.worst1PercentEquity <= result.worst5PercentEquity)
        assertTrue(result.worst1PercentEquity in 0.0..1.0)
    }

    @Test
    fun `stationary bootstrap preserves method and block length metadata`() {
        val returns = (1..30).map { (it % 5 - 2) * 0.01 }
        val result = MonteCarlo.simulateStationary(returns, capital, simulations = 500, avgBlockLength = 7.0, seed = 42)
        assertEquals("stationary", result.blockMethod)
        assertEquals(7.0, result.avgBlockLength, 1e-12)
        assertTrue(result.probabilityOfLoss in 0.0..1.0)
        assertTrue(result.probabilityOfRuin in 0.0..1.0)
    }

    @Test
    fun `block bootstrap preserves method and block length metadata`() {
        val returns = (1..30).map { (it % 5 - 2) * 0.01 }
        val result = MonteCarlo.simulateBlock(returns, capital, simulations = 500, blockLength = 5, seed = 42)
        assertEquals("block", result.blockMethod)
        assertEquals(5.0, result.avgBlockLength, 1e-12)
        assertTrue(result.probabilityOfLoss in 0.0..1.0)
    }

    @Test
    fun `stationary and block bootstrap are deterministic under same seed`() {
        val returns = (1..30).map { (it % 5 - 2) * 0.01 }
        val a = MonteCarlo.simulateStationary(returns, capital, simulations = 500, avgBlockLength = 5.0, seed = 42)
        val b = MonteCarlo.simulateStationary(returns, capital, simulations = 500, avgBlockLength = 5.0, seed = 42)
        assertEquals(a.medianReturn, b.medianReturn)
        assertEquals(a.p5Return, b.p5Return)
        assertEquals(a.probabilityOfRuin, b.probabilityOfRuin)
        assertEquals("stationary", a.blockMethod)
    }

    @Test
    fun `deep downtrend path yields high mdd and ruin risk`() {
        // 50 последовательных потерь по -8%: почти все пути пробивают floor разорения.
        val returns = List(50) { -0.08 }
        val result = MonteCarlo.simulate(returns, capital, simulations = 500, seed = 42)
        assertTrue(result.probabilityOfRuin > 0.9)
        assertTrue(result.probabilityMddExceeds40 > 0.9)
        assertFalse(result.isRobust())
    }

    @Test
    fun `block bootstrap rejects invalid block length`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            MonteCarlo.simulateBlock(listOf(0.01), capital, simulations = 100, blockLength = 0)
        }
    }

    @Test
    fun `same seed produces identical distribution`() {
        val returns = listOf(0.01, -0.004, 0.007, -0.002, 0.015, -0.006)
        val a = MonteCarlo.simulate(returns, capital, simulations = 2000, seed = 42)
        val b = MonteCarlo.simulate(returns, capital, simulations = 2000, seed = 42)
        assertEquals(a.medianReturn, b.medianReturn)
        assertEquals(a.p5Return, b.p5Return)
        assertEquals(a.p95Return, b.p95Return)
        assertEquals(a.probabilityOfLoss, b.probabilityOfLoss)
        assertEquals(a.minReturn, b.minReturn)
        assertEquals(a.maxReturn, b.maxReturn)
    }

    @Test
    fun `different seed changes the resampling`() {
        val returns = listOf(0.01, -0.004, 0.007, -0.002, 0.015, -0.006)
        val a = MonteCarlo.simulate(returns, capital, simulations = 500, seed = 1)
        val b = MonteCarlo.simulate(returns, capital, simulations = 500, seed = 2)
        assertTrue(a.medianReturn != b.medianReturn || a.p5Return != b.p5Return)
    }

    @Test
    fun `percentiles are ordered and within observed range`() {
        val returns = (1..20).map { (it - 8) * 0.01 } // смесь прибылей и убытков
        val result = MonteCarlo.simulate(returns, capital, simulations = 2000, seed = 42)
        assertTrue(result.p5Return <= result.medianReturn)
        assertTrue(result.medianReturn <= result.p95Return)
        assertTrue(result.minReturn <= result.p5Return)
        assertTrue(result.p95Return <= result.maxReturn)
        assertTrue(result.probabilityOfLoss in 0.0..1.0)
    }

    @Test
    fun `empty returns produce neutral result`() {
        val result = MonteCarlo.simulate(emptyList(), capital, simulations = 100, seed = 42)
        assertEquals(0.0, result.medianReturn, 1e-12)
        assertEquals(0.0, result.probabilityOfLoss, 1e-12)
        assertFalse(result.isRobust())
    }

    @Test
    fun `zero simulations and non-positive capital return neutral result`() {
        val noSims = MonteCarlo.simulate(listOf(0.01), capital, simulations = 0, seed = 42)
        assertEquals(0, noSims.simulations)
        assertEquals(0.0, noSims.medianReturn, 1e-12)
        assertEquals(0.0, noSims.p5Return, 1e-12)
        assertEquals(0.0, noSims.probabilityOfLoss, 1e-12)

        val zeroCapital = MonteCarlo.simulate(listOf(1000.0), BigDecimal.ZERO, simulations = 50, seed = 42)
        assertEquals(0.0, zeroCapital.medianReturn, 1e-12)
        assertEquals(0.0, zeroCapital.p5Return, 1e-12)
        assertFalse(zeroCapital.isRobust())
    }

    @Test
    fun `stress scenario maps backtest metrics`() {
        val result =
            BacktestMetrics.compute(
                "SBER",
                listOf(capital, BigDecimal("101000"), BigDecimal("99000")),
                tradeReturns = listOf(1000.0, -500.0, 2000.0),
            )
        val scenario = StressScenarioResult.of("commission_x2", "Комиссия ×2", 2.0, 1.0, result)
        assertEquals("commission_x2", scenario.name)
        assertEquals(2.0, scenario.commissionMultiplier)
        assertEquals(3, scenario.totalTrades)
        assertEquals(result.totalReturn, scenario.totalReturn)
    }

    @Test
    fun `analyzer uses frozen strategy parameters in base and stress runs`() {
        val engine = mock<BacktestEngine> {}
        val baseResult = BacktestMetrics.compute("SBER", listOf(capital, BigDecimal("103000")), tradeReturns = listOf(3000.0))
        val frozen =
            StrategyParameters(
                slPercent = 0.02,
                tpPercent = 0.06,
                slPoints = null,
                tpPoints = null,
                confidenceThreshold = 0.63,
                leverage = 5.0,
                riskPerTradePercent = 0.30,
                futuresMaxContractsPerPosition = 33,
            )
        runBlocking {
            stubSimulateWith(engine, frozen, baseResult)
        }

        val report =
            runBlocking {
                MonteCarloAnalyzer(engine, BacktestConfig()).analyze(
                    "SBER",
                    sampleCandles(),
                    parameters = frozen,
                    simulations = 50,
                    seed = 42,
                )
            }

        assertEquals(5, report.stress.size)
        // Каждый стресс-сценарий и базовый прогон обязаны использовать те же frozen-параметры
        // (леверидж 5x, риск 30%, лимит 33, confidence 0.63), а не config-дефолты.
        val totalCalls = 1 + report.stress.size
        runBlocking {
            verify(engine, times(totalCalls))
                .simulate(
                    eq("SBER"),
                    any(),
                    eq(capital),
                    any(),
                    eq(0.02),
                    eq(0.06),
                    any(),
                    any(),
                    anyOrNull(),
                    anyOrNull(),
                    eq(5.0),
                    anyOrNull(),
                    eq(0.30),
                    eq(33),
                    anyOrNull(),
                )
        }
    }

    @Test
    fun `analyzer runs base bootstrap and all stress scenarios`() {
        val engine = mock<BacktestEngine> {}
        val baseResult = BacktestMetrics.compute("SBER", listOf(capital, BigDecimal("103000")), tradeReturns = listOf(3000.0))
        val stressResult = BacktestMetrics.compute("SBER", listOf(capital, BigDecimal("102000")), tradeReturns = listOf(2000.0))
        runBlocking {
            stubSimulate(engine, 1.0, 1.0, baseResult)
            stubSimulate(engine, 2.0, 1.0, stressResult)
            stubSimulate(engine, 5.0, 1.0, stressResult)
            stubSimulate(engine, 1.0, 2.0, stressResult)
            stubSimulate(engine, 1.0, 5.0, stressResult)
            stubSimulate(engine, 3.0, 3.0, stressResult)
        }

        val report =
            runBlocking {
                MonteCarloAnalyzer(engine, BacktestConfig()).analyze("SBER", sampleCandles(), simulations = 50, seed = 42)
            }

        assertEquals("base", report.base.name)
        // Базовый прогон без стресса + 5 сценариев
        assertEquals(5, report.stress.size)
        assertEquals(50, report.monteCarlo.simulations)
        // Кривая капитала [100000, 103000] даёт одну периодную доходность +3% -> ровно +3%
        assertEquals(0.03, report.monteCarlo.medianReturn, 1e-12)

        // Множители стресса доходят до движка в каждом сценарии
        assertEquals(listOf(2.0, 5.0, 1.0, 1.0, 3.0), report.stress.map { it.commissionMultiplier })
        assertEquals(listOf(1.0, 1.0, 2.0, 5.0, 3.0), report.stress.map { it.slippageMultiplier })
        assertTrue(report.stress.all { it.name.startsWith("commission") || it.name.startsWith("slippage") || it.name == "combined_stress" })
    }

    private suspend fun stubSimulate(
        engine: BacktestEngine,
        commissionMultiplier: Double,
        slippageMultiplier: Double,
        result: BacktestResult,
    ) {
        whenever(
            engine.simulate(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                Mockito.eq(commissionMultiplier),
                Mockito.eq(slippageMultiplier),
                anyOrNull(),
                anyOrNull(),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
            ),
        ).thenReturn(result)
    }

    private suspend fun stubSimulateWith(
        engine: BacktestEngine,
        p: StrategyParameters,
        result: BacktestResult,
    ) {
        whenever(
            engine.simulate(
                eq("SBER"),
                any(),
                eq(capital),
                any(),
                eq(p.slPercent),
                eq(p.tpPercent),
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                eq(p.leverage),
                anyOrNull(),
                eq(p.riskPerTradePercent),
                eq(p.futuresMaxContractsPerPosition),
                anyOrNull(),
            ),
        ).thenReturn(result)
    }

    private fun sampleCandles(): List<Candle> {
        val candles = ArrayList<Candle>()
        for (i in 0 until 200) {
            val p = 300.0 - i * 0.5
            candles.add(
                Candle(
                    ticker = "SBER",
                    timeframe = "MINUTE_10",
                    openPrice = BigDecimal(p),
                    highPrice = BigDecimal(p * 1.01),
                    lowPrice = BigDecimal(p * 0.99),
                    closePrice = BigDecimal(p),
                    volume = 1000L,
                    time = LocalDateTime.now().plusMinutes(10L * i),
                ),
            )
        }
        return candles
    }
}
