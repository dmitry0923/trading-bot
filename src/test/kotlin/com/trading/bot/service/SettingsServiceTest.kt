package com.trading.bot.service

import com.trading.bot.config.ExperimentConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.entity.BotSettings
import com.trading.bot.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import java.math.BigDecimal

/**
 * P1 (roadmap 13.17): SettingsService применяет сохранённые настройки в
 * runtime-конфиги RiskConfig/LeverageConfig/ExperimentConfig без перезапуска
 * и персистит изменения в БД.
 */
class SettingsServiceTest {
    private val repo = Mockito.mock(SettingsRepository::class.java)
    private val riskConfig = RiskConfig()
    private val leverageConfig = LeverageConfig()
    private val experimentConfig = ExperimentConfig()
    private val service = SettingsService(repo, riskConfig, leverageConfig, experimentConfig)

    @BeforeEach
    fun reset() {
        Mockito.reset(repo)
    }

    @Test
    fun `init loads persisted settings and applies them to runtime configs`() {
        val persisted =
            BotSettings(
                riskEnabled = false,
                maxPositionRub = 100000,
                maxOpenPositions = 5,
                futuresMaxOpenPositions = 2,
                maxVolatilityPercent = 8.0,
                defaultStopLossPercent = 1.5,
                leverageEnabled = false,
                userLeverage = 4.0,
                experimentEnabled = true,
                experimentId = "ab-1",
                experimentRolloutPercent = 40,
                variantPromptVersion = "v2",
            )
        runBlocking { Mockito.`when`(repo.loadSettings()).thenReturn(persisted) }

        service.init()

        assertEquals(persisted, service.getSettings())
        assertFalse(riskConfig.enabled)
        assertEquals(BigDecimal("100000"), riskConfig.maxPositionRub)
        assertEquals(5, riskConfig.maxOpenPositions)
        assertEquals(2, riskConfig.futuresMaxOpenPositions)
        assertEquals(8.0, riskConfig.maxVolatilityPercent)
        assertEquals(0, BigDecimal("1.5").compareTo(riskConfig.defaultStopLossPercent))
        assertFalse(leverageConfig.enabled)
        assertEquals(0, leverageConfig.userLeverage.compareTo(BigDecimal("4.0")))
        assertTrue(experimentConfig.enabled)
        assertEquals("ab-1", experimentConfig.experimentId)
        assertEquals(40, experimentConfig.rolloutPercent)
        assertEquals("v2", experimentConfig.variantPromptVersion)
        runBlocking { Mockito.verify(repo, Mockito.never()).saveSettings(any()) }
    }

    @Test
    fun `init saves defaults when nothing persisted`() {
        runBlocking { Mockito.`when`(repo.loadSettings()).thenReturn(null) }

        service.init()

        assertEquals(BotSettings(), service.getSettings())
        runBlocking { Mockito.verify(repo).saveSettings(BotSettings()) }
    }

