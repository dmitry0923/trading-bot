package com.trading.bot.model

import java.math.BigDecimal

data class TechnicalReport(
    val trend: String,
    val rsi: Double,
    val atr: Double
)

data class FundamentalReport(
    val conclusion: String
)

data class MarketSnapshot(
    val currentPrice: BigDecimal
)

data class BotSettings(
    val tradingEnabled: Boolean = true,
    val riskEnabled: Boolean = true,
    val maxPositionRub: Int = 500000,
    val maxDailyLossRub: Int = 50000
)

data class RiskCheckResult(
    val allowed: Boolean,
    val reason: String,
    val adjustedQty: Int
)
