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

class ArbitrageStrategyTest {
    private val strategy = ArbitrageStrategy()

    private val indicators =
        IndicatorCalculator.Indicators(
            rsi = 50.0,
            atr = 0.5,
            macdLine = 0.0,
            macdSignal = 0.0,
            macdHistogram = 0.0,
            bbUpper = BigDecimal("110"),
            bbMiddle = BigDecimal("100"),
            bbLower = BigDecimal("90"),
            trend = "SIDEWAYS",
            conclusion = "NEUTRAL",
        )

    private fun context(
        price: BigDecimal,
        relatedQuote: BigDecimal?,
    ): StrategyContext =
        StrategyContext(
            ticker = "Si",
            snapshot = MarketSnapshot(ticker = "Si", currentPrice = price),
            candles = emptyList(),
            indicators = indicators,
            cycleId = "cycle-1",
            relatedQuote = relatedQuote,
        )

    @Test
    fun `SELL when instrument trades rich vs related`() {
        val decision =
            runBlocking { strategy.evaluate(context(price = BigDecimal("102.0"), relatedQuote = BigDecimal("100.0"))) }
        assertEquals(StrategyAction.SELL, decision.action)
        assertTrue(decision.confidence in 0.5..0.9, "confidence=${decision.confidence}")
    }

    @Test
    fun `BUY when instrument trades cheap vs related`() {
        val decision =
            runBlocking { strategy.evaluate(context(price = BigDecimal("98.0"), relatedQuote = BigDecimal("100.0"))) }
        assertEquals(StrategyAction.BUY, decision.action)
    }

    @Test
    fun `HOLD when basis within threshold`() {
        val decision =
            runBlocking { strategy.evaluate(context(price = BigDecimal("100.1"), relatedQuote = BigDecimal("100.0"))) }
        assertEquals(StrategyAction.HOLD, decision.action)
        assertEquals(0.0, decision.confidence)
    }

    @Test
    fun `HOLD without related quote`() {
        val decision = runBlocking { strategy.evaluate(context(price = BigDecimal("102.0"), relatedQuote = null)) }
        assertEquals(StrategyAction.HOLD, decision.action)
        assertEquals(0.0, decision.confidence)
    }
}
