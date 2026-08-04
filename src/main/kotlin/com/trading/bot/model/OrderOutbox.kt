package com.trading.bot.model

import java.time.LocalDateTime
import java.util.UUID

enum class OutboxStatus {
    PENDING,
    SENT,
    FAILED,
}

/**
 * Строка Outbox для гарантированной доставки ордеров в Alor.
 *
 * Алгоритм:
 * 1. Сохранить ордер в outbox (PENDING)
 * 2. Отправить в Alor
 * 3. Успех → markSent(outboxId, alorOrderId)
 * 4. Ошибка → markFailed(outboxId, error)
 *
 * Worker переотправляет PENDING строки старше 30 сек (например, после перезапуска приложения).
 */
data class OrderOutbox(
    val id: UUID? = null,
    val payloadJson: String,
    val status: OutboxStatus = OutboxStatus.PENDING,
    val alorOrderId: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val processedAt: LocalDateTime? = null,
    val errorMessage: String? = null,
)
