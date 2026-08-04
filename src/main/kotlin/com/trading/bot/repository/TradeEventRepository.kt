package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.TradeEvent
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

/**
 * Append-only репозиторий торговых событий (R2DBC).
 *
 * Только INSERT и SELECT — никаких UPDATE/DELETE. sequence_number
 * вычисляется в БД как MAX+1 внутри агрегата (гарантия монотонности).
 */
@Repository
class TradeEventRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toTradeEvent(row: Row): TradeEvent =
        TradeEvent(
            id = row.get("id", Long::class.javaObjectType),
            aggregateId = row.require("aggregate_id", UUID::class.java),
            eventType = row.require("event_type", String::class.java),
            payload = row.require("payload", String::class.java),
            occurredAt = row.require("occurred_at", LocalDateTime::class.java),
            sequenceNumber = row.require("sequence_number", Long::class.javaObjectType),
        )

    suspend fun append(event: TradeEvent) {
        val sql =
            """
            INSERT INTO trade_events (aggregate_id, event_type, payload, occurred_at, sequence_number)
            VALUES (
                :aggregateId,
                :eventType,
                :payload::jsonb,
                :occurredAt,
                (SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM trade_events WHERE aggregate_id = :aggregateId)
            )
            """.trimIndent()
        databaseClient
            .sql(sql)
            .bind("aggregateId", event.aggregateId)
            .bind("eventType", event.eventType)
            .bind("payload", event.payload)
            .bind("occurredAt", event.occurredAt)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun findByAggregateId(aggregateId: UUID): List<TradeEvent> {
        val sql = "SELECT * FROM trade_events WHERE aggregate_id = :aggregateId ORDER BY sequence_number"
        return databaseClient
            .sql(sql)
            .bind("aggregateId", aggregateId)
            .map { row, _ -> toTradeEvent(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun findRecent(limit: Int): List<TradeEvent> {
        val sql = "SELECT * FROM trade_events ORDER BY occurred_at DESC LIMIT :limit"
        return databaseClient
            .sql(sql)
            .bind("limit", limit)
            .map { row, _ -> toTradeEvent(row) }
            .all()
            .collectList()
            .awaitSingle()
    }
}
