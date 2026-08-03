package com.trading.bot.model

import java.time.LocalDateTime

data class FeedbackCacheEntry(
    val ticker: String,
    val feedbackJson: String,
    val statsHash: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
