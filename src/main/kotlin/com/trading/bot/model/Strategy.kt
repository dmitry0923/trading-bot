package com.trading.bot.model

import java.math.BigDecimal
import java.time.LocalDateTime

data class Strategy(
    val id: Long? = null,
    val ticker: String,
    val action: StrategyAction,
    val targetPrice: BigDecimal,
    val quantity: Int,
    val stopLoss: BigDecimal? = null,
    val takeProfit: BigDecimal? = null,
    val trailingStop: Boolean = false,
    val confidence: Double,
    val reasoning: String,
    val rawJson: String? = null,
    val cycleId: String,
    val validUntil: LocalDateTime,
    val timeframe: String = "MINUTE_10",
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
