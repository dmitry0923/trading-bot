package com.trading.bot.repository

import com.trading.bot.model.AgentLog
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class AgentLogRepository(
    private val namedTemplate: NamedParameterJdbcTemplate,
) {
    private val rowMapper = RowMapper { rs: ResultSet, _: Int ->
        AgentLog(
            id = rs.getLong("id"),
            cycleId = rs.getString("cycle_id"),
            agentName = rs.getString("agent_name"),
            ticker = rs.getString("ticker"),
            action = rs.getString("action"),
            confidence = rs.getBigDecimal("confidence")?.toDouble(),
            reasoning = rs.getString("reasoning"),
            rawOutput = rs.getString("raw_output"),
            latencyMs = rs.getLong("latency_ms").takeIf { it != 0L },
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun findTop100ByOrderByCreatedAtDesc(): List<AgentLog> {
        return namedTemplate.query("SELECT * FROM agent_logs ORDER BY created_at DESC LIMIT 100", rowMapper)
    }

    fun save(log: AgentLog): AgentLog {
        val sql = """
            INSERT INTO agent_logs (cycle_id, agent_name, ticker, action, confidence, reasoning, raw_output, latency_ms, created_at)
            VALUES (:cycleId, :agentName, :ticker, :action, :confidence, :reasoning, :rawOutput, :latencyMs, :createdAt)
        """.trimIndent()
        val keyHolder = GeneratedKeyHolder()
        namedTemplate.update(sql, MapSqlParameterSource()
            .addValue("cycleId", log.cycleId)
            .addValue("agentName", log.agentName)
            .addValue("ticker", log.ticker)
            .addValue("action", log.action)
            .addValue("confidence", log.confidence)
            .addValue("reasoning", log.reasoning)
            .addValue("rawOutput", log.rawOutput)
            .addValue("latencyMs", log.latencyMs)
            .addValue("createdAt", log.createdAt), keyHolder)
        return log.copy(id = keyHolder.key?.toLong())
    }

    fun deleteAll() {
        namedTemplate.update("DELETE FROM agent_logs", emptyMap<String, Any>())
    }
}
