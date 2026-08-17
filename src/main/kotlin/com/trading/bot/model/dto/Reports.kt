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
    val signalStrength: Double = 0.0,
    val reasoning: String = "",
)

data class FundamentalReport(
    val conclusion: String,
    val signalStrength: Double = 0.0,
    val reasoning: String = "",
)

data class MarketSnapshot(
    val ticker: String = "",
    val currentPrice: BigDecimal,
    val bid: BigDecimal? = null,
    val ask: BigDecimal? = null,
    val volume: Long? = null,
    val bidSize: Long? = null,
    val askSize: Long? = null,
    val microprice: BigDecimal? = null,
    val obi: BigDecimal? = null,
    val timestamp: Instant = Instant.now(),
)
