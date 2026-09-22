package com.trading.bot.application

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MoexHolidayCalendarTest {
    private val calendar = MoexHolidayCalendar()

    @Test
    fun `weekends are not trading days`() {
        assertFalse(calendar.isTradingDay(LocalDate.of(2026, 9, 12))) // сб
        assertFalse(calendar.isTradingDay(LocalDate.of(2026, 9, 13))) // вс
        assertTrue(calendar.isNonTradingDay(LocalDate.of(2026, 9, 12)))
    }

    @Test
    fun `weekday is a trading day`() {
        assertTrue(calendar.isTradingDay(LocalDate.of(2026, 9, 8))) // вт
        assertTrue(calendar.isTradingDay(LocalDate.of(2026, 9, 11))) // пт
        assertFalse(calendar.isNonTradingDay(LocalDate.of(2026, 9, 8)))
    }

    @Test
    fun `russian public holidays are not trading days`() {
        assertTrue(calendar.isNonTradingDay(LocalDate.of(2026, 1, 1)))
        assertTrue(calendar.isNonTradingDay(LocalDate.of(2026, 1, 7)))
        assertTrue(calendar.isNonTradingDay(LocalDate.of(2026, 1, 8)))
        assertTrue(calendar.isNonTradingDay(LocalDate.of(2026, 2, 23)))
        assertTrue(calendar.isNonTradingDay(LocalDate.of(2026, 3, 8)))
        assertTrue(calendar.isNonTradingDay(LocalDate.of(2026, 5, 1)))
        assertTrue(calendar.isNonTradingDay(LocalDate.of(2026, 5, 9)))
        assertTrue(calendar.isNonTradingDay(LocalDate.of(2026, 6, 12)))
        assertTrue(calendar.isNonTradingDay(LocalDate.of(2026, 11, 4)))
    }

    @Test
    fun `extra holidays are excluded when provided`() {
        val extra = LocalDate.of(2026, 9, 9) // среда
        val withExtra = MoexHolidayCalendar(extraHolidays = setOf(extra))
        assertFalse(withExtra.isTradingDay(extra))
        assertTrue(calendar.isTradingDay(extra))
    }
}
