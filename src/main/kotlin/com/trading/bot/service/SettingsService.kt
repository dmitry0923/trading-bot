package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.model.BotSettings
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference

/**
 * Потокобезопасные runtime-настройки торгового ядра.
 *
 * Настройки не персистятся и после перезапуска инициализируются из application.yml.
 * Изменения лимитов сразу применяются к [RiskConfig], а [tradingEnabled]
 * проверяется сервисами перед запуском стратегий и открытием новых позиций.
 * Мониторинг и закрытие уже открытых позиций остаются активными всегда.
 */
@Service
class SettingsService(
    private val riskConfig: RiskConfig,
) {
    private val settings = AtomicReference(
        BotSettings(
            tradingEnabled = true,
            riskEnabled = riskConfig.enabled,
            maxPositionRub = riskConfig.maxPositionRub.intValueExact(),
            maxDailyLossRub = riskConfig.maxDailyLossRub.intValueExact(),
        ),
    )

    fun getSettings(): BotSettings = settings.get()

    fun isTradingEnabled(): Boolean = settings.get().tradingEnabled

    /**
     * Валидирует и атомарно применяет новые настройки.
     * Риск-менеджмент нельзя отключить при включённой торговле: это сохраняет
     * fail-safe поведение production-бота.
     */
    @Synchronized
    fun updateSettings(newSettings: BotSettings): BotSettings {
        require(newSettings.maxPositionRub > 0) { "maxPositionRub must be positive" }
        require(newSettings.maxDailyLossRub > 0) { "maxDailyLossRub must be positive" }
        require(newSettings.maxDailyLossRub <= newSettings.maxPositionRub) {
            "maxDailyLossRub must not exceed maxPositionRub"
        }
        require(!newSettings.tradingEnabled || newSettings.riskEnabled) {
            "risk management cannot be disabled while trading is enabled"
        }

        riskConfig.enabled = newSettings.riskEnabled
        riskConfig.maxPositionRub = BigDecimal(newSettings.maxPositionRub)
        riskConfig.maxDailyLossRub = BigDecimal(newSettings.maxDailyLossRub)
        settings.set(newSettings)
        return newSettings
    }
}
