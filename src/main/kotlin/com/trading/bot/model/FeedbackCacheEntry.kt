package com.trading.bot.model

import java.time.LocalDateTime

/**
 * Кэш-запись feedback от Meta-Agent.
 */
data class FeedbackCacheEntry(
    val ticker: String,
    val feedbackJson: String,
    val statsHash: String, // хеш статистики, при изменении — invalidate
    val createdAt: LocalDateTime = LocalDateTime.now()
)
