package com.trading.bot.application.strategy

import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TrendFollowingStrategyTest {
    private val strategy = TrendFollowingStrategy()
    private val price = BigDecimal("100.0")

    private fun context(indicators: IndicatorCalculator.Indicators): StrategyContext =
        StrategyContext(
            ticker = "SBER",
            snapshot = MarketSnapshot(ticker = "SBER", currentPrice = price),
            candles = emptyList(),
            indicators = indicators,
            cycleId = "cycle-1",
        )

    private fun indicators(
        trend: String,
        macdHist: Double,
        rsi: Double,
    ) = IndicatorCalculator.Indicators(
        rsi = rsi,
        atr = 1.0,
        macdLine = 0.0,
        macdSignal = 0.0,
        macdHistogram = macdHist,
        bbUpper = BigDecimal("110"),
        bbMiddle = BigDecimal("100"),
        bbLower = BigDecimal("90"),
        trend = trend,
        conclusion = "NEUTRAL",
    )

    @Test
    fun `BUY on upward trend with positive macd and moderate rsi`() {
        val decision = runBlocking { strategy.evaluate(context(indicators("UP", 0.5, 55.0))) }
        assertEquals(StrategyAction.BUY, decision.action)
        assertEquals(price, decision.targetPrice)
        assertTrue(decision.signalStrength in 0.45..0.9, "signalStrength=${decision.signalStrength}")
    }

    @Test
    fun `SELL on downward trend with negative macd and moderate rsi`() {
        val decision = runBlocking { strategy.evaluate(context(indicators("DOWN", -0.5, 45.0))) }
        assertEquals(StrategyAction.SELL, decision.action)
    }

    @Test
    fun `HOLD when macd contradicts trend`() {
        val decision = runBlocking { strategy.evaluate(context(indicators("UP", -0.2, 55.0))) }
        assertEquals(StrategyAction.HOLD, decision.action)
        assertEquals(0.0, decision.signalStrength)
    }

    @Test
    fun `HOLD in overbought zone even with up trend`() {
        val decision = runBlocking { strategy.evaluate(context(indicators("UP", 0.5, 75.0))) }
        assertEquals(StrategyAction.HOLD, decision.action)
    }

    @Test
    fun `HOLD with insufficient indicators`() {
        val decision =
            runBlocking {
                strategy.evaluate(
                    StrategyContext(
                        ticker = "SBER",
                        snapshot = MarketSnapshot(ticker = "SBER", currentPrice = price),
                        candles = emptyList(),
                        indicators = null,
                        cycleId = "cycle-1",
                    ),
                )
            }
        assertEquals(StrategyAction.HOLD, decision.action)
    }
}
