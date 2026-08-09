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

class GridStrategyTest {
    private val strategy = GridStrategy()

    private val indicators =
        IndicatorCalculator.Indicators(
            rsi = 50.0,
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

    private fun context(price: BigDecimal): StrategyContext {
        val candles =
            (0 until 40).map {
                candle(open = BigDecimal("100"), high = BigDecimal("110"), low = BigDecimal("90"), close = BigDecimal("100"))
            }
        return StrategyContext(
            ticker = "SBER",
            snapshot = MarketSnapshot(ticker = "SBER", currentPrice = price),
            candles = candles,
            indicators = indicators,
            cycleId = "cycle-1",
        )
    }

    private fun candle(
        open: BigDecimal,
        high: BigDecimal,
        low: BigDecimal,
        close: BigDecimal,
    ) = Candle(
        ticker = "SBER",
        timeframe = "MINUTE_10",
        openPrice = open,
        highPrice = high,
        lowPrice = low,
        closePrice = close,
        volume = 1000,
        time = LocalDateTime.of(2026, 1, 1, 10, 0),
    )

    @Test
    fun `BUY near lower band of the range`() {
        val decision = runBlocking { strategy.evaluate(context(BigDecimal("92.0"))) }
        assertEquals(StrategyAction.BUY, decision.action)
        assertTrue(decision.confidence in 0.5..0.85, "confidence=${decision.confidence}")
    }

    @Test
    fun `SELL near upper band of the range`() {
        val decision = runBlocking { strategy.evaluate(context(BigDecimal("108.0"))) }
        assertEquals(StrategyAction.SELL, decision.action)
    }

    @Test
    fun `HOLD inside the range`() {
        val decision = runBlocking { strategy.evaluate(context(BigDecimal("100.0"))) }
        assertEquals(StrategyAction.HOLD, decision.action)
        assertEquals(0.0, decision.confidence)
    }

    @Test
    fun `HOLD with insufficient candles`() {
        val candles = (0 until 10).map { candle(BigDecimal("100"), BigDecimal("110"), BigDecimal("90"), BigDecimal("100")) }
        val decision =
            runBlocking {
                strategy.evaluate(
                    StrategyContext(
                        ticker = "SBER",
                        snapshot = MarketSnapshot(ticker = "SBER", currentPrice = BigDecimal("92.0")),
                        candles = candles,
                        indicators = indicators,
                        cycleId = "cycle-1",
                    ),
                )
            }
        assertEquals(StrategyAction.HOLD, decision.action)
    }
}
