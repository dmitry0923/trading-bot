package com.trading.bot.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class FundingCostsTest {
    private fun dt(
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): LocalDateTime = LocalDateTime.of(2026, 9, day, hour, minute)

    // 2026-09: 9/7 пн, 9/8 вт, 9/9 ср, 9/10 чт, 9/11 пт, 9/12 сб, 9/13 вс, 9/14 пн.

    @Test
    fun `intraday position before clearing pays nothing`() {
        // вт 10:00 → вт 12:00
        assertEquals(0, FundingCosts.clearingsCrossed(dt(8, 10, 0), dt(8, 12, 0)))
    }

    @Test
    fun `position crossing one evening clearing pays once`() {
        // вт 10:00 → ср 17:00 (пережили клиринг вт 18:45, до клиринга ср закрыта)
        assertEquals(1, FundingCosts.clearingsCrossed(dt(8, 10, 0), dt(9, 17, 0)))
    }

    @Test
    fun `position open across full weekdays counts every clearing day`() {
        // вт 10:00 → чт 19:00 (вт, ср, чт — три клиринга; закрыта уже после чт 18:45)
        assertEquals(3, FundingCosts.clearingsCrossed(dt(8, 10, 0), dt(10, 19, 0)))
    }

    @Test
    fun `weekend days have no clearing`() {
        // пт 10:00 → пн 19:00 (клирингов: пт, сб/вс нет, пн) = 2
        assertEquals(2, FundingCosts.clearingsCrossed(dt(11, 10, 0), dt(14, 19, 0)))
    }

    @Test
    fun `open and close on weekend alone pays nothing`() {
        // сб 10:00 → вс 19:00 (клирингов в выходные нет)
        assertEquals(0, FundingCosts.clearingsCrossed(dt(12, 10, 0), dt(13, 19, 0)))
    }

    @Test
    fun `closed before clearing does not count that day`() {
        // вт 10:00 → вт 17:00 (до 18:45 — клиринга не пережила)
        assertEquals(0, FundingCosts.clearingsCrossed(dt(8, 10, 0), dt(8, 17, 0)))
    }

    @Test
    fun `closed exactly at clearing does not count`() {
        // вт 10:00 → вт 18:45 — строгое isAfter(clearing) = false
        assertEquals(0, FundingCosts.clearingsCrossed(dt(8, 10, 0), dt(8, 18, 45)))
    }

    @Test
    fun `opened exactly at clearing does not count that day`() {
        // вт 18:45 → вт 20:00 — openedAt < clearing вт = false → 0
        assertEquals(0, FundingCosts.clearingsCrossed(dt(8, 18, 45), dt(8, 20, 0)))
    }

    @Test
    fun `invalid interval returns zero`() {
        assertEquals(0, FundingCosts.clearingsCrossed(dt(9, 12, 0), dt(8, 12, 0)))
        assertEquals(0, FundingCosts.clearingsCrossed(dt(8, 12, 0), dt(8, 12, 0)))
    }
}
