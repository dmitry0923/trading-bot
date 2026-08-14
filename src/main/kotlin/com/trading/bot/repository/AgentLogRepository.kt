package com.trading.bot.repository

import com.trading.bot.infrastructure.db.bindOrNull
import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.AgentLog
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

@Repository
class AgentLogRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toAgentLog(row: Row): AgentLog =
        AgentLog(
            id = row.get("id", Long::class.javaObjectType),
            cycleId = row.require("cycle_id", String::class.java),
            agentName = row.require("agent_name", String::class.java),
            ticker = row.get("ticker", String::class.java),
            action = row.require("action", String::class.java),
            signalStrength = row.get("signal_strength", BigDecimal::class.java)?.toDouble(),
            reasoning = row.get("reasoning", String::class.java),
            rawOutput = row.get("raw_output", String::class.java),
            latencyMs = row.get("latency_ms", Long::class.javaObjectType)?.takeIf { it != 0L },
            tokensUsed = row.get("tokens_used", Int::class.javaObjectType)?.takeIf { it != 0 },
            isCached = row.require("is_cached", Boolean::class.javaObjectType),
            overrideReason = row.get("override_reason", String::class.java),
            storageKey = row.get("storage_key", String::class.java),
            createdAt = row.require("created_at", LocalDateTime::class.java),
        )

    suspend fun findFiltered(
        ticker: String?,
        agentName: String?,
        limit: Int,
    ): List<AgentLog> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>("limit" to limit.coerceIn(1, 500))
        ticker?.takeIf { it.isNotBlank() }?.let {
            conditions += "ticker = :ticker"
            params["ticker"] = it
        }
        agentName?.takeIf { it.isNotBlank() }?.let {
            conditions += "agent_name = :agentName"
            params["agentName"] = it
        }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")} "
        val sql = "SELECT * FROM agent_logs $where ORDER BY created_at DESC LIMIT :limit"
        var spec = databaseClient.sql(sql)
        params.forEach { (name, value) -> spec = spec.bind(name, value) }
        return spec
            .map { row, _ -> toAgentLog(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /**
     * Решение LLM-стратега (Agent-3-Strategist) для [cycleId] — последняя запись.
     * Используется экспортом ML-датасета (roadmap v2.4): action + signalStrength
     * стратега на входе в позицию.
     */
    suspend fun findStrategyDecision(cycleId: String): AgentLog? =
        databaseClient
            .sql(
                "SELECT * FROM agent_logs WHERE cycle_id = :cycleId AND agent_name = :agentName " +
                    "ORDER BY created_at DESC LIMIT 1",
            ).bind("cycleId", cycleId)
            .bind("agentName", STRATEGY_AGENT)
            .map { row, _ -> toAgentLog(row) }
            .one()
            .awaitSingleOrNull()

    /**
     * Батч-получение силы сигнала стратега (Agent-3-Strategist) по списку cycleId.
     *
     * Используется онлайн-калибровкой порога уверенности (roadmap 13.11.8): для закрытых
     * позиций тикера одним запросом поднимаются силы сигнала на входе. Возвращает map
     * cycleId -> signalStrength; cycleId без лога стратега (детерминированные стратегии без
     * LLM-контура) отсутствуют в результате.
     */
    suspend fun findStrategySignalStrengthByCycleIds(cycleIds: Collection<String>): Map<String, Double> {
        val ids = cycleIds.map { it.trim() }.distinct().filter { it.isNotEmpty() }
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.indices.joinToString(",") { ":id$it" }
        var spec =
            databaseClient
                .sql(
                    """
                    SELECT cycle_id, signal_strength
                    FROM agent_logs
                    WHERE cycle_id IN ($placeholders) AND agent_name = :agentName
                    """.trimIndent(),
                ).bind("agentName", STRATEGY_AGENT)
        ids.forEachIndexed { i, id -> spec = spec.bind("id$i", id) }
        return spec
            .map { row, _ ->
                row.require("cycle_id", String::class.java) to (row.get("signal_strength", BigDecimal::class.java)?.toDouble() ?: 0.0)
            }.all()
            .collectList()
            .awaitSingle()
            .toMap()
    }

    suspend fun save(log: AgentLog): AgentLog {
        val sql =
            """
            INSERT INTO agent_logs (cycle_id, agent_name, ticker, action, signal_strength, reasoning, raw_output,
                                    latency_ms, tokens_used, is_cached, override_reason, storage_key, created_at)
            VALUES (:cycleId, :agentName, :ticker, :action, :signalStrength, :reasoning, :rawOutput,
                    :latencyMs, :tokensUsed, :isCached, :overrideReason, :storageKey, :createdAt)
            RETURNING id
            """.trimIndent()
        val id =
            databaseClient
                .sql(sql)
                .bind("cycleId", log.cycleId)
                .bind("agentName", log.agentName)
                .bindOrNull("ticker", log.ticker)
                .bind("action", log.action)
                .bindOrNull("signalStrength", log.signalStrength)
                .bindOrNull("reasoning", log.reasoning)
                .bindOrNull("rawOutput", log.rawOutput)
                .bindOrNull("latencyMs", log.latencyMs)
                .bindOrNull("tokensUsed", log.tokensUsed)
                .bind("isCached", log.isCached)
                .bindOrNull("overrideReason", log.overrideReason)
                .bindOrNull("storageKey", log.storageKey)
                .bind("createdAt", log.createdAt)
                .map { row, _ -> row.get("id", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        return log.copy(id = id)
    }

    private companion object {
        const val STRATEGY_AGENT = "Agent-3-Strategist"
    }
}
