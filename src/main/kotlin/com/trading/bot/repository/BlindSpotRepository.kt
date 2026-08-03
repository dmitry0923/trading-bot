package com.trading.bot.repository

import com.trading.bot.model.BlindSpotEntity
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class BlindSpotRepository(
    private val namedTemplate: NamedParameterJdbcTemplate,
) {
    private val rowMapper = RowMapper { rs: ResultSet, _: Int ->
        BlindSpotEntity(
            id = rs.getLong("id"),
            ticker = rs.getString("ticker"),
            conditionPattern = rs.getString("condition_pattern"),
            lossRate = rs.getDouble("loss_rate"),
            occurrenceCount = rs.getInt("occurrence_count"),
            recommendation = rs.getString("recommendation"),
            isActive = rs.getBoolean("is_active"),
            detectedAt = rs.getTimestamp("detected_at").toLocalDateTime(),
            resolvedAt = rs.getTimestamp("resolved_at")?.toLocalDateTime()
        )
    }

    fun findByIsActiveTrue(): List<BlindSpotEntity> {
        return namedTemplate.query("SELECT * FROM blind_spots WHERE is_active = true", rowMapper)
    }

    fun findByTickerAndIsActiveTrue(ticker: String): List<BlindSpotEntity> {
        val sql = "SELECT * FROM blind_spots WHERE ticker = :ticker AND is_active = true"
        return namedTemplate.query(sql, mapOf("ticker" to ticker), rowMapper)
    }

    fun save(entity: BlindSpotEntity): BlindSpotEntity {
        return if (entity.id == null) {
            insert(entity)
        } else {
            update(entity)
            entity
        }
    }

    private fun insert(entity: BlindSpotEntity): BlindSpotEntity {
        val sql = """
            INSERT INTO blind_spots (ticker, condition_pattern, loss_rate, occurrence_count, recommendation, is_active, detected_at, resolved_at)
            VALUES (:ticker, :conditionPattern, :lossRate, :occurrenceCount, :recommendation, :isActive, :detectedAt, :resolvedAt)
        """.trimIndent()
        val keyHolder = GeneratedKeyHolder()
        namedTemplate.update(sql, createParams(entity), keyHolder)
        return entity.copy(id = keyHolder.key?.toLong())
    }

    private fun update(entity: BlindSpotEntity) {
        val sql = """
            UPDATE blind_spots SET
                ticker = :ticker, condition_pattern = :conditionPattern, loss_rate = :lossRate,
                occurrence_count = :occurrenceCount, recommendation = :recommendation,
                is_active = :isActive, detected_at = :detectedAt, resolved_at = :resolvedAt
            WHERE id = :id
        """.trimIndent()
        namedTemplate.update(sql, createParams(entity).addValue("id", entity.id))
    }

    fun deleteAll() {
        namedTemplate.update("DELETE FROM blind_spots", emptyMap<String, Any>())
    }

    private fun createParams(entity: BlindSpotEntity): MapSqlParameterSource {
        return MapSqlParameterSource()
            .addValue("ticker", entity.ticker)
            .addValue("conditionPattern", entity.conditionPattern)
            .addValue("lossRate", entity.lossRate)
            .addValue("occurrenceCount", entity.occurrenceCount)
            .addValue("recommendation", entity.recommendation)
            .addValue("isActive", entity.isActive)
            .addValue("detectedAt", entity.detectedAt)
            .addValue("resolvedAt", entity.resolvedAt)
    }
}
