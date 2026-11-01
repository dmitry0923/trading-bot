package com.trading.bot.backtest

import com.trading.bot.model.PositionDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [BacktestTradeRecord] — ветви equals/hashCode по nullable-временам входа и выхода.
 */
class BacktestTradeRecordEqualityTest {
    private fun record(
        entryTime: LocalDateTime?,
        exitTime: LocalDateTime?,
        hourOfDay: Int? = null,
        dayOfWeek: String? = null,
        atrPercent: Double? = null,
        volatilityPercent: Double? = null,
    ): BacktestTradeRecord =
        BacktestTradeRecord(
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("100"),
            exitPrice = BigDecimal("101"),
            entryTime = entryTime,
            exitTime = exitTime,
            closeReason = "TP",
            pnl = 1.0,
            commission = 0.1,
            funding = 0.0,
            holdBars = 1,
            hourOfDay = hourOfDay,
            dayOfWeek = dayOfWeek,
            atrPercent = atrPercent,
            volatilityPercent = volatilityPercent,
        )

    @Test
    fun `records with null times are equal and share the hash`() {
        val first = record(null, null)
        val second = record(null, null)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `records with different nullable times are not equal`() {
        val now = LocalDateTime.of(2026, 10, 1, 10, 0)

        assertNotEquals(record(now, now), record(null, null))
        assertNotEquals(record(now, null), record(null, null))
        assertNotEquals(record(null, now), record(now, now))
    }
}
