package com.trading.bot.repository

import com.trading.bot.model.StrategyAdjustment
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class StrategyAdjustmentRepository(
    private val namedTemplate: NamedParameterJdbcTemplate,
) {
    private val rowMapper = RowMapper { rs: ResultSet, _: Int ->
        StrategyAdjustment(
            id = rs.getLong("id"),
            ticker = rs.getString("ticker"),
            adjustmentType = rs.getString("adjustment_type"),
            oldValue = rs.getBigDecimal("old_value"),
            newValue = rs.getBigDecimal("new_value"),
            triggeredBy = rs.getString("triggered_by"),
            reason = rs.getString("reason"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun findByTickerOrderByCreatedAtDesc(ticker: String): List<StrategyAdjustment> {
        val sql = "SELECT * FROM strategy_adjustments WHERE ticker = :ticker ORDER BY created_at DESC"
        return namedTemplate.query(sql, mapOf("ticker" to ticker), rowMapper)
    }

    fun findByTickerAndAdjustmentTypeOrderByCreatedAtDesc(ticker: String, type: String): List<StrategyAdjustment> {
        val sql = """
            SELECT * FROM strategy_adjustments
            WHERE ticker = :ticker AND adjustment_type = :type
            ORDER BY created_at DESC
        """.trimIndent()
        return namedTemplate.query(sql, mapOf("ticker" to ticker, "type" to type), rowMapper)
    }

    fun findAll(): List<StrategyAdjustment> {
        return namedTemplate.query("SELECT * FROM strategy_adjustments ORDER BY created_at DESC", rowMapper)
    }

    fun save(entity: StrategyAdjustment): StrategyAdjustment {
        return if (entity.id == null) {
            insert(entity)
        } else {
            update(entity)
            entity
        }
    }

    private fun insert(entity: StrategyAdjustment): StrategyAdjustment {
        val sql = """
            INSERT INTO strategy_adjustments (ticker, adjustment_type, old_value, new_value, triggered_by, reason, created_at)
            VALUES (:ticker, :adjustmentType, :oldValue, :newValue, :triggeredBy, :reason, :createdAt)
            RETURNING id
        """.trimIndent()
        val keyHolder = GeneratedKeyHolder()
        namedTemplate.update(sql, createParams(entity), keyHolder)
        return entity.copy(id = keyHolder.key?.toLong())
    }

    private fun update(entity: StrategyAdjustment) {
        val sql = """
            UPDATE strategy_adjustments SET
                ticker = :ticker, adjustment_type = :adjustmentType, old_value = :oldValue,
                new_value = :newValue, triggered_by = :triggeredBy, reason = :reason, created_at = :createdAt
            WHERE id = :id
        """.trimIndent()
        namedTemplate.update(sql, createParams(entity).addValue("id", entity.id))
    }

    fun deleteAll() {
        namedTemplate.update("DELETE FROM strategy_adjustments", emptyMap<String, Any>())
    }

    private fun createParams(entity: StrategyAdjustment): MapSqlParameterSource {
        return MapSqlParameterSource()
            .addValue("ticker", entity.ticker)
            .addValue("adjustmentType", entity.adjustmentType)
            .addValue("oldValue", entity.oldValue)
            .addValue("newValue", entity.newValue)
            .addValue("triggeredBy", entity.triggeredBy)
            .addValue("reason", entity.reason)
            .addValue("createdAt", entity.createdAt)
    }
}
