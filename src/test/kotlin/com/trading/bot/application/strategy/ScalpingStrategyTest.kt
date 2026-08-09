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

class ScalpingStrategyTest {
    private val strategy = ScalpingStrategy()
    private val price = BigDecimal("100.5")

    private fun context(
        prevClose: BigDecimal,
        lastClose: BigDecimal,
        atr: Double = 1.0,
        macdHist: Double = 0.1,
        lastVolume: Long = 1000,
    ): StrategyContext {
        val indicators =
            IndicatorCalculator.Indicators(
                rsi = 55.0,
                atr = atr,
                macdLine = 0.0,
                macdSignal = 0.0,
                macdHistogram = macdHist,
                bbUpper = BigDecimal("110"),
                bbMiddle = BigDecimal("100"),
                bbLower = BigDecimal("90"),
                trend = "UP",
                conclusion = "NEUTRAL",
            )
        return StrategyContext(
            ticker = "SBER",
            snapshot = MarketSnapshot(ticker = "SBER", currentPrice = price),
            candles = listOf(candle(prevClose), candle(lastClose, lastVolume)),
            indicators = indicators,
            cycleId = "cycle-1",
        )
    }

    private fun candle(
        close: BigDecimal,
        volume: Long = 1000,
    ) = Candle(
        ticker = "SBER",
        timeframe = "MINUTE_10",
        openPrice = close,
        highPrice = close,
        lowPrice = close,
        closePrice = close,
        volume = volume,
        time = LocalDateTime.of(2026, 1, 1, 10, 0),
    )

    @Test
    fun `BUY on strong up momentum with macd and volume confirmation`() {
        val decision =
            runBlocking { strategy.evaluate(context(prevClose = BigDecimal("100.0"), lastClose = BigDecimal("105.0"))) }
        assertEquals(StrategyAction.BUY, decision.action)
        assertTrue(decision.confidence in 0.4..0.8, "confidence=${decision.confidence}")
    }

    @Test
    fun `SELL on strong down momentum`() {
        val decision =
            runBlocking { strategy.evaluate(context(prevClose = BigDecimal("100.0"), lastClose = BigDecimal("95.0"), macdHist = -0.1)) }
        assertEquals(StrategyAction.SELL, decision.action)
    }

    @Test
    fun `HOLD on weak momentum`() {
        val decision =
            runBlocking { strategy.evaluate(context(prevClose = BigDecimal("100.0"), lastClose = BigDecimal("100.2"))) }
        assertEquals(StrategyAction.HOLD, decision.action)
        assertEquals(0.0, decision.confidence)
    }

    @Test
    fun `HOLD when macd contradicts momentum direction`() {
        val decision =
            runBlocking { strategy.evaluate(context(prevClose = BigDecimal("100.0"), lastClose = BigDecimal("105.0"), macdHist = -0.1)) }
        assertEquals(StrategyAction.HOLD, decision.action)
    }

    @Test
    fun `HOLD on weak volume confirmation`() {
        val decision =
            runBlocking {
                strategy.evaluate(
                    context(
                        prevClose = BigDecimal("100.0"),
                        lastClose = BigDecimal("105.0"),
                        lastVolume = 100,
                    ),
                )
            }
        assertEquals(StrategyAction.HOLD, decision.action)
    }

    @Test
    fun `HOLD with single candle`() {
        val indicators =
            IndicatorCalculator.Indicators(
                rsi = 55.0,
                atr = 1.0,
                macdLine = 0.0,
                macdSignal = 0.0,
                macdHistogram = 0.1,
                bbUpper = BigDecimal("110"),
                bbMiddle = BigDecimal("100"),
                bbLower = BigDecimal("90"),
                trend = "UP",
                conclusion = "NEUTRAL",
            )
        val context =
            StrategyContext(
                ticker = "SBER",
                snapshot = MarketSnapshot(ticker = "SBER", currentPrice = price),
                candles = listOf(candle(BigDecimal("100.0"))),
                indicators = indicators,
                cycleId = "cycle-1",
            )
        val decision = runBlocking { strategy.evaluate(context) }
        assertEquals(StrategyAction.HOLD, decision.action)
    }
}
