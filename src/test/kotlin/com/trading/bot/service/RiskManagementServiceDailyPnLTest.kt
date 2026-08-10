package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Дневной P&L и дневной лимит убытка живут в едином источнике
 * [DrawdownProtectionService]. RiskManagementService — только делегат без
 * локального состояния и без записи в daily_risk_snapshot.
 */
class RiskManagementServiceDailyPnLTest {
    private val drawdownProtection = Mockito.mock(DrawdownProtectionService::class.java)

    private fun service(): RiskManagementService =
        RiskManagementService(
            RiskConfig(),
            drawdownProtection,
            SimpleMeterRegistry(),
            Mockito.mock(AumProvider::class.java),
        )

    @Test
    fun `updateDailyPnl delegates to drawdown protection`() {
        val s = service()

        s.updateDailyPnL(BigDecimal("1000"))
        s.updateDailyPnL(BigDecimal("-400"))

        Mockito.verify(drawdownProtection).updateDailyPnl(BigDecimal("1000"))
        Mockito.verify(drawdownProtection).updateDailyPnl(BigDecimal("-400"))
    }

    @Test
    fun `getDailyPnl reads from drawdown protection`() {
        Mockito.`when`(drawdownProtection.getDailyPnl()).thenReturn(BigDecimal("600"))

        assertEquals(0, BigDecimal("600").compareTo(service().getDailyPnL()))
    }

    @Test
    fun `isDailyLossLimitReached reads from drawdown protection`() {
        val s = service()

        Mockito.`when`(drawdownProtection.isDailyLossLimitReached()).thenReturn(false)
        assertFalse(s.isDailyLossLimitReached())

        Mockito.`when`(drawdownProtection.isDailyLossLimitReached()).thenReturn(true)
        assertTrue(s.isDailyLossLimitReached())
    }
}
