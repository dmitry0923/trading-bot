package com.trading.bot.repository

import com.trading.bot.infrastructure.db.bindOrNull
import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.OrderOutbox
import com.trading.bot.model.OutboxStatus
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

@Repository
class OrderOutboxRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toOrderOutbox(row: Row): OrderOutbox = OrderOutbox(
        id = row.get("id", UUID::class.java),
        payloadJson = row.require("payload", String::class.java),
        status = OutboxStatus.valueOf(row.require("status", String::class.java)),
        alorOrderId = row.get("alor_order_id", String::class.java),
        createdAt = row.require("created_at", LocalDateTime::class.java),
        processedAt = row.get("processed_at", LocalDateTime::class.java),
        errorMessage = row.get("error_message", String::class.java),
        attemptCount = row.require("attempt_count", Int::class.javaObjectType),
        nextAttemptAt = row.require("next_attempt_at", LocalDateTime::class.java),
    )

    suspend fun save(outbox: OrderOutbox): OrderOutbox {
        val id = outbox.id ?: UUID.randomUUID()
        val sql = """
            INSERT INTO order_outbox (
                id, payload, status, alor_order_id, created_at, processed_at,
                error_message, attempt_count, next_attempt_at
            ) VALUES (
                :id, CAST(:payload AS jsonb), :status, :alorOrderId, :createdAt, :processedAt,
                :errorMessage, :attemptCount, :nextAttemptAt
            )
        """.trimIndent()
        databaseClient.sql(sql)
            .bind("id", id)
            .bind("payload", outbox.payloadJson)
            .bind("status", outbox.status.name)
            .bindOrNull("alorOrderId", outbox.alorOrderId)
            .bind("createdAt", outbox.createdAt)
            .bindOrNull("processedAt", outbox.processedAt)
            .bindOrNull("errorMessage", outbox.errorMessage)
            .bind("attemptCount", outbox.attemptCount)
            .bind("nextAttemptAt", outbox.nextAttemptAt)
            .then()
            .awaitSingleOrNull()
        return outbox.copy(id = id)
    }

    /** Атомарно захватывает только что созданный ордер для немедленной отправки. */
    suspend fun claim(id: UUID): OrderOutbox? {
        val sql = """
            UPDATE order_outbox
            SET status = 'PROCESSING', processed_at = :now
            WHERE id = :id AND status = 'PENDING'
            RETURNING *
        """.trimIndent()
        return databaseClient.sql(sql)
            .bind("now", LocalDateTime.now())
            .bind("id", id)
            .map { row, _ -> toOrderOutbox(row) }
            .one()
            .awaitSingleOrNull()
    }

    /**
     * Атомарно захватывает незавершённые записи старше пяти минут.
     * Они требуют карантина и ручной сверки с брокером после падения процесса.
     */
    suspend fun claimReady(limit: Int = 100): List<OrderOutbox> {
        val now = LocalDateTime.now()
        val sql = """
            WITH candidates AS (
                SELECT id
                FROM order_outbox
                WHERE (status = 'PENDING' AND next_attempt_at <= :now AND created_at < :staleBefore)
                   OR (status = 'PROCESSING' AND processed_at < :staleBefore)
                ORDER BY next_attempt_at ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE order_outbox AS outbox
            SET status = 'PROCESSING', processed_at = :now
            FROM candidates
            WHERE outbox.id = candidates.id
            RETURNING outbox.*
        """.trimIndent()
        return databaseClient.sql(sql)
            .bind("now", now)
            .bind("staleBefore", now.minusMinutes(5))
            .bind("limit", limit.coerceIn(1, 500))
            .map { row, _ -> toOrderOutbox(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun markSent(id: UUID, alorOrderId: String) {
        databaseClient.sql(
            """
            UPDATE order_outbox
            SET status = 'SENT', alor_order_id = :oid, processed_at = :now, error_message = NULL
            WHERE id = :id AND status = 'PROCESSING'
            """.trimIndent(),
        )
            .bind("oid", alorOrderId)
            .bind("now", LocalDateTime.now())
            .bind("id", id)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun markFailed(id: UUID, attemptCount: Int, error: String) {
        databaseClient.sql(
            """
            UPDATE order_outbox
            SET status = 'FAILED', attempt_count = :attemptCount, processed_at = :now, error_message = :error
            WHERE id = :id AND status = 'PROCESSING'
            """.trimIndent(),
        )
            .bind("attemptCount", attemptCount)
            .bind("now", LocalDateTime.now())
            .bind("error", error.take(MAX_ERROR_LENGTH))
            .bind("id", id)
            .then()
            .awaitSingleOrNull()
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 2_000
    }
}
