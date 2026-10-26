package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.TradeEvent
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

/**
 * Append-only репозиторий торговых событий (R2DBC).
 *
 * Только INSERT и SELECT — никаких UPDATE/DELETE. sequence_number
 * вычисляется в БД как MAX+1 внутри агрегата (гарантия монотонности), а
 * уникальность `(aggregate_id, sequence_number)` держит уникальный индекс
 * (Liquibase 041). Конкурентный append, проигравший гонку, получает unique
 * violation и повторяет вставку с пересчитанным MAX+1 — иначе два события
 * одного агрегата делили бы номер и порядок lineage становился случайным.
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
        var attempt = 0
        while (true) {
            attempt++
            try {
                insertWithNextSequence(event)
                return
            } catch (e: DataIntegrityViolationException) {
                // Гонка MAX+1: номер уже занят другим append этого агрегата.
                // Пересчитываем MAX+1 и пробуем снова (номер пересчитывается в БД).
                if (attempt >= MAX_APPEND_ATTEMPTS) throw e
            }
        }
    }

    private suspend fun insertWithNextSequence(event: TradeEvent) {
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

    /**
     * События нескольких агрегатов в хронологическом порядке (lineage): все
     * позиции цикла одним запросом. Используется реконструкцией по cycleId
     * ([com.trading.bot.service.LineageService]).
     */
    suspend fun findByAggregateIds(aggregateIds: Collection<UUID>): List<TradeEvent> {
        val ids = aggregateIds.distinct()
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.indices.joinToString(",") { ":id$it" }
        var spec =
            databaseClient
                .sql(
                    "SELECT * FROM trade_events WHERE aggregate_id IN ($placeholders) " +
                        "ORDER BY occurred_at ASC",
                )
        ids.forEachIndexed { i, id -> spec = spec.bind("id$i", id) }
        return spec
            .map { row, _ -> toTradeEvent(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    private companion object {
        /** Попытки вставки при гонке за sequence_number (unique violation). */
        private const val MAX_APPEND_ATTEMPTS = 5
    }
}
