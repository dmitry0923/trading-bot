package com.trading.bot.model.entity

import com.trading.bot.model.BusinessTime
import java.time.LocalDateTime

data class BlindSpotEntity(
    val id: Long? = null,
    val ticker: String,
    val conditionPattern: String,
    val lossRate: Double,
    var occurrenceCount: Int,
    val recommendation: String,
    var isActive: Boolean = true,
    val detectedAt: LocalDateTime = BusinessTime.now(),
    var resolvedAt: LocalDateTime? = null,
)
