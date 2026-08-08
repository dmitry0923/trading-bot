package com.trading.bot.model.dto

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

data class RiskCheckResult(
    val allowed: Boolean,
    val reason: String,
    val adjustedQty: Int,
)
