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

class BreakoutStrategyTest {
    private val strategy = BreakoutStrategy()

    private val indicators =
        IndicatorCalculator.Indicators(
            rsi = 60.0,
            atr = 2.0,
            macdLine = 0.0,
            macdSignal = 0.0,
            macdHistogram = 0.1,
            bbUpper = BigDecimal("115"),
            bbMiddle = BigDecimal("100"),
            bbLower = BigDecimal("85"),
            trend = "UP",
            conclusion = "NEUTRAL",
        )

    private fun context(
        lastClose: BigDecimal,
        candleCount: Int = 22,
    ): StrategyContext {
        val candles =
            (0 until candleCount)
                .map {
                    candle(open = BigDecimal("100"), high = BigDecimal("110"), low = BigDecimal("90"), close = BigDecimal("100"))
                }.toMutableList()
        candles[candleCount - 1] = candle(open = BigDecimal("105"), high = lastClose, low = BigDecimal("100"), close = lastClose)
        return StrategyContext(
            ticker = "SBER",
            snapshot = MarketSnapshot(ticker = "SBER", currentPrice = lastClose),
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
    fun `BUY when close breaks above resistance`() {
        val decision = runBlocking { strategy.evaluate(context(lastClose = BigDecimal("112.0"))) }
        assertEquals(StrategyAction.BUY, decision.action)
        assertEquals(BigDecimal("112.0"), decision.targetPrice)
        assertTrue(decision.signalStrength in 0.45..0.9, "signalStrength=${decision.signalStrength}")
    }

    @Test
    fun `SELL when close breaks below support`() {
        val decision = runBlocking { strategy.evaluate(context(lastClose = BigDecimal("88.0"))) }
        assertEquals(StrategyAction.SELL, decision.action)
    }

    @Test
    fun `HOLD when close inside range`() {
        val decision = runBlocking { strategy.evaluate(context(lastClose = BigDecimal("100.0"))) }
        assertEquals(StrategyAction.HOLD, decision.action)
        assertEquals(0.0, decision.signalStrength)
    }

    @Test
    fun `HOLD with insufficient candles`() {
        val decision = runBlocking { strategy.evaluate(context(lastClose = BigDecimal("112.0"), candleCount = 10)) }
        assertEquals(StrategyAction.HOLD, decision.action)
    }
}
