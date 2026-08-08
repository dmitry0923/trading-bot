package com.trading.bot.model.dto

import java.time.LocalDateTime

/**
 * Кэшированная запись обратной связи Meta-Agent'а (Redis/БД).
 *
 * @param ticker тикер инструмента
 * @param feedbackJson JSON-ответ feedback (StrategyFeedback.rawJson)
 * @param statsHash хеш торговой статистики, по которому определяется актуальность
 * @param createdAt время создания записи
 */
data class FeedbackCacheEntry(
    val ticker: String,
    val feedbackJson: String,
    val statsHash: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
