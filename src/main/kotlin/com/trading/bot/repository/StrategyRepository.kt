package com.trading.bot.repository

import com.trading.bot.model.Strategy
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class StrategyRepository(
    private val namedTemplate: NamedParameterJdbcTemplate,
) {
    private val rowMapper = RowMapper { rs: ResultSet, _: Int ->
        Strategy(
            id = rs.getLong("id"),
            ticker = rs.getString("ticker"),
            action = enumValueOf(rs.getString("action")),
            targetPrice = rs.getBigDecimal("target_price"),
            quantity = rs.getInt("quantity"),
            stopLoss = rs.getBigDecimal("stop_loss"),
            takeProfit = rs.getBigDecimal("take_profit"),
            trailingStop = rs.getBoolean("trailing_stop"),
            confidence = rs.getDouble("confidence"),
            reasoning = rs.getString("reasoning"),
            rawJson = rs.getString("raw_json"),
            cycleId = rs.getString("cycle_id"),
            validUntil = rs.getTimestamp("valid_until").toLocalDateTime(),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun findTop50ByOrderByCreatedAtDesc(): List<Strategy> {
        return namedTemplate.query("SELECT * FROM strategies ORDER BY created_at DESC LIMIT 50", rowMapper)
    }

    fun findTopByTickerOrderByCreatedAtDesc(ticker: String): Strategy? {
        val sql = "SELECT * FROM strategies WHERE ticker = :ticker ORDER BY created_at DESC LIMIT 1"
        return namedTemplate.query(sql, mapOf("ticker" to ticker), rowMapper).firstOrNull()
    }

    fun save(strategy: Strategy): Strategy {
        val sql = """
            INSERT INTO strategies (ticker, action, target_price, quantity, stop_loss, take_profit, trailing_stop, confidence, reasoning, raw_json, cycle_id, valid_until, created_at)
            VALUES (:ticker, :action, :targetPrice, :quantity, :stopLoss, :takeProfit, :trailingStop, :confidence, :reasoning, :rawJson, :cycleId, :validUntil, :createdAt)
        """.trimIndent()
        val keyHolder = GeneratedKeyHolder()
        namedTemplate.update(sql, MapSqlParameterSource()
            .addValue("ticker", strategy.ticker)
            .addValue("action", strategy.action.name)
            .addValue("targetPrice", strategy.targetPrice)
            .addValue("quantity", strategy.quantity)
            .addValue("stopLoss", strategy.stopLoss)
            .addValue("takeProfit", strategy.takeProfit)
            .addValue("trailingStop", strategy.trailingStop)
            .addValue("confidence", strategy.confidence)
            .addValue("reasoning", strategy.reasoning)
            .addValue("rawJson", strategy.rawJson)
            .addValue("cycleId", strategy.cycleId)
            .addValue("validUntil", strategy.validUntil)
            .addValue("createdAt", strategy.createdAt), keyHolder)
        return strategy.copy(id = keyHolder.key?.toLong())
    }
}
