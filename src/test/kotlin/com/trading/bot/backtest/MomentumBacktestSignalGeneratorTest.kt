package com.trading.bot.backtest

import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class MomentumBacktestSignalGeneratorTest {
    private val generator = MomentumBacktestSignalGenerator()
    private val minBars = 30

    private fun candle(
        index: Int,
        close: Double,
        range: Double,
    ): Candle =
        Candle(
            ticker = "CNYRUBF",
            timeframe = "DAY_1",
            openPrice = BigDecimal.valueOf(close),
            highPrice = BigDecimal.valueOf(close + range / 2.0),
            lowPrice = BigDecimal.valueOf(close - range / 2.0),
            closePrice = BigDecimal.valueOf(close),
            volume = 1000L,
            time = BASE_TIME.plusDays(index.toLong()),
        )

    /** 280 высоковолатильных баров + 20 низковолатильных с заданным трендом. */
    private fun regimeCandles(
        trendStep: Double,
        highVolBars: Int = 280,
        lowVolBars: Int = 20,
    ): List<Candle> {
        val candles = mutableListOf<Candle>()
        repeat(highVolBars) { candles.add(candle(candles.size, 100.0, 2.0)) }
        var close = 100.0
        repeat(lowVolBars) { i ->
            close += trendStep
            candles.add(candle(candles.size, close, 0.1))
        }
        return candles
    }

    @Test
    fun `insufficient history returns hold`() =
        runBlocking {
            val candles = regimeCandles(trendStep = 0.1, highVolBars = 80, lowVolBars = 20)
            assertEquals(StrategyAction.HOLD, generator.signal("CNYRUBF", candles, candles.size - 1, minBars, "cycle"))
        }

    @Test
    fun `low volatility uptrend returns buy`() =
        runBlocking {
            val candles = regimeCandles(trendStep = 0.1)
            assertEquals(StrategyAction.BUY, generator.signal("CNYRUBF", candles, candles.size - 1, minBars, "cycle"))
        }

    @Test
    fun `low volatility downtrend returns sell`() =
        runBlocking {
            val candles = regimeCandles(trendStep = -0.1)
            assertEquals(StrategyAction.SELL, generator.signal("CNYRUBF", candles, candles.size - 1, minBars, "cycle"))
        }

    @Test
    fun `high volatility returns hold`() =
        runBlocking {
            val candles = (0 until 300).map { candle(it, 100.0, 2.0) }
            assertEquals(StrategyAction.HOLD, generator.signal("CNYRUBF", candles, candles.size - 1, minBars, "cycle"))
        }

    @Test
    fun `flat momentum returns hold`() =
        runBlocking {
            val candles = regimeCandles(trendStep = 0.0)
            assertEquals(StrategyAction.HOLD, generator.signal("CNYRUBF", candles, candles.size - 1, minBars, "cycle"))
        }

    @Test
    fun `signals during virtual holding are suppressed`() =
        runBlocking {
            val candles = regimeCandles(trendStep = 0.1, lowVolBars = 30)
            val stateful = MomentumBacktestSignalGenerator()
            assertEquals(StrategyAction.BUY, stateful.signal("CNYRUBF", candles, 299, minBars, "cycle"))
            assertEquals(StrategyAction.HOLD, stateful.signal("CNYRUBF", candles, 300, minBars, "cycle"))
            assertEquals(StrategyAction.HOLD, stateful.signal("CNYRUBF", candles, 304, minBars, "cycle"))
            assertEquals(StrategyAction.BUY, stateful.signal("CNYRUBF", candles, 305, minBars, "cycle"))
        }

    private companion object {
        private val BASE_TIME: LocalDateTime = LocalDateTime.of(2024, 1, 1, 0, 0)
    }
}
