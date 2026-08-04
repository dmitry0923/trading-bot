package com.trading.bot.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Неизменяемое событие торгового решения (таблица trade_events).
 *
 * - aggregateId — идентификатор агрегата (например, UUID от position.id)
 * - eventType   — POSITION_OPENED | POSITION_UPDATED | POSITION_CLOSED
 * - payload     — JSON-снимок позиции в момент события
 * - sequenceNumber — порядковый номер внутри агрегата (1, 2, 3, ...)
 */
data class TradeEvent(
    val id: Long? = null,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val occurredAt: LocalDateTime,
    val sequenceNumber: Long,
)
