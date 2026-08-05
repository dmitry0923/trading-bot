package com.trading.bot.service

import com.trading.bot.model.BotSettings
import com.trading.bot.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

/**
 * Персистентное хранилище настроек бота.
 *
 * - Настройки читаются из PostgreSQL после старта контекста (после миграций Liquibase)
 *   и кэшируются в памяти.
 * - updateSettings() пишет в БД и обновляет кэш — изменения применяются сразу.
 * - getSettings() неблокирующий (in-memory), чтобы не нагружать R2DBC на горячем пути.
 */
@Service
class SettingsService(
    private val settingsRepository: SettingsRepository,
) {
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var settings: BotSettings = BotSettings()

    @EventListener(ApplicationReadyEvent::class)
    fun init() {
        runBlocking {
            val persisted = settingsRepository.loadSettings()
            if (persisted != null) {
                settings = persisted
                logger.info { "Loaded bot settings from DB: tradingEnabled=${settings.tradingEnabled}" }
            } else {
                settingsRepository.saveSettings(settings)
                logger.info { "No persisted settings found, saved defaults" }
            }
        }
    }

    /**
     * Возвращает актуальные настройки бота.
     */
    fun getSettings(): BotSettings = settings

    /**
     * Заменяет настройки бота новыми значениями и персистит их.
     */
    fun updateSettings(newSettings: BotSettings) {
        settings = newSettings
        runBlocking {
            settingsRepository.saveSettings(newSettings)
        }
        logger.info { "Bot settings updated: tradingEnabled=${newSettings.tradingEnabled} provider=${newSettings.llmProvider}" }
    }
}
