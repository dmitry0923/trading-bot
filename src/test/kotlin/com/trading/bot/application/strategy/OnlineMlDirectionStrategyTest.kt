package com.trading.bot.application.strategy

import com.trading.bot.config.BacktestConfig
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class OnlineMlDirectionStrategyTest {
    private fun candles(
        start: Double,
        step: Double,
        count: Int,
    ): List<Candle> =
        (0 until count).map { i ->
            val close = start + step * i
            Candle(
                ticker = "CNYRUBF",
                timeframe = "MINUTE_10",
                openPrice = BigDecimal.valueOf(close - step / 2),
                highPrice = BigDecimal.valueOf(close + step),
                lowPrice = BigDecimal.valueOf(close - step),
                closePrice = BigDecimal.valueOf(close),
                volume = 1000L,
                time = LocalDateTime.of(2026, 9, 1, 10, 0).plusMinutes(10L * i),
            )
        }

    private fun context(
        window: List<Candle>,
        cycleId: String,
        ticker: String = "CNYRUBF",
    ): StrategyContext {
        val last = window.last()
        return StrategyContext(
            ticker = ticker,
            snapshot =
                MarketSnapshot(
                    ticker = ticker,
                    currentPrice = last.closePrice,
                    volume = last.volume,
                    timestamp = ZonedDateTime.of(last.time, ZoneId.systemDefault()).toInstant(),
                ),
            candles = window,
            indicators = IndicatorCalculator.calculate(window),
            cycleId = cycleId,
        )
    }

    @Test
    fun `rising series yields BUY after warmup within single simulation`() {
        val strategy = OnlineMlDirectionStrategy(minSamples = 40, signalMargin = 0.05)
        val all = candles(start = 100.0, step = 0.5, count = 300)
        val cycleId = "test-cycle-1"

        val actions = mutableListOf<StrategyAction>()
        runBlocking {
            // Симулируем 250 баров с увеличивающимся окном (без lookahead).
            for (i in 30 until all.size) {
                actions.add(strategy.evaluate(context(all.subList(0, i + 1), cycleId)).action)
            }
        }

        assertTrue(actions.any { it == StrategyAction.BUY }, "expected at least one BUY, got none")
        // warmup: первые бары HOLD (модель ещё не обучена).
        assertTrue(actions.take(30).all { it == StrategyAction.HOLD })
        // после зрелости модели восходящая серия не даёт SELL.
        assertTrue(actions.drop(120).none { it == StrategyAction.SELL }, "mature model on rising series must not produce SELL")
    }

    @Test
    fun `falling series yields SELL after warmup`() {
        val strategy = OnlineMlDirectionStrategy(minSamples = 5, signalMargin = 0.05)
        val all = candles(start = 200.0, step = -0.5, count = 200)
        val cycleId = "test-cycle-2"

        var sawSell = false
        runBlocking {
            for (i in 60 until all.size) {
                if (strategy.evaluate(context(all.subList(0, i + 1), cycleId)).action == StrategyAction.SELL) {
                    sawSell = true
                }
            }
        }
        assertTrue(sawSell, "falling series must produce SELL")
    }

    @Test
    fun `new simulation cycleId resets model state`() {
        val strategy = OnlineMlDirectionStrategy(minSamples = 5, signalMargin = 0.05)
        val all = candles(start = 100.0, step = 0.5, count = 200)

        runBlocking {
            // В первой симуляции модель дообучается до BUY.
            for (i in 30 until 150) {
                strategy.evaluate(context(all.subList(0, i + 1), "sim-1"))
            }
        }

        // Вторая симуляция: модель сброшена → первые бары снова HOLD (warmup).
        var earlyHoldCount = 0
        runBlocking {
            for (i in 30 until 100) {
                if (strategy.evaluate(context(all.subList(0, i + 1), "sim-2")).action == StrategyAction.HOLD) {
                    earlyHoldCount++
                }
            }
        }
        assertNotEquals(0, earlyHoldCount, "warmup must re-apply after reset")
    }

    @Test
    fun `disabled config returns no strategy`() {
        val config = BacktestConfig()
        config.mlDirectionEnabled = false
        assertEquals(null, OnlineMlDirectionStrategy.from(config))
        config.mlDirectionEnabled = true
        assertTrue(OnlineMlDirectionStrategy.from(config) != null)
    }
}
