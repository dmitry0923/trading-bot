package com.trading.bot.repository

import com.trading.bot.infrastructure.UuidV7
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
    private fun toOrderOutbox(row: Row): OrderOutbox =
        OrderOutbox(
            id = row.get("id", UUID::class.java),
            payloadJson = row.require("payload", String::class.java),
            status = OutboxStatus.valueOf(row.require("status", String::class.java)),
            alorOrderId = row.get("alor_order_id", String::class.java),
            idempotencyKey = row.get("idempotency_key", String::class.java),
            retryCount = row.get("retry_count", Int::class.javaObjectType) ?: 0,
            positionId = row.get("position_id", Long::class.javaObjectType),
            createdAt = row.require("created_at", LocalDateTime::class.java),
            processedAt = row.get("processed_at", LocalDateTime::class.java),
            errorMessage = row.get("error_message", String::class.java),
        )

    suspend fun save(outbox: OrderOutbox): OrderOutbox {
        val id = outbox.id ?: UuidV7.uuid()
        val sql =
            """
            INSERT INTO order_outbox (id, payload, status, alor_order_id, idempotency_key, retry_count,
                position_id, created_at, processed_at, error_message)
            VALUES (:id, CAST(:payload AS jsonb), :status, :alorOrderId, :idempotencyKey, :retryCount,
                :positionId, :createdAt, :processedAt, :errorMessage)
            """.trimIndent()
        databaseClient
            .sql(sql)
            .bind("id", id)
            .bind("payload", outbox.payloadJson)
            .bind("status", outbox.status.name)
            .bindOrNull("alorOrderId", outbox.alorOrderId)
            .bindOrNull("idempotencyKey", outbox.idempotencyKey)
            .bind("retryCount", outbox.retryCount)
            .bindOrNull("positionId", outbox.positionId)
            .bind("createdAt", outbox.createdAt)
            .bindOrNull("processedAt", outbox.processedAt)
            .bindOrNull("errorMessage", outbox.errorMessage)
            .then()
            .awaitSingleOrNull()
        return outbox.copy(id = id)
    }

    /**
     * Строки для (повторной) доставки:
     * - PENDING старше cutoff (краш между save и dispatch, либо после рестарта);
     * - FAILED с retry_count < maxRetries и последней попыткой старше cutoff.
     */
    suspend fun findRetryable(
        maxRetries: Int,
        olderThanSeconds: Int = 30,
    ): List<OrderOutbox> {
        val sql =
            """
            SELECT * FROM order_outbox
            WHERE
                (status = 'PENDING' AND created_at < :cutoff) OR
                (status = 'FAILED' AND retry_count < :maxRetries AND COALESCE(processed_at, created_at) < :cutoff)
            ORDER BY created_at ASC
            LIMIT 100
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("cutoff", LocalDateTime.now().minusSeconds(olderThanSeconds.toLong()))
            .bind("maxRetries", maxRetries)
            .map { row, _ -> toOrderOutbox(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /**
     * Последняя outbox-запись для позиции (для сверки входов/закрытий).
     */
    suspend fun findLatestByPositionId(positionId: Long): OrderOutbox? {
        val sql =
            """
            SELECT * FROM order_outbox
            WHERE payload->>'positionId' = :positionId
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("positionId", positionId.toString())
            .map { row, _ -> toOrderOutbox(row) }
            .one()
            .awaitSingleOrNull()
    }

    suspend fun markSent(
        id: UUID,
        alorOrderId: String?,
    ) {
        databaseClient
            .sql(
                "UPDATE order_outbox SET status = 'SENT', alor_order_id = :oid, processed_at = :now, error_message = NULL WHERE id = :id",
            ).bindOrNull("oid", alorOrderId)
            .bind("now", LocalDateTime.now())
            .bind("id", id)
            .then()
            .awaitSingleOrNull()
    }

    /**
     * Помечает доставку как неудачную и инкрементирует retry_count.
     * Одна и та же строка может переотправляться (с тем же idempotencyKey),
     * но не более maxRetries раз — см. [findRetryable].
     */
    suspend fun markFailed(
        id: UUID,
        error: String,
    ) {
        databaseClient
            .sql(
                "UPDATE order_outbox SET status = 'FAILED', processed_at = :now, error_message = :err, retry_count = retry_count + 1 WHERE id = :id",
            ).bind("now", LocalDateTime.now())
            .bind("err", error.take(2000))
            .bind("id", id)
            .then()
            .awaitSingleOrNull()
    }
}
