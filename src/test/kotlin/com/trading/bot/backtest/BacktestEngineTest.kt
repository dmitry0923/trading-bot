package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.LocalDateTime

class BacktestEngineTest {
    private val engine =
        BacktestEngine(
            com.trading.bot.repository
                .CandleRepository(Mockito.mock(DatabaseClient::class.java)),
        )

    private fun candle(
        price: Double,
        i: Int,
    ): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal(price),
            highPrice = BigDecimal(price * 1.01),
            lowPrice = BigDecimal(price * 0.99),
            closePrice = BigDecimal(price),
            volume = 1000L,
            time = LocalDateTime.now().plusMinutes(10L * i),
        )

    /** Нисходящий тренд: RSI низкий, MACD hist должен давать BUY на дне. */
    private fun candles(): List<Candle> = (0 until 300).map { i -> candle(300.0 - i * 0.5, i) }

    /** V-образная серия: падение до oversold, затем рост — детерминированный BUY. */
    private fun trendingCandles(): List<Candle> {
        val prices =
            (0 until 100).map { 200.0 - it * 1.0 } +
                (100 until 300).map { 100.0 + (it - 100) * 0.5 }
        return prices.mapIndexed { i, price -> candle(price, i) }
    }

    @Test
    fun `simulate produces results on trending data`() {
        val candles = candles()
        val result = engine.simulate("SBER", candles)

        assertTrue(result.totalTrades >= 0)
        assertTrue(result.equityCurve.isNotEmpty())
        assertTrue(result.sharpeRatio.isFinite())
        assertTrue(result.profitFactor >= 0.0)
    }

    @Test
    fun `sharpe ratio is zero for flat returns`() {
        val result =
            BacktestMetrics.compute(
                "SBER",
                listOf(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                listOf(0.0, 0.0, 0.0),
            )
        assertEquals(0.0, result.sharpeRatio)
    }

    @Test
    fun `max drawdown is computed correctly`() {
        val mdd =
            BacktestMetrics.maxDrawdown(
                listOf(
                    BigDecimal("1.00"),
                    BigDecimal("1.50"),
                    BigDecimal("1.20"),
                    BigDecimal("1.30"),
                    BigDecimal("0.90"),
                ),
            )
        // пик 1.50 -> минимум 0.90 => DD = 1 - 0.9/1.5 = 0.4
        assertEquals(0.4, mdd, 1e-6)
    }

    @Test
    fun `acceptance criteria reject weak results`() {
        val result =
            BacktestResult(
                ticker = "SBER",
                totalReturn = 0.05,
                sharpeRatio = 0.8,
                maxDrawdown = 0.25,
                winRate = 0.4,
                profitFactor = 1.0,
                totalTrades = 50,
                avgHoldBars = 3.0,
                equityCurve = emptyList(),
                monthlyReturns = emptyMap(),
            )
        assertFalse(result.isPassable())
    }

    @Test
    fun `acceptance criteria pass strong results`() {
        val result =
            BacktestResult(
                ticker = "SBER",
                totalReturn = 0.30,
                sharpeRatio = 1.5,
                sortinoRatio = 1.8,
                maxDrawdown = 0.10,
                winRate = 0.55,
                profitFactor = 1.8,
                totalTrades = 250,
                avgHoldBars = 3.0,
                equityCurve = emptyList(),
                monthlyReturns = emptyMap(),
                expectancy = 120.0,
                winLossRatio = 1.4,
                avgTrade = 100.0,
                recoveryFactor = 3.0,
            )
        assertTrue(result.isPassable())
    }

    @Test
    fun `backtest metrics include risk and quality ratios`() {
        val result =
            BacktestMetrics.compute(
                "SBER",
                listOf(BigDecimal("100000"), BigDecimal("101000"), BigDecimal("99000"), BigDecimal("102000")),
                listOf(1000.0, -500.0, 2000.0),
            )
        assertEquals(3, result.totalTrades)
        assertEquals(2.0 / 3.0, result.winRate, 1e-9)
        assertTrue(result.sortinoRatio.isFinite())
        // AvgTrade = средний P&L сделки; Expectancy (Van Tharp) для $-доходностей
        // совпадает с ним: (Win% × AvgWin) − (Loss% × AvgLoss) = 833.33
        assertEquals(2500.0 / 3.0, result.avgTrade, 1e-9)
        assertEquals((2.0 / 3.0) * 1500.0 - (1.0 / 3.0) * 500.0, result.expectancy, 1e-9)
        assertEquals(1500.0 / 500.0, result.winLossRatio, 1e-9)
        assertEquals(2500.0 / result.maxDrawdown, result.recoveryFactor, 1e-9)
    }

    @Test
    fun `metrics map is compact and excludes heavy series`() {
        val result =
            BacktestResult(
                ticker = "SBER",
                totalReturn = 0.30,
                sharpeRatio = 1.5,
                sortinoRatio = 1.8,
                maxDrawdown = 0.10,
                winRate = 0.55,
                profitFactor = 1.8,
                totalTrades = 250,
                avgHoldBars = 3.0,
                equityCurve = listOf(BigDecimal("100000"), BigDecimal("110000")),
                monthlyReturns = mapOf("2026-07" to 0.05),
                tradeReturns = listOf(100.0, -50.0),
                expectancy = 120.0,
                winLossRatio = 1.4,
                avgTrade = 100.0,
                recoveryFactor = 3.0,
            )

        val metrics = result.metrics()

        assertEquals(13, metrics.size)
        assertEquals(1.5, metrics["sharpeRatio"])
        assertEquals(250, metrics["totalTrades"])
        assertEquals(true, metrics["passable"])
        assertFalse(metrics.containsKey("equityCurve"))
        assertFalse(metrics.containsKey("monthlyReturns"))
        assertFalse(metrics.containsKey("tradeReturns"))
    }

    @Test
    fun `backtest config exposes default values`() {
        val config = BacktestConfig()
        assertEquals(BigDecimal("100000"), config.initialCapital)
        assertEquals(365, config.days)
        assertEquals("MINUTE_10", config.timeframe)
        assertEquals(30, config.minBarsForSignal)
        assertEquals(2.0, config.slPercent)
        assertEquals(4.0, config.tpPercent)
        assertEquals(0.20, config.capitalSlice)
    }

    @Test
    fun `initial capital from config scales equity proportionally`() {
        val base = engine.simulate("SBER", trendingCandles())
        val big =
            BacktestEngine(
                com.trading.bot.repository
                    .CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                backtestConfig =
                    BacktestConfig().apply {
                        initialCapital = BigDecimal("200000")
                    },
            ).simulate("SBER", trendingCandles())
        assertTrue(base.totalTrades > 0, "fixture must produce trades")
        val ratio = big.equityCurve.last().toDouble() / base.equityCurve.last().toDouble()
        assertTrue(ratio in 1.5..2.5, "expected ~2x equity scaling with doubled capital, got $ratio")
    }

    @Test
    fun `capital slice from config changes position size`() {
        val base = engine.simulate("SBER", trendingCandles())
        val doubled =
            BacktestEngine(
                com.trading.bot.repository
                    .CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                backtestConfig =
                    BacktestConfig().apply {
                        capitalSlice = 0.40
                    },
            ).simulate("SBER", trendingCandles())
        assertTrue(base.totalTrades > 0, "fixture must produce trades")
        val baseDeviation =
            base.equityCurve
                .last()
                .subtract(BigDecimal("100000"))
                .abs()
        val doubledDeviation =
            doubled.equityCurve
                .last()
                .subtract(BigDecimal("100000"))
                .abs()
        assertTrue(
            doubledDeviation > baseDeviation.multiply(BigDecimal("1.5")),
            "doubling capital slice should scale P&L, base=${base.equityCurve.last()} doubled=${doubled.equityCurve.last()}",
        )
    }

    @Test
    fun `commission and slippage constants`() {
        assertEquals(BigDecimal("0.0005"), SimulatedExecution.COMMISSION_RATE)
        assertEquals(BigDecimal("0.001"), SimulatedExecution.MARKET_SLIPPAGE_RATE)
    }

    @Test
    fun `lot rounding is down to whole lots of instrument`() {
        // SBER lot = 10
        assertEquals(0, SimulatedExecution.lotRounded(5, 10))
        assertEquals(10, SimulatedExecution.lotRounded(10, 10))
        assertEquals(90, SimulatedExecution.lotRounded(99, 10))
        // VTBR lot = 1000
        assertEquals(0, SimulatedExecution.lotRounded(999, 1000))
        assertEquals(1000, SimulatedExecution.lotRounded(1500, 1000))
        // неизвестный инструмент (lotSize <= 0) — лотность игнорируется
        assertEquals(77, SimulatedExecution.lotRounded(77, 0))
        assertEquals(77, SimulatedExecution.lotRounded(77, -1))
    }
}
