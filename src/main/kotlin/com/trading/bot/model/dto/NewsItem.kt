package com.trading.bot.model.dto

import java.time.Instant

/**
 * Новость по эмитенту для фундаментального анализа (Agent-2).
 *
 * Стабильные поля для semantic fingerprint: только количество новостей и
 * временной бакет последней — иначе кэш аннулируется на каждой новости.
 */
data class NewsItem(
    val title: String,
    val url: String = "",
    val publishedAt: Instant? = null,
    val snippet: String = "",
)
