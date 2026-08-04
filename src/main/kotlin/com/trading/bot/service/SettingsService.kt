package com.trading.bot.service

import com.trading.bot.model.BotSettings
import org.springframework.stereotype.Service

/**
 * Хранилище текущих настроек бота (in-memory).
 *
 * - getSettings(): актуальные настройки (BotSettings)
 * - updateSettings(): полная замена настроек
 * - Настройки не персистятся — сбрасываются при перезапуске приложения
 */
@Service
class SettingsService {
    private var settings = BotSettings()

    /**
     * Возвращает актуальные настройки бота.
     *
     * @return текущие настройки
     */
    fun getSettings(): BotSettings = settings

    /**
     * Заменяет настройки бота новыми значениями.
     *
     * @param newSettings новые настройки
     */
    fun updateSettings(newSettings: BotSettings) {
        settings = newSettings
    }
}
