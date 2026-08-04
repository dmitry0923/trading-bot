package com.trading.bot.model

import java.time.LocalDateTime
import java.util.UUID

enum class OutboxStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED,
}

/**
 * Строка transactional outbox для доставки ордера в Alor.
 *
 * Отправитель атомарно переводит запись PENDING -> PROCESSING. Один и тот же
 * idempotencyKey используется для сетевых retry внутри AlorClient. После ошибки
 * или неопределённого состояния запись переводится в FAILED для сверки.
 */
data class OrderOutbox(
    val id: UUID? = null,
    val payloadJson: String,
    val status: OutboxStatus = OutboxStatus.PENDING,
    val alorOrderId: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val processedAt: LocalDateTime? = null,
    val errorMessage: String? = null,
    val attemptCount: Int = 0,
    val nextAttemptAt: LocalDateTime = LocalDateTime.now(),
)
