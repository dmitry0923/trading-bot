package com.trading.bot.application.strategy

import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class MeanReversionStrategyTest {
    private val strategy = MeanReversionStrategy()
    private val price = BigDecimal("100.0")

    private fun context(
        rsi: Double,
        close: BigDecimal,
    ): StrategyContext {
        val indicators =
            IndicatorCalculator.Indicators(
                rsi = rsi,
                atr = 1.0,
                macdLine = 0.0,
                macdSignal = 0.0,
                macdHistogram = 0.0,
                bbUpper = BigDecimal("110"),
                bbMiddle = BigDecimal("100"),
                bbLower = BigDecimal("90"),
                trend = "SIDEWAYS",
                conclusion = "NEUTRAL",
            )
        return StrategyContext(
            ticker = "SBER",
            snapshot = MarketSnapshot(ticker = "SBER", currentPrice = price),
            candles = listOf(candle(close)),
            indicators = indicators,
            cycleId = "cycle-1",
        )
    }

    private fun candle(close: BigDecimal) =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = close,
            highPrice = close,
            lowPrice = close,
            closePrice = close,
            volume = 1000,
            time = LocalDateTime.of(2026, 1, 1, 10, 0),
        )

    @Test
    fun `BUY when oversold at lower band`() {
        val decision = runBlocking { strategy.evaluate(context(rsi = 25.0, close = BigDecimal("89.0"))) }
        assertEquals(StrategyAction.BUY, decision.action)
        assertEquals(price, decision.targetPrice)
        assertTrue(decision.confidence in 0.5..0.9, "confidence=${decision.confidence}")
    }

    @Test
    fun `SELL when overbought at upper band`() {
        val decision = runBlocking { strategy.evaluate(context(rsi = 75.0, close = BigDecimal("111.0"))) }
        assertEquals(StrategyAction.SELL, decision.action)
    }

    @Test
    fun `HOLD in neutral zone`() {
        val decision = runBlocking { strategy.evaluate(context(rsi = 50.0, close = BigDecimal("100.0"))) }
        assertEquals(StrategyAction.HOLD, decision.action)
        assertEquals(0.0, decision.confidence)
    }

    @Test
    fun `HOLD when oversold but price above lower band`() {
        val decision = runBlocking { strategy.evaluate(context(rsi = 25.0, close = BigDecimal("95.0"))) }
        assertEquals(StrategyAction.HOLD, decision.action)
    }
}
