package com.trading.bot.application

import com.trading.bot.config.RiskConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

class TradingHoursGuardTest {

    private val guard = TradingHoursGuard(
        RiskConfig().apply {
            tradingHoursStart = "10:00"
            tradingHoursEnd = "18:30"
        }
    )

    @Test
    fun `trading blocked at 19 00`() {
        assertFalse(guard.isTradingAllowed(LocalTime.of(19, 0)))
    }

    @Test
    fun `trading blocked before open at 09 00`() {
        assertFalse(guard.isTradingAllowed(LocalTime.of(9, 0)))
    }

    @Test
    fun `trading blocked exactly at boundaries`() {
        assertFalse(guard.isTradingAllowed(LocalTime.of(10, 0)))
        assertFalse(guard.isTradingAllowed(LocalTime.of(18, 30)))
    }

    @Test
    fun `trading allowed at 12 00`() {
        assertTrue(guard.isTradingAllowed(LocalTime.of(12, 0)))
    }

    @Test
    fun `trading allowed inside window at 14 30`() {
        assertTrue(guard.isTradingAllowed(LocalTime.of(14, 30)))
    }

    @Test
    fun `inverted window always blocks`() {
        val broken = TradingHoursGuard(
            RiskConfig().apply {
                tradingHoursStart = "19:00"
                tradingHoursEnd = "18:00"
            }
        )
        assertFalse(broken.isTradingAllowed(LocalTime.of(12, 0)))
    }
}
