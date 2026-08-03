package com.trading.bot.model

import java.math.BigDecimal
import java.time.LocalDateTime

data class StrategyAdjustment(
    val id: Long? = null,
    val ticker: String,
    val adjustmentType: String,
    val oldValue: BigDecimal? = null,
    val newValue: BigDecimal? = null,
    val triggeredBy: String,
    val reason: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
