package com.trading.bot.application.strategy

import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

class CnyRubStrategyTest {
    private val strategy = CnyRubStrategy()

    @Test
    fun `returns HOLD for non CNY_RUB ticker`() = runBlocking {
        val ctx = context("SBER", indicators = indicators(rsi = 25.0, bbLower = BigDecimal("99"), bbUpper = BigDecimal("101"), bbMiddle = BigDecimal("100")))
        val result = strategy.evaluate(ctx)
        assertEquals(StrategyAction.HOLD, result.action)
        assertTrue(result.reasoning.contains("Not CNY_RUB"))
    }

    @Test
    fun `returns HOLD without indicators`() = runBlocking {
        val ctx = context("CNY_RUB", indicators = null)
        val result = strategy.evaluate(ctx)
        assertEquals(StrategyAction.HOLD, result.action)
        assertTrue(result.reasoning.contains("Insufficient"))
    }

    @Test
    fun `BUY when RSI oversold and price at lower BB`() = runBlocking {
        val ctx = context(
            "CNY_RUB",
            price = BigDecimal("12.3400"),
            indicators = indicators(
                rsi = 25.0,
                bbLower = BigDecimal("12.3400"),
                bbUpper = BigDecimal("12.5000"),
                bbMiddle = BigDecimal("12.4200"),
            ),
        )
        val result = strategy.evaluate(ctx)
        assertEquals(StrategyAction.BUY, result.action)
        assertTrue(result.signalStrength > 0.0)
    }

    @Test
    fun `SELL when RSI overbought and price at upper BB`() = runBlocking {
        val ctx = context(
            "CNY_RUB",
            price = BigDecimal("12.5000"),
            indicators = indicators(
                rsi = 75.0,
                bbLower = BigDecimal("12.3400"),
                bbUpper = BigDecimal("12.5000"),
                bbMiddle = BigDecimal("12.4200"),
            ),
        )
        val result = strategy.evaluate(ctx)
        assertEquals(StrategyAction.SELL, result.action)
        assertTrue(result.signalStrength > 0.0)
    }

    @Test
    fun `HOLD when RSI neutral`() = runBlocking {
        val ctx = context(
            "CNY_RUB",
            indicators = indicators(rsi = 50.0),
        )
        val result = strategy.evaluate(ctx)
        assertEquals(StrategyAction.HOLD, result.action)
    }

    @Test
    fun `HOLD when RSI oversold but price not at lower BB`() = runBlocking {
        val ctx = context(
            "CNY_RUB",
            price = BigDecimal("12.4200"),
            indicators = indicators(
                rsi = 25.0,
                bbLower = BigDecimal("12.3400"),
                bbUpper = BigDecimal("12.5000"),
                bbMiddle = BigDecimal("12.4200"),
            ),
        )
        val result = strategy.evaluate(ctx)
        assertEquals(StrategyAction.HOLD, result.action)
    }

    @Test
    fun `microstructure confirms buy`() = runBlocking {
        val ctx = context(
            "CNY_RUB",
            price = BigDecimal("12.3400"),
            indicators = indicators(
                rsi = 25.0,
                bbLower = BigDecimal("12.3400"),
                bbUpper = BigDecimal("12.5000"),
                bbMiddle = BigDecimal("12.4200"),
            ),
            bidSize = 1000L,
            askSize = 100L,
        )
        val result = strategy.evaluate(ctx)
        assertEquals(StrategyAction.BUY, result.action)
    }

    @Test
    fun `microstructure blocks buy when obi opposes`() = runBlocking {
        val ctx = context(
            "CNY_RUB",
            price = BigDecimal("12.3400"),
            indicators = indicators(
                rsi = 25.0,
                bbLower = BigDecimal("12.3400"),
                bbUpper = BigDecimal("12.5000"),
                bbMiddle = BigDecimal("12.4200"),
            ),
            bidSize = 100L,
            askSize = 1000L,
        )
        val result = strategy.evaluate(ctx)
        assertEquals(StrategyAction.HOLD, result.action)
    }

    @Test
    fun `fallback mode works without bidSize askSize`() = runBlocking {
        val ctx = context(
            "CNY_RUB",
            price = BigDecimal("12.3400"),
            indicators = indicators(
                rsi = 25.0,
                bbLower = BigDecimal("12.3400"),
                bbUpper = BigDecimal("12.5000"),
                bbMiddle = BigDecimal("12.4200"),
            ),
            bidSize = null,
            askSize = null,
        )
        val result = strategy.evaluate(ctx)
        assertEquals(StrategyAction.BUY, result.action)
    }

    @Test
    fun `signal strength above zero for valid signal`() = runBlocking {
        val ctx = context(
            "CNY_RUB",
            price = BigDecimal("12.3400"),
            indicators = indicators(
                rsi = 20.0,
                bbLower = BigDecimal("12.3400"),
                bbUpper = BigDecimal("12.5000"),
                bbMiddle = BigDecimal("12.4200"),
            ),
            bidSize = 1000L,
            askSize = 100L,
        )
        val result = strategy.evaluate(ctx)
        assertTrue(result.signalStrength in 0.0..1.0)
    }

    private fun context(
        ticker: String,
        price: BigDecimal = BigDecimal("12.42"),
        indicators: IndicatorCalculator.Indicators? = indicators(rsi = 50.0),
        bidSize: Long? = null,
        askSize: Long? = null,
    ): StrategyContext {
        val obi = if (bidSize != null && askSize != null) {
            com.trading.bot.domain.microstructure.ObiCalculator.calculate(bidSize, askSize)
        } else null
        val bid = price.multiply(BigDecimal("0.999")).setScale(4, RoundingMode.HALF_UP)
        val ask = price.multiply(BigDecimal("1.001")).setScale(4, RoundingMode.HALF_UP)
        val microprice = if (bidSize != null && askSize != null) {
            com.trading.bot.domain.microstructure.MicropriceCalculator.calculate(bid, ask, bidSize, askSize)
        } else null

        return StrategyContext(
            ticker = ticker,
            snapshot = MarketSnapshot(
                ticker = ticker,
                currentPrice = price,
                bid = bid,
                ask = ask,
                bidSize = bidSize,
                askSize = askSize,
                microprice = microprice,
                obi = obi,
            ),
            candles = listOf(
                Candle(
                    ticker = ticker,
                    timeframe = "MINUTE_10",
                    openPrice = price,
                    highPrice = price.add(BigDecimal("0.01")),
                    lowPrice = price.subtract(BigDecimal("0.01")),
                    closePrice = price,
                    volume = 1000L,
                    time = LocalDateTime.now().minusMinutes(10),
                ),
            ),
            indicators = indicators,
            cycleId = "test-cycle",
        )
    }

    private fun indicators(
        rsi: Double = 50.0,
        bbLower: BigDecimal = BigDecimal("99"),
        bbUpper: BigDecimal = BigDecimal("101"),
        bbMiddle: BigDecimal = BigDecimal("100"),
    ) = IndicatorCalculator.Indicators(
        rsi = rsi,
        atr = 0.01,
        macdLine = 0.0,
        macdSignal = 0.0,
        macdHistogram = 0.0,
        bbUpper = bbUpper,
        bbMiddle = bbMiddle,
        bbLower = bbLower,
        trend = "SIDEWAYS",
        conclusion = "NEUTRAL",
    )
}
