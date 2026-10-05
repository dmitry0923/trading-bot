package com.trading.bot.tuning

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * Сущность версии стратегии в БД (таблица strategy_versions, миграция V038).
 *
 * Хранит параметры и метрики каждого месячного цикла тюнинга.
 *
 * @property id автоинкрементный PK.
 * @property version период тюнинга в формате YYYY-MM (напр. "2026-10").
 * @property parameters JSONB: параметры стратегии на момент деплоя.
 * @property gateStatus результат gate check: PASS / WARN / ALERT.
 * @property metrics JSONB: метрики прошедшего месяца (PF, DD, WinRate, Sharpe, Trades).
 * @property deployedAt время деплоя версии.
 * @property rolledBack true — версия откатана, больше не активна.
 * @property notes произвольные заметки (причина отката, комментарии).
 */
@Table("strategy_versions")
data class StrategyVersion(
    @Id
    val id: Long? = null,
    val version: String,
    val parameters: String,          // JSONB сериализован в String (Jackson)
    @Column("gate_status")
    val gateStatus: String,          // PASS / WARN / ALERT
    val metrics: String,             // JSONB сериализован в String (Jackson)
    @Column("deployed_at")
    val deployedAt: OffsetDateTime = OffsetDateTime.now(),
    @Column("rolled_back")
    val rolledBack: Boolean = false,
    val notes: String? = null,
)

/**
 * Spring Data репозиторий для [StrategyVersion].
 *
 * Обеспечивает CRUD-доступ к таблице strategy_versions для
 * Monthly Tuning Engine (docs/24, Фаза 3.5).
 */
@Repository
interface StrategyVersionRepository : CrudRepository<StrategyVersion, Long> {

    /**
     * Все версии в обратном порядке деплоя (новые сначала).
     */
    fun findAllByOrderByDeployedAtDesc(): List<StrategyVersion>

    /**
     * Последняя активная (не откатанная) версия.
     */
    fun findFirstByRolledBackFalseOrderByDeployedAtDesc(): StrategyVersion?

    /**
     * Версия по периоду (YYYY-MM).
     */
    fun findByVersion(version: String): StrategyVersion?

    /**
     * Все откатанные версии для аудита.
     */
    fun findByRolledBackTrue(): List<StrategyVersion>
}
