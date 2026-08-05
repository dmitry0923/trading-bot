package com.trading.bot.application

import com.trading.bot.service.SettingsService
import org.springframework.stereotype.Component

/**
 * Единый флаг включения/выключения торговли.
 *
 * Читает настройку tradingEnabled (управляется через UI /api/v1/settings).
 * Используется на всех точках входа в позиции — как для акций, так и для фьючерсов.
 */
@Component
class TradingGate(
    private val settingsService: SettingsService,
) {
    fun isTradingEnabled(): Boolean = settingsService.getSettings().tradingEnabled
}
