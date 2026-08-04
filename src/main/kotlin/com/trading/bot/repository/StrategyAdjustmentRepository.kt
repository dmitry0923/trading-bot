package com.trading.bot.repository

import com.trading.bot.infrastructure.db.bindOrNull
import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.StrategyAdjustment
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

@Repository
class StrategyAdjustmentRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toStrategyAdjustment(row: Row): StrategyAdjustment =
        StrategyAdjustment(
            id = row.get("id", Long::class.javaObjectType),
            ticker = row.require("ticker", String::class.java),
            adjustmentType = row.require("adjustment_type", String::class.java),
            oldValue = row.get("old_value", BigDecimal::class.java),
            newValue = row.get("new_value", BigDecimal::class.java),
            triggeredBy = row.require("triggered_by", String::class.java),
            reason = row.require("reason", String::class.java),
            createdAt = row.require("created_at", LocalDateTime::class.java),
        )

    suspend fun findByTickerOrderByCreatedAtDesc(ticker: String): List<StrategyAdjustment> {
        val sql = "SELECT * FROM strategy_adjustments WHERE ticker = :ticker ORDER BY created_at DESC"
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .map { row, _ -> toStrategyAdjustment(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun findByTickerAndAdjustmentTypeOrderByCreatedAtDesc(
        ticker: String,
        type: String,
    ): List<StrategyAdjustment> {
        val sql =
            """
            SELECT * FROM strategy_adjustments
            WHERE ticker = :ticker AND adjustment_type = :type
            ORDER BY created_at DESC
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .bind("type", type)
            .map { row, _ -> toStrategyAdjustment(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun findAll(): List<StrategyAdjustment> =
        databaseClient
            .sql("SELECT * FROM strategy_adjustments ORDER BY created_at DESC")
            .map { row, _ -> toStrategyAdjustment(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun save(entity: StrategyAdjustment): StrategyAdjustment =
        if (entity.id == null) {
            insert(entity)
        } else {
            update(entity)
            entity
        }

    private suspend fun insert(entity: StrategyAdjustment): StrategyAdjustment {
        val sql =
            """
            INSERT INTO strategy_adjustments (ticker, adjustment_type, old_value, new_value, triggered_by, reason, created_at)
            VALUES (:ticker, :adjustmentType, :oldValue, :newValue, :triggeredBy, :reason, :createdAt)
            RETURNING id
            """.trimIndent()
        val id =
            databaseClient
                .sql(sql)
                .bind("ticker", entity.ticker)
                .bind("adjustmentType", entity.adjustmentType)
                .bindOrNull("oldValue", entity.oldValue)
                .bindOrNull("newValue", entity.newValue)
                .bind("triggeredBy", entity.triggeredBy)
                .bind("reason", entity.reason)
                .bind("createdAt", entity.createdAt)
                .map { row, _ -> row.get("id", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        return entity.copy(id = id)
    }

    private suspend fun update(entity: StrategyAdjustment) {
        val sql =
            """
            UPDATE strategy_adjustments SET
                ticker = :ticker, adjustment_type = :adjustmentType, old_value = :oldValue,
                new_value = :newValue, triggered_by = :triggeredBy, reason = :reason, created_at = :createdAt
            WHERE id = :id
            """.trimIndent()
        databaseClient
            .sql(sql)
            .bind("ticker", entity.ticker)
            .bind("adjustmentType", entity.adjustmentType)
            .bindOrNull("oldValue", entity.oldValue)
            .bindOrNull("newValue", entity.newValue)
            .bind("triggeredBy", entity.triggeredBy)
            .bind("reason", entity.reason)
            .bind("createdAt", entity.createdAt)
            .bind("id", entity.id!!)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun deleteAll() {
        databaseClient.sql("DELETE FROM strategy_adjustments").then().awaitSingleOrNull()
    }
}
