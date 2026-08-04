package com.trading.bot.repository

import com.trading.bot.model.TradeEvent
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/**
 * Append-only репозиторий торговых событий.
 *
 * Только INSERT и SELECT — никаких UPDATE/DELETE. sequence_number
 * вычисляется в БД как MAX+1 внутри агрегата (гарантия монотонности).
 */
@Repository
class TradeEventRepository(
    private val namedTemplate: NamedParameterJdbcTemplate
) {
    private val rowMapper = RowMapper { rs: ResultSet, _: Int ->
        TradeEvent(
            id = rs.getLong("id"),
            aggregateId = rs.getObject("aggregate_id", java.util.UUID::class.java),
            eventType = rs.getString("event_type"),
            payload = rs.getString("payload"),
            occurredAt = rs.getTimestamp("occurred_at").toLocalDateTime(),
            sequenceNumber = rs.getLong("sequence_number")
        )
    }

    fun append(event: TradeEvent) {
        val sql = """
            INSERT INTO trade_events (aggregate_id, event_type, payload, occurred_at, sequence_number)
            VALUES (
                :aggregateId,
                :eventType,
                :payload::jsonb,
                :occurredAt,
                (SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM trade_events WHERE aggregate_id = :aggregateId)
            )
        """.trimIndent()
        namedTemplate.update(
            sql,
            mapOf(
                "aggregateId" to event.aggregateId,
                "eventType" to event.eventType,
                "payload" to event.payload,
                "occurredAt" to event.occurredAt
            )
        )
    }

    fun findByAggregateId(aggregateId: UUID): List<TradeEvent> {
        val sql = "SELECT * FROM trade_events WHERE aggregate_id = :aggregateId ORDER BY sequence_number"
        return namedTemplate.query(sql, mapOf("aggregateId" to aggregateId), rowMapper)
    }

    fun findRecent(limit: Int): List<TradeEvent> {
        val sql = "SELECT * FROM trade_events ORDER BY occurred_at DESC LIMIT :limit"
        return namedTemplate.query(sql, mapOf("limit" to limit), rowMapper)
    }
}
