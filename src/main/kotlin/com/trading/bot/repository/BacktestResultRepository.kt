package com.trading.bot.repository

import com.trading.bot.infrastructure.db.bindOrNull
import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.BacktestResultEntity
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * Репозиторий истории бэктест-прогонов (roadmap v2.2, раздел 13.7.3).
 *
 * Только INSERT и SELECT — результаты append-only, сравнение итераций через
 * [findRecent]. JSON-колонки (params/metrics/oos) читаются из R2DBC как String.
 */
@Repository
class BacktestResultRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toEntity(row: Row): BacktestResultEntity =
        BacktestResultEntity(
            id = row.get("id", Long::class.javaObjectType),
            ticker = row.require("ticker", String::class.java),
            params = row.require("params", String::class.java),
            metrics = row.require("metrics", String::class.java),
            oos = row.get("oos", String::class.java),
            createdAt = row.require("created_at", LocalDateTime::class.java),
        )

    suspend fun save(record: BacktestResultEntity): BacktestResultEntity {
        val sql =
            """
            INSERT INTO backtest_results (ticker, params, metrics, oos, created_at)
            VALUES (:ticker, CAST(:params AS jsonb), CAST(:metrics AS jsonb), CAST(:oos AS jsonb), :createdAt)
            """.trimIndent()
        databaseClient
            .sql(sql)
            .bind("ticker", record.ticker)
            .bind("params", record.params)
            .bind("metrics", record.metrics)
            .bindOrNull("oos", record.oos)
            .bind("createdAt", record.createdAt)
            .then()
            .awaitSingleOrNull()
        return record
    }

    /**
     * Последние [limit] прогонов по тикеру (по убыванию времени) — сравнение итераций.
     */
    suspend fun findRecent(
        ticker: String,
        limit: Int,
    ): List<BacktestResultEntity> {
        val sql =
            """
            SELECT * FROM backtest_results
            WHERE ticker = :ticker
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .bind("limit", limit)
            .map { row, _ -> toEntity(row) }
            .all()
            .collectList()
            .awaitSingle()
    }
}
