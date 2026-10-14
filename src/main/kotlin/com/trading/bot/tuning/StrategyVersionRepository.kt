package com.trading.bot.tuning

import com.trading.bot.infrastructure.db.bindOrNull
import com.trading.bot.infrastructure.db.require
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * Версия стратегии, задеплоенная по итогам Monthly Tuning (docs/24, Фаза 3.5).
 *
 * @property id идентификатор в БД (BIGSERIAL).
 * @property version период деплоя: YYYY-MM (например, '2026-10').
 * @property parameters JSONB: сериализованные параметры стратегии.
 * @property gateStatus итог gate check: PASS / WARN / ALERT.
 * @property metrics JSONB: метрики месяца (PF, DD, WinRate, Sharpe).
 * @property deployedAt время деплоя версии.
 * @property rolledBack true — версия откатана, больше не активна.
 * @property notes произвольные заметки (причина отката, комментарии).
 */
data class StrategyVersion(
    val id: Long? = null,
    val version: String,
    val parameters: String, // JSONB сериализован в String (Jackson)
    val gateStatus: String, // PASS / WARN / ALERT (колонка gate_status)
    val metrics: String, // JSONB сериализован в String (Jackson)
    val deployedAt: OffsetDateTime = OffsetDateTime.now(),
    val rolledBack: Boolean = false,
    val notes: String? = null,
)

/**
 * R2DBC-репозиторий версий стратегии (таблица `strategy_versions`).
 *
 * Написан на [DatabaseClient], как остальные репозитории проекта: Spring Data
 * репозитории в этом модуле не подключены, поэтому `CrudRepository`-интерфейс
 * не регистрировался бы как бин.
 */
@Repository
class StrategyVersionRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toVersion(row: Row): StrategyVersion =
        StrategyVersion(
            id = row.get("id", Long::class.javaObjectType),
            version = row.require("version", String::class.java),
            parameters = row.require("parameters", String::class.java),
            gateStatus = row.require("gate_status", String::class.java),
            metrics = row.require("metrics", String::class.java),
            deployedAt = row.require("deployed_at", OffsetDateTime::class.java),
            rolledBack = row.get("rolled_back", Boolean::class.java) ?: false,
            notes = row.get("notes", String::class.java),
        )

    /**
     * Сохраняет версию и возвращает её с присвоенным БД идентификатором.
     *
     * JSON-колонки пишутся через `CAST(... AS jsonb)` — R2DBC не умеет биндить
     * String в jsonb напрямую (тот же приём в [com.trading.bot.repository.BacktestResultRepository]).
     */
    suspend fun save(version: StrategyVersion): StrategyVersion {
        val sql =
            """
            INSERT INTO strategy_versions (version, parameters, gate_status, metrics, deployed_at, rolled_back, notes)
            VALUES (:version, CAST(:parameters AS jsonb), :gateStatus, CAST(:metrics AS jsonb),
                    :deployedAt, :rolledBack, :notes)
            ON CONFLICT (version) WHERE NOT rolled_back
            DO UPDATE SET parameters = EXCLUDED.parameters,
                          gate_status = EXCLUDED.gate_status,
                          metrics = EXCLUDED.metrics,
                          deployed_at = EXCLUDED.deployed_at,
                          notes = EXCLUDED.notes
            RETURNING id
            """.trimIndent()
        val id =
            databaseClient
                .sql(sql)
                .bind("version", version.version)
                .bind("parameters", version.parameters)
                .bind("gateStatus", version.gateStatus)
                .bind("metrics", version.metrics)
                .bind("deployedAt", version.deployedAt)
                .bind("rolledBack", version.rolledBack)
                .bindOrNull("notes", version.notes)
                .map { row, _ -> row.require("id", Long::class.javaObjectType) }
                .one()
                .awaitSingle()
        return version.copy(id = id)
    }

    /** Все версии в обратном порядке деплоя (новые сначала). */
    suspend fun findAllByOrderByDeployedAtDesc(): List<StrategyVersion> =
        databaseClient
            .sql("SELECT * FROM strategy_versions ORDER BY deployed_at DESC")
            .map { row, _ -> toVersion(row) }
            .all()
            .collectList()
            .awaitSingleOrNull()
            ?: emptyList()

    /** Последняя активная (не откатанная) версия. */
    suspend fun findFirstByRolledBackFalseOrderByDeployedAtDesc(): StrategyVersion? =
        databaseClient
            .sql("SELECT * FROM strategy_versions WHERE NOT rolled_back ORDER BY deployed_at DESC LIMIT 1")
            .map { row, _ -> toVersion(row) }
            .one()
            .awaitSingleOrNull()

    /** Версия по периоду (YYYY-MM). */
    suspend fun findByVersion(version: String): StrategyVersion? =
        databaseClient
            .sql("SELECT * FROM strategy_versions WHERE version = :version")
            .bind("version", version)
            .map { row, _ -> toVersion(row) }
            .one()
            .awaitSingleOrNull()

    /** Все откатанные версии для аудита. */
    suspend fun findByRolledBackTrue(): List<StrategyVersion> =
        databaseClient
            .sql("SELECT * FROM strategy_versions WHERE rolled_back ORDER BY deployed_at DESC")
            .map { row, _ -> toVersion(row) }
            .all()
            .collectList()
            .awaitSingleOrNull()
            ?: emptyList()
}
