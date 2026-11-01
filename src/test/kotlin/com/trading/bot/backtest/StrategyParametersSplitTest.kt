package com.trading.bot.backtest

import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [splitDevHoldout] — границы «строго внутри (0,1)» и корректное деление истории.
 */
class StrategyParametersSplitTest {
    private fun candle(index: Int): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal("100"),
            highPrice = BigDecimal("101"),
            lowPrice = BigDecimal("99"),
            closePrice = BigDecimal("100"),
            volume = 1_000L,
            time = LocalDateTime.of(2026, 1, 1, 10, 0).plusMinutes(10L * index),
        )

    @Test
    fun `history is split at the holdout boundary`() {
        val (dev, holdout) = splitDevHoldout((0 until 10).map(::candle), 0.3)

        assertEquals(7, dev.size)
        assertEquals(3, holdout.size)
        assertEquals(true, dev.last().time.isBefore(holdout.first().time))
    }

    @Test
    fun `fractions at or outside the bounds are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { splitDevHoldout(emptyList(), 0.0) }
        assertThrows(IllegalArgumentException::class.java) { splitDevHoldout(emptyList(), 1.0) }
        assertThrows(IllegalArgumentException::class.java) { splitDevHoldout(emptyList(), -0.1) }
    }
}
