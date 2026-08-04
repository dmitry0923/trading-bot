package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.model.BotSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SettingsServiceTest {
    @Test
    fun `runtime risk limits are applied to risk config`() {
        val riskConfig = RiskConfig()
        val service = SettingsService(riskConfig)

        val updated = service.updateSettings(
            BotSettings(
                tradingEnabled = true,
                riskEnabled = true,
                maxPositionRub = 80_000,
                maxDailyLossRub = 4_000,
            ),
        )

        assertEquals(updated, service.getSettings())
        assertEquals(BigDecimal("80000"), riskConfig.maxPositionRub)
        assertEquals(BigDecimal("4000"), riskConfig.maxDailyLossRub)
        assertTrue(service.isTradingEnabled())
    }

    @Test
    fun `risk cannot be disabled while trading is enabled`() {
        val service = SettingsService(RiskConfig())

        assertThrows(IllegalArgumentException::class.java) {
            service.updateSettings(
                BotSettings(
                    tradingEnabled = true,
                    riskEnabled = false,
                    maxPositionRub = 50_000,
                    maxDailyLossRub = 5_000,
                ),
            )
        }
    }
}
