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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Monte Carlo bootstrap и стресс-сценарии бэктеста (roadmap 13.7.8, MR-004/H-003).
 *
 * Чистая математика [MonteCarlo] тестируется детерминированно (seed);
 * [MonteCarloAnalyzer] — как оркестратор: базовый прогон, bootstrap по его
 * сделкам и перепрогон стресс-сценариев с увеличенными издержками.
 */
class MonteCarloAnalyzerTest {
    private val capital = BigDecimal("100000")

    @Test
    fun `single trade path is fully deterministic`() {
        // Одна сделка на +1000 руб. => каждый путь ровно +1% при любом seed.
        val result = MonteCarlo.simulate(listOf(1000.0), capital, simulations = 500, seed = 7)
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
    fun `all losing trades always end in loss`() {
        val result = MonteCarlo.simulate(listOf(-500.0, -300.0), capital, simulations = 1000, seed = 42)
        assertEquals(1.0, result.probabilityOfLoss, 1e-12)
        assertTrue(result.maxReturn < 0.0)
        assertFalse(result.isRobust())
    }

    @Test
    fun `same seed produces identical distribution`() {
        val trades = listOf(1000.0, -400.0, 700.0, -200.0, 1500.0, -600.0)
        val a = MonteCarlo.simulate(trades, capital, simulations = 2000, seed = 42)
        val b = MonteCarlo.simulate(trades, capital, simulations = 2000, seed = 42)
        assertEquals(a.medianReturn, b.medianReturn)
        assertEquals(a.p5Return, b.p5Return)
        assertEquals(a.p95Return, b.p95Return)
        assertEquals(a.probabilityOfLoss, b.probabilityOfLoss)
        assertEquals(a.minReturn, b.minReturn)
        assertEquals(a.maxReturn, b.maxReturn)
    }

    @Test
    fun `different seed changes the resampling`() {
        val trades = listOf(1000.0, -400.0, 700.0, -200.0, 1500.0, -600.0)
        val a = MonteCarlo.simulate(trades, capital, simulations = 500, seed = 1)
        val b = MonteCarlo.simulate(trades, capital, simulations = 500, seed = 2)
        assertTrue(a.medianReturn != b.medianReturn || a.p5Return != b.p5Return)
    }

    @Test
    fun `percentiles are ordered and within observed range`() {
        val trades = (1..20).map { (it - 8) * 100.0 } // смесь прибылей и убытков
        val result = MonteCarlo.simulate(trades, capital, simulations = 2000, seed = 42)
        assertTrue(result.p5Return <= result.medianReturn)
        assertTrue(result.medianReturn <= result.p95Return)
        assertTrue(result.minReturn <= result.p5Return)
        assertTrue(result.p95Return <= result.maxReturn)
        assertTrue(result.probabilityOfLoss in 0.0..1.0)
    }

    @Test
    fun `empty trades return neutral result`() {
        val result = MonteCarlo.simulate(emptyList(), capital, simulations = 100, seed = 42)
        assertEquals(0.0, result.medianReturn, 1e-12)
        assertEquals(0.0, result.probabilityOfLoss, 1e-12)
        assertFalse(result.isRobust())
    }

    @Test
    fun `zero simulations and non-positive capital return neutral result`() {
        val noSims = MonteCarlo.simulate(listOf(1000.0), capital, simulations = 0, seed = 42)
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
                listOf(1000.0, -500.0, 2000.0),
            )
        val scenario = StressScenarioResult.of("commission_x2", "Комиссия ×2", 2.0, 1.0, result)
        assertEquals("commission_x2", scenario.name)
        assertEquals(2.0, scenario.commissionMultiplier)
        assertEquals(3, scenario.totalTrades)
        assertEquals(result.totalReturn, scenario.totalReturn)
    }

    @Test
    fun `analyzer runs base bootstrap and all stress scenarios`() {
        val engine = mock<BacktestEngine> {}
        val baseResult = BacktestMetrics.compute("SBER", listOf(capital, BigDecimal("103000")), listOf(3000.0))
        val stressResult = BacktestMetrics.compute("SBER", listOf(capital, BigDecimal("102000")), listOf(2000.0))
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
        // 3000 руб. на каждую симуляцию из одной сделки -> ровно +3%
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
