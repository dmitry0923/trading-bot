package com.trading.bot.repository

import com.trading.bot.model.OrderOutbox
import com.trading.bot.model.OutboxStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.UUID

@Repository
class OrderOutboxRepository(
    private val namedTemplate: NamedParameterJdbcTemplate
) {
    private val rowMapper = RowMapper { rs: ResultSet, _: Int ->
        OrderOutbox(
            id = UUID.fromString(rs.getString("id")),
            payloadJson = rs.getString("payload"),
            status = OutboxStatus.valueOf(rs.getString("status")),
            alorOrderId = rs.getString("alor_order_id"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            processedAt = rs.getTimestamp("processed_at")?.toLocalDateTime(),
            errorMessage = rs.getString("error_message")
        )
    }

    fun save(outbox: OrderOutbox): OrderOutbox {
        val id = outbox.id ?: UUID.randomUUID()
        val sql = """
            INSERT INTO order_outbox (id, payload, status, alor_order_id, created_at, processed_at, error_message)
            VALUES (:id, CAST(:payload AS jsonb), :status, :alorOrderId, :createdAt, :processedAt, :errorMessage)
        """.trimIndent()
        namedTemplate.update(sql, MapSqlParameterSource()
            .addValue("id", id)
            .addValue("payload", outbox.payloadJson)
            .addValue("status", outbox.status.name)
            .addValue("alorOrderId", outbox.alorOrderId)
            .addValue("createdAt", outbox.createdAt)
            .addValue("processedAt", outbox.processedAt)
            .addValue("errorMessage", outbox.errorMessage))
        return outbox.copy(id = id)
    }

    fun findPendingOlderThan(seconds: Int): List<OrderOutbox> {
        val sql = """
            SELECT * FROM order_outbox
            WHERE status = 'PENDING' AND created_at < :cutoff
            ORDER BY created_at ASC
            LIMIT 100
        """.trimIndent()
        return namedTemplate.query(sql, mapOf("cutoff" to LocalDateTime.now().minusSeconds(seconds.toLong())), rowMapper)
    }

    fun markSent(id: UUID, alorOrderId: String?) {
        namedTemplate.update(
            "UPDATE order_outbox SET status = 'SENT', alor_order_id = :oid, processed_at = :now, error_message = NULL WHERE id = :id",
            MapSqlParameterSource()
                .addValue("oid", alorOrderId)
                .addValue("now", LocalDateTime.now())
                .addValue("id", id)
        )
    }

    fun markFailed(id: UUID, error: String) {
        namedTemplate.update(
            "UPDATE order_outbox SET status = 'FAILED', processed_at = :now, error_message = :err WHERE id = :id",
            MapSqlParameterSource()
                .addValue("now", LocalDateTime.now())
                .addValue("err", error.take(2000))
                .addValue("id", id)
        )
    }
}
