package com.trading.bot.backtest

import com.trading.bot.model.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.LocalDateTime

class BacktestEngineTest {

    private val engine = BacktestEngine(
        com.trading.bot.repository.CandleRepository(Mockito.mock(DatabaseClient::class.java))
    )

    private fun candle(price: Double, i: Int): Candle = Candle(
        ticker = "SBER",
        timeframe = "MINUTE_10",
        openPrice = BigDecimal(price),
        highPrice = BigDecimal(price * 1.01),
        lowPrice = BigDecimal(price * 0.99),
        closePrice = BigDecimal(price),
        volume = 1000L,
        time = LocalDateTime.now().plusMinutes(10L * i)
    )

    /** Нисходящий тренд: RSI низкий, MACD hist должен давать BUY на дне. */
    private fun candles(seed: Double, count: Int, step: Double): List<Candle> =
        (0 until count).map { i -> candle(seed - i * step, i) }

    @Test
    fun `simulate produces results on trending data`() {
        val candles = candles(seed = 300.0, count = 300, step = 0.5)
        val result = engine.simulate("SBER", candles)

        assertTrue(result.totalTrades >= 0)
        assertTrue(result.equityCurve.isNotEmpty())
        assertTrue(result.sharpeRatio.isFinite())
        assertTrue(result.profitFactor >= 0.0)
    }

    @Test
    fun `sharpe ratio is zero for flat returns`() {
        val result = BacktestMetrics.compute(
            "SBER",
            listOf(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
            listOf(0.0, 0.0, 0.0)
        )
        assertEquals(0.0, result.sharpeRatio)
    }

    @Test
    fun `max drawdown is computed correctly`() {
        val mdd = BacktestMetrics.maxDrawdown(
            listOf(
                BigDecimal("1.00"),
                BigDecimal("1.50"),
                BigDecimal("1.20"),
                BigDecimal("1.30"),
                BigDecimal("0.90")
            )
        )
        // пик 1.50 -> минимум 0.90 => DD = 1 - 0.9/1.5 = 0.4
        assertEquals(0.4, mdd, 1e-6)
    }

    @Test
    fun `acceptance criteria reject weak results`() {
        val result = BacktestResult(
            ticker = "SBER",
            totalReturn = 0.05,
            sharpeRatio = 0.8,
            maxDrawdown = 0.25,
            winRate = 0.4,
            profitFactor = 1.0,
            totalTrades = 50,
            avgHoldBars = 3.0,
            equityCurve = emptyList(),
            monthlyReturns = emptyMap()
        )
        assertFalse(result.isPassable())
    }

    @Test
    fun `acceptance criteria pass strong results`() {
        val result = BacktestResult(
            ticker = "SBER",
            totalReturn = 0.30,
            sharpeRatio = 1.5,
            maxDrawdown = 0.10,
            winRate = 0.55,
            profitFactor = 1.8,
            totalTrades = 150,
            avgHoldBars = 3.0,
            equityCurve = emptyList(),
            monthlyReturns = emptyMap()
        )
        assertTrue(result.isPassable())
    }

    @Test
    fun `commission and slippage constants`() {
        assertEquals(BigDecimal("0.0005"), SimulatedExecution.COMMISSION_RATE)
        assertEquals(BigDecimal("0.001"), SimulatedExecution.MARKET_SLIPPAGE_RATE)
    }
}
