package com.trading.bot.model.entity

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
 * 1. Сохранить ордер в outbox (PENDING), сгенерировав [idempotencyKey] ОДИН раз на логический ордер.
 * 2. Отправить в Alor (тот же [idempotencyKey] используется при всех повторных попытках —
 *    Alor дедуплицирует по "id").
 * 3. Успех → markSent(outboxId, alorOrderId)
 * 4. Ошибка → markFailed(outboxId, error), retry_count += 1
 *
 * Worker переотправляет PENDING-строки старше 30 сек и FAILED-строки с
 * retry_count < maxRetries. Перед любой повторной отправкой выполняется
 * State Reconciliation (поиск ордера на бирже по [idempotencyKey]).
 *
 * [positionId] связывает ордер с позицией — для сверки и стейт-машины входов/закрытий.
 */
data class OrderOutbox(
    val id: UUID? = null,
    val payloadJson: String,
    val status: OutboxStatus = OutboxStatus.PENDING,
    val alorOrderId: String? = null,
    val idempotencyKey: String? = null,
    val retryCount: Int = 0,
    val positionId: Long? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val processedAt: LocalDateTime? = null,
    val errorMessage: String? = null,
)
