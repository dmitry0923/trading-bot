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

data class BotSettings(
    val tradingEnabled: Boolean = true,
    val riskEnabled: Boolean = true,
    val maxPositionRub: Int = 500000,
    val maxDailyLossRub: Int = 50000,
    val tradingMode: String = "SIMULATION",
    val maxOpenPositions: Int = 3,
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
