package com.trading.bot.backtest

import com.trading.bot.model.PositionDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Unit-тест сериализации сделки бэктеста (`BacktestTradeRecord.toMap`): карта
 * используется research-отчётами, поэтому проверяются оба состояния —
 * с датами и без них (пропущенный timestamp не должен ломать выгрузку).
 */
class BacktestTradeRecordTest {
    private val time = LocalDateTime.of(2026, 1, 5, 10, 30)

    private fun record(
        entryTime: LocalDateTime?,
        exitTime: LocalDateTime?,
    ) = BacktestTradeRecord(
        ticker = "CNYRUBF",
        direction = PositionDirection.LONG,
        quantity = 2,
        entryPrice = BigDecimal("12.34"),
        exitPrice = BigDecimal("12.44"),
        entryTime = entryTime,
        exitTime = exitTime,
        closeReason = "TAKE_PROFIT",
        pnl = 1.5,
        commission = 0.2,
        funding = -0.1,
        holdBars = 7,
        hourOfDay = 10,
        dayOfWeek = "MONDAY",
        atrPercent = 0.4,
        volatilityPercent = 1.2,
    )

    @Test
    fun `missing timestamps are exported as nulls`() {
        val map = record(null, null).toMap()

        assertNull(map["entryTime"])
        assertNull(map["exitTime"])
        assertEquals("CNYRUBF", map["ticker"])
        assertEquals("LONG", map["direction"])
        assertEquals(7, map["holdBars"])
    }

    @Test
    fun `present timestamps are exported as ISO strings`() {
        val map = record(time, time.plusHours(1)).toMap()

        assertEquals(time.toString(), map["entryTime"])
        assertEquals(time.plusHours(1).toString(), map["exitTime"])
        assertEquals(10, map["hourOfDay"])
        assertEquals("MONDAY", map["dayOfWeek"])
        assertEquals(0.4, map["atrPercent"])
        assertEquals(1.2, map["volatilityPercent"])
    }
}
