package com.trading.bot.model

import java.math.BigDecimal
import java.time.LocalDateTime

data class TradeSignal(
    val ticker: String,
    val action: SignalAction,
    val targetPrice: BigDecimal,
    val quantity: Int,
    val stopLoss: BigDecimal? = null,
    val takeProfit: BigDecimal? = null,
    val trailingStop: Boolean = false,
    val confidence: Double = 0.0,
    val reasoning: String = "",
    val timestamp: LocalDateTime = LocalDateTime.now()
)

enum class SignalAction { BUY, SELL, HOLD, ADJUST_STOPS }
