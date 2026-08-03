package com.trading.bot.service

import com.trading.bot.model.BotSettings
import org.springframework.stereotype.Service

@Service
class SettingsService {
    private var settings = BotSettings()

    fun getSettings(): BotSettings = settings

    fun updateSettings(newSettings: BotSettings) {
        settings = newSettings
    }
}
