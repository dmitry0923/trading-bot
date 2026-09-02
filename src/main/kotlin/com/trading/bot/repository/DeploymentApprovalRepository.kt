package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.DeploymentApprovalRecord
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * R2DBC-репозиторий per-ticker LIVE-одобрения (таблица deployment_approval).
 *
 * Читается на старте в кэш [com.trading.bot.service.DeploymentApprovalService];
 * на горячем пути входов БД НЕ трогается (только in-memory кэш).
 */
@Repository
class DeploymentApprovalRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toRecord(row: Row): DeploymentApprovalRecord =
        DeploymentApprovalRecord(
            ticker = row.require("ticker", String::class.java),
            status = row.require("status", String::class.java),
            frozenConfidenceThreshold = row.get("frozen_confidence_threshold", java.lang.Double::class.java)?.toDouble(),
            paramsHash = row.get("params_hash", String::class.java),
            approvedAt = row.require("approved_at", Instant::class.java),
        )

    suspend fun save(record: DeploymentApprovalRecord) {
        val sql =
            """
            INSERT INTO deployment_approval
                (ticker, status, frozen_confidence_threshold, params_hash, approved_at, updated_at)
            VALUES (:ticker, :status, :confidence, :paramsHash, :approvedAt, NOW())
            ON CONFLICT (ticker) DO UPDATE SET
                status = EXCLUDED.status,
                frozen_confidence_threshold = EXCLUDED.frozen_confidence_threshold,
                params_hash = EXCLUDED.params_hash,
                approved_at = EXCLUDED.approved_at,
                updated_at = NOW()
            """.trimIndent()
        val conf = record.frozenConfidenceThreshold
        val hash = record.paramsHash
        databaseClient
            .sql(sql)
            .bind("ticker", record.ticker)
            .bind("status", record.status)
            .let { spec -> if (conf != null) spec.bind("confidence", conf) else spec.bindNull("confidence", java.lang.Double::class.java) }
            .let { spec -> if (hash != null) spec.bind("paramsHash", hash) else spec.bindNull("paramsHash", String::class.java) }
            .bind("approvedAt", record.approvedAt)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun delete(ticker: String) {
        databaseClient
            .sql("DELETE FROM deployment_approval WHERE ticker = :ticker")
            .bind("ticker", ticker)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun latest(): List<DeploymentApprovalRecord> =
        databaseClient
            .sql("SELECT * FROM deployment_approval")
            .map { row, _ -> toRecord(row) }
            .all()
            .collectList()
            .awaitSingleOrNull()
            ?: emptyList()
}
