package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.FrozenStrategyRecord
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * R2DBC-репозиторий per-ticker замороженной стратегии (таблица frozen_strategy).
 *
 * Только UPSERT ([save]) и чтение ([latest]); физическое удаление строки выполняется
 * через DELETE только осознанно при отзыве (revoke) — см. [FrozenStrategyStore].
 */
@Repository
class FrozenStrategyRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toRecord(row: Row): FrozenStrategyRecord =
        FrozenStrategyRecord(
            ticker = row.require("ticker", String::class.java),
            slPercent = row.get("sl_percent", java.lang.Double::class.java)?.toDouble(),
            tpPercent = row.get("tp_percent", java.lang.Double::class.java)?.toDouble(),
            slPoints = row.get("sl_points", java.lang.Integer::class.java)?.toInt(),
            tpPoints = row.get("tp_points", java.lang.Integer::class.java)?.toInt(),
            confidenceThreshold = row.get("confidence_threshold", java.lang.Double::class.java)?.toDouble(),
            leverage = row.require("leverage", java.lang.Double::class.java).toDouble(),
            riskPerTradePercent = row.get("risk_per_trade_percent", java.lang.Double::class.java)?.toDouble(),
            futuresMaxContractsPerPosition = row.get("max_contracts_per_position", java.lang.Integer::class.java)?.toInt(),
            strategyVersion = row.require("strategy_version", String::class.java),
            gitCommitSha = row.get("git_commit_sha", String::class.java),
            updatedAt = row.require("updated_at", Instant::class.java),
        )

    suspend fun save(record: FrozenStrategyRecord) {
        val sql =
            """
            INSERT INTO frozen_strategy
                (ticker, sl_percent, tp_percent, sl_points, tp_points, confidence_threshold,
                 leverage, risk_per_trade_percent, max_contracts_per_position, strategy_version,
                 git_commit_sha, updated_at)
            VALUES (:ticker, :slPercent, :tpPercent, :slPoints, :tpPoints, :confidence,
                    :leverage, :riskPerTrade, :maxContracts, :strategyVersion,
                    :gitCommitSha, NOW())
            ON CONFLICT (ticker) DO UPDATE SET
                sl_percent = EXCLUDED.sl_percent,
                tp_percent = EXCLUDED.tp_percent,
                sl_points = EXCLUDED.sl_points,
                tp_points = EXCLUDED.tp_points,
                confidence_threshold = EXCLUDED.confidence_threshold,
                leverage = EXCLUDED.leverage,
                risk_per_trade_percent = EXCLUDED.risk_per_trade_percent,
                max_contracts_per_position = EXCLUDED.max_contracts_per_position,
                strategy_version = EXCLUDED.strategy_version,
                git_commit_sha = EXCLUDED.git_commit_sha,
                updated_at = NOW()
            """.trimIndent()
        databaseClient
            .sql(sql)
            .bind("ticker", record.ticker)
            .bindNullable("slPercent", record.slPercent, java.lang.Double::class.java)
            .bindNullable("tpPercent", record.tpPercent, java.lang.Double::class.java)
            .bindNullable("slPoints", record.slPoints, java.lang.Integer::class.java)
            .bindNullable("tpPoints", record.tpPoints, java.lang.Integer::class.java)
            .bindNullable("confidence", record.confidenceThreshold, java.lang.Double::class.java)
            .bind("leverage", record.leverage)
            .bindNullable("riskPerTrade", record.riskPerTradePercent, java.lang.Double::class.java)
            .bindNullable("maxContracts", record.futuresMaxContractsPerPosition, java.lang.Integer::class.java)
            .bind("strategyVersion", record.strategyVersion)
            .bindNullable("gitCommitSha", record.gitCommitSha, String::class.java)
            .then()
            .awaitSingleOrNull()
    }

    /** bind с поддержкой null (bindNull без null-check на каждый вызов). */
    private fun DatabaseClient.GenericExecuteSpec.bindNullable(
        name: String,
        value: Any?,
        type: Class<*>,
    ): DatabaseClient.GenericExecuteSpec = if (value != null) bind(name, value) else bindNull(name, type)

    suspend fun latest(): List<FrozenStrategyRecord> =
        databaseClient
            .sql("SELECT * FROM frozen_strategy")
            .map { row, _ -> toRecord(row) }
            .all()
            .collectList()
            .awaitSingleOrNull()
            ?: emptyList()

    suspend fun find(ticker: String): FrozenStrategyRecord? =
        databaseClient
            .sql("SELECT * FROM frozen_strategy WHERE ticker = :ticker")
            .bind("ticker", ticker)
            .map { row, _ -> toRecord(row) }
            .one()
            .awaitSingleOrNull()

    suspend fun delete(ticker: String) {
        databaseClient
            .sql("DELETE FROM frozen_strategy WHERE ticker = :ticker")
            .bind("ticker", ticker)
            .then()
            .awaitSingleOrNull()
    }
}
