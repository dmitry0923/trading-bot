package com.trading.bot.service

import com.trading.bot.config.ExperimentConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.entity.BotSettings
import com.trading.bot.repository.SettingsRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * Персистентное хранилище настроек бота.
 *
 * - Настройки читаются из PostgreSQL после старта контекста (после миграций Liquibase)
 *   и кэшируются в памяти.
 * - updateSettings() пишет в БД, обновляет кэш и применяет значения в
 *   [RiskConfig] / [LeverageConfig] — изменения влияют на торговлю сразу.
 * - getSettings() неблокирующий (in-memory), чтобы не нагружать R2DBC на горячем пути.
 */
@Service
class SettingsService(
    private val settingsRepository: SettingsRepository,
    private val riskConfig: RiskConfig,
    private val leverageConfig: LeverageConfig,
    private val experimentConfig: ExperimentConfig,
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
        applyRuntimeConfig(settings)
    }

    /**
     * Возвращает актуальные настройки бота.
     */
    fun getSettings(): BotSettings = settings

    /**
     * Текущие настройки **без секретов**: `llmApiKey` не сериализуется в ответ
     * (read-only роль ANALYTICS получала ключ провайдера открытым текстом),
     * вместо значения отдаётся флаг `llmApiKeyConfigured` (скан-5 P2).
     */
    fun getSettingsMasked(): BotSettings =
        settings.copy(
            llmApiKey = "",
            llmApiKeyConfigured = settings.llmApiKey.isNotBlank(),
        )

    /**
     * Полная замена настроек (внутренний/тестовый путь): значения применяются как есть.
     */
    suspend fun updateSettings(newSettings: BotSettings) {
        apply(newSettings.copy(llmApiKeyConfigured = false))
    }

    /**
     * Сохранение настроек из UI: `llmApiKey` приходит маскированным (пустым), поэтому
     * пустое значение означает «не менять» — иначе каждое сохранение формы стирало бы
     * ключ провайдера (скан-5 P2). Полная очистка ключа делается явным [updateSettings].
     */
    suspend fun updateSettingsFromUi(newSettings: BotSettings) {
        val merged =
            if (newSettings.llmApiKey.isBlank()) {
                newSettings.copy(llmApiKey = settings.llmApiKey)
            } else {
                newSettings
            }
        apply(merged.copy(llmApiKeyConfigured = false))
    }

    private suspend fun apply(newSettings: BotSettings) {
        settings = newSettings
        settingsRepository.saveSettings(newSettings)
        applyRuntimeConfig(newSettings)
        logger.info { "Bot settings updated: tradingEnabled=${newSettings.tradingEnabled} provider=${newSettings.llmProvider}" }
    }

    /**
     * Применяет значения из BotSettings в [RiskConfig] и [LeverageConfig].
     * Эти конфиги читаются торговыми сервисами на каждом цикле, поэтому
     * изменения из UI вступают в силу немедленно.
     */
    private fun applyRuntimeConfig(s: BotSettings) {
        riskConfig.enabled = s.riskEnabled
        riskConfig.maxPositionRub = BigDecimal(s.maxPositionRub)
        riskConfig.maxDailyLossRub = BigDecimal(s.maxDailyLossRub)
        riskConfig.maxOpenPositions = s.maxOpenPositions
        riskConfig.futuresMaxOpenPositions = s.futuresMaxOpenPositions
        riskConfig.maxSectorExposure = s.maxSectorExposure
        riskConfig.maxVolatilityPercent = s.maxVolatilityPercent
        riskConfig.defaultStopLossPercent = BigDecimal(s.defaultStopLossPercent.toString())
        riskConfig.defaultTakeProfitPercent = BigDecimal(s.defaultTakeProfitPercent.toString())
        riskConfig.trailingStopEnabled = s.trailingStopEnabled
        riskConfig.trailingStopPercent = s.trailingStopPercent
        riskConfig.riskPerTradePercent = s.riskPerTradePercent
        riskConfig.kellyFraction = s.kellyFraction
        riskConfig.tradingHoursStart = s.tradingHoursStart
        riskConfig.tradingHoursEnd = s.tradingHoursEnd

        leverageConfig.enabled = s.leverageEnabled
        leverageConfig.userLeverage = BigDecimal(s.userLeverage)
        leverageConfig.minLeverage = BigDecimal(s.minLeverage)
        leverageConfig.maxLeverage = BigDecimal(s.maxLeverage)

        riskConfig.maxDailyLossPercent = s.maxDailyLossPercent
        riskConfig.maxRollingLoss7dPercent = s.maxRollingLossPercent7d
        riskConfig.maxRollingLoss30dPercent = s.maxRollingLossPercent30d
        riskConfig.maxConsecutiveLosses = s.maxConsecutiveLosses
        riskConfig.shadowModeEnabled = s.shadowModeEnabled
        riskConfig.shadowModeCooldownHours = s.shadowModeCooldownHours
        riskConfig.volatilityIndexEnabled = s.volatilityIndexEnabled
        riskConfig.maxVolatilityIndexPercent = s.maxVolatilityIndexPercent

        // Shadow Mode / Decision-level A/B эксперимент
        experimentConfig.enabled = s.experimentEnabled
        experimentConfig.experimentId = s.experimentId.ifBlank { "default" }
        experimentConfig.variantPromptVersion = s.variantPromptVersion.ifBlank { null }
        experimentConfig.rolloutPercent = s.experimentRolloutPercent.coerceIn(0, 100)
    }
}
