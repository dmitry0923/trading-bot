package com.trading.bot.model

import java.math.BigDecimal
import java.time.Instant

data class TechnicalReport(
    val trend: String,
    val rsi: Double,
    val atr: Double,
    val macd: Double = 0.0,
    val bbUpper: BigDecimal? = null,
    val bbLower: BigDecimal? = null,
    val conclusion: String = "NEUTRAL",
    val confidence: Double = 0.0,
    val reasoning: String = "",
)

data class FundamentalReport(
    val conclusion: String,
    val confidence: Double = 0.0,
    val reasoning: String = "",
)

data class MarketSnapshot(
    val ticker: String = "",
    val currentPrice: BigDecimal,
    val bid: BigDecimal? = null,
    val ask: BigDecimal? = null,
    val volume: Long? = null,
    val timestamp: Instant = Instant.now(),
)

/**
 * Настройки бота, доступные через UI (SettingsPage) и хранящиеся в БД (bot_settings).
 *
 * Дефолты синхронизированы с application.yml (risk.*, leverage.*), чтобы
 * отображаемые в UI значения совпадали с фактически применяемыми.
 * Сервис SettingsService применяет изменения в RiskConfig/LeverageConfig сразу.
 */
data class BotSettings(
    val tradingEnabled: Boolean = true,
    val riskEnabled: Boolean = true,
    val maxPositionRub: Int = 50000,
    val maxDailyLossRub: Int = 5000,
    val tradingMode: String = "SIMULATION",
    val maxOpenPositions: Int = 3,
    val futuresMaxOpenPositions: Int = 1,
    val maxSectorExposure: Int = 2,
    val maxVolatilityPercent: Double = 5.0,
    val defaultStopLossPercent: Double = 2.0,
    val defaultTakeProfitPercent: Double = 4.0,
    val trailingStopEnabled: Boolean = true,
    val trailingStopPercent: Double = 1.0,
    val riskPerTradePercent: Double = 1.0,
    val tradingHoursStart: String = "10:00",
    val tradingHoursEnd: String = "18:30",
    val botIntervalMs: Long = 300000,
    val strategyIntervalMs: Long = 600000,
    val kellyFraction: Double = 0.5,
    val timeframes: List<String> = listOf("MINUTE_10"),
    val llmProvider: String = "ROUTER_AI",
    val llmModel: String = "",
    val llmBaseUrl: String = "",
    val llmApiKey: String = "",
    val forceCloseEnabled: Boolean = false,
    val forceCloseTime: String = "",
    val investorManagementEnabled: Boolean = true,
    val leverageEnabled: Boolean = true,
    val userLeverage: Double = 2.0,
    val minLeverage: Double = 1.0,
    val maxLeverage: Double = 3.0,
    val maxDailyLossPercent: Double = 10.0,
    val maxRollingLossPercent7d: Double = 15.0,
    val maxRollingLossPercent30d: Double = 25.0,
    val maxConsecutiveLosses: Int = 3,
    val shadowModeEnabled: Boolean = true,
    val shadowModeCooldownHours: Long = 24,
    val volatilityIndexEnabled: Boolean = true,
    val maxVolatilityIndexPercent: Double = 50.0,
    // Shadow Mode / Decision-level A/B эксперимент (Phases 3). Вкл/выкл и параметры
    // эксперимента поверх experiment.* конфигурации.
    val experimentEnabled: Boolean = false,
    val experimentId: String = "default",
    val experimentRolloutPercent: Int = 100,
    val variantPromptVersion: String = "",
) {
    fun llmProvider(): com.trading.bot.config.LlmProvider? =
        runCatching {
            com.trading.bot.config.LlmProvider
                .valueOf(llmProvider)
        }.getOrNull()
}

data class RiskCheckResult(
    val allowed: Boolean,
    val reason: String,
    val adjustedQty: Int,
)
