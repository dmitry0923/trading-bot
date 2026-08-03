package com.trading.bot.model

import java.math.BigDecimal
import java.time.LocalDateTime

data class MarketSnapshot(
    val ticker: String,
    val currentPrice: BigDecimal,
    val bid: BigDecimal,
    val ask: BigDecimal,
    val spread: BigDecimal,
    val volume: Long,
    val timestamp: LocalDateTime = LocalDateTime.now()
)