    @Test
    fun `updateSettings persists and applies runtime config without restart`() {
        val newSettings =
            BotSettings(
                riskEnabled = true,
                maxPositionRub = 250000,
                maxDailyLossRub = 15000,
                maxOpenPositions = 7,
                futuresMaxOpenPositions = 3,
                maxSectorExposure = 3,
                maxVolatilityPercent = 6.0,
                defaultStopLossPercent = 1.5,
                defaultTakeProfitPercent = 5.0,
                trailingStopEnabled = false,
                trailingStopPercent = 2.0,
                riskPerTradePercent = 0.5,
                kellyFraction = 0.75,
                tradingHoursStart = "09:00",
                tradingHoursEnd = "19:00",
                leverageEnabled = true,
                userLeverage = 5.0,
                minLeverage = 1.0,
                maxLeverage = 10.0,
                maxDailyLossPercent = 12.0,
                maxRollingLossPercent7d = 20.0,
                maxRollingLossPercent30d = 30.0,
                maxConsecutiveLosses = 5,
                shadowModeEnabled = false,
                shadowModeCooldownHours = 12,
                volatilityIndexEnabled = false,
                maxVolatilityIndexPercent = 40.0,
                experimentEnabled = true,
                experimentId = "",
                experimentRolloutPercent = 150,
                variantPromptVersion = "   ",
            )

        runBlocking { service.updateSettings(newSettings) }

        runBlocking { Mockito.verify(repo).saveSettings(newSettings) }
        assertEquals(newSettings, service.getSettings())
        assertEquals(BigDecimal("250000"), riskConfig.maxPositionRub)
        assertEquals(BigDecimal("15000"), riskConfig.maxDailyLossRub)
        assertEquals(7, riskConfig.maxOpenPositions)
        assertEquals(3, riskConfig.futuresMaxOpenPositions)
        assertEquals(3, riskConfig.maxSectorExposure)
        assertFalse(riskConfig.trailingStopEnabled)
        assertEquals(2.0, riskConfig.trailingStopPercent)
        assertEquals(0.5, riskConfig.riskPerTradePercent)
        assertEquals(0.75, riskConfig.kellyFraction)
        assertEquals("09:00", riskConfig.tradingHoursStart)
        assertEquals("19:00", riskConfig.tradingHoursEnd)
        assertEquals(0, leverageConfig.userLeverage.compareTo(BigDecimal("5.0")))
        assertEquals(0, leverageConfig.maxLeverage.compareTo(BigDecimal("10.0")))
        assertEquals(12.0, riskConfig.maxDailyLossPercent)
        assertEquals(20.0, riskConfig.maxRollingLoss7dPercent)
        assertEquals(30.0, riskConfig.maxRollingLoss30dPercent)
        assertEquals(5, riskConfig.maxConsecutiveLosses)
        assertFalse(riskConfig.shadowModeEnabled)
        assertEquals(12L, riskConfig.shadowModeCooldownHours)
        assertFalse(riskConfig.volatilityIndexEnabled)
        assertEquals(40.0, riskConfig.maxVolatilityIndexPercent)
        assertTrue(experimentConfig.enabled)
        assertEquals(100, experimentConfig.rolloutPercent, "rollout усекается до 100")
        assertEquals("default", experimentConfig.experimentId, "пустой id -> default")
        assertNull(experimentConfig.variantPromptVersion, "пробельный вариант -> null")
    }

    @Test
    fun `getSettings returns in-memory snapshot without touching db`() {
        runBlocking { Mockito.`when`(repo.loadSettings()).thenReturn(null) }
        service.init()
        Mockito.reset(repo)

        val settings = service.getSettings()

        assertEquals(BotSettings(), settings)
        runBlocking {
            Mockito.verify(repo, Mockito.never()).loadSettings()
            Mockito.verify(repo, Mockito.never()).saveSettings(any())
        }
    }

    @Test
    fun `settings response hides the llm api key but reports it as configured`(): Unit =
        runBlocking {
            // Ключ провайдера не должен попадать в HTTP-ответ (роль ANALYTICS read-only),
            // UI получает только признак «задан» (скан-5 P2).
            Mockito.`when`(repo.loadSettings()).thenReturn(BotSettings(llmApiKey = "sk-secret"))
            service.init()

            val masked = service.getSettingsMasked()

            assertEquals("", masked.llmApiKey)
            assertTrue(masked.llmApiKeyConfigured)
            assertEquals("sk-secret", service.getSettings().llmApiKey, "внутренний путь ключ сохраняет")
        }

    @Test
    fun `blank llm api key from the ui keeps the stored key`(): Unit =
        runBlocking {
            // Форма настроек отправляет маскированное (пустое) значение — это «не менять»,
            // иначе каждое сохранение стирало бы ключ (скан-5 P2).
            Mockito.`when`(repo.loadSettings()).thenReturn(BotSettings(llmApiKey = "sk-secret"))
            service.init()

            service.updateSettingsFromUi(BotSettings(llmApiKey = "", maxOpenPositions = 2))

            assertEquals("sk-secret", service.getSettings().llmApiKey)
            assertEquals(2, service.getSettings().maxOpenPositions)
            assertEquals(2, riskConfig.maxOpenPositions)
        }

    @Test
    fun `explicit update replaces settings including the llm api key`(): Unit =
        runBlocking {
            // Внутренний путь (тесты/операторские скрипты) остаётся полной заменой.
            Mockito.`when`(repo.loadSettings()).thenReturn(BotSettings(llmApiKey = "sk-secret"))
            service.init()

            service.updateSettings(BotSettings(llmApiKey = "", maxOpenPositions = 1))

            assertEquals("", service.getSettings().llmApiKey)
        }
}
