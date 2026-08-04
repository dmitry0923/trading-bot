package com.trading.bot.repository

import com.trading.bot.model.Position
import com.trading.bot.model.PositionStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDateTime

@Repository
class PositionRepository(
    private val namedTemplate: NamedParameterJdbcTemplate,
) {
    private val rowMapper = RowMapper { rs: ResultSet, _: Int ->
        Position(
            id = rs.getLong("id"),
            ticker = rs.getString("ticker"),
            direction = enumValueOf(rs.getString("direction")),
            quantity = rs.getInt("quantity"),
            entryPrice = rs.getBigDecimal("entry_price"),
            currentPrice = rs.getBigDecimal("current_price"),
            closePrice = rs.getBigDecimal("close_price"),
            stopLoss = rs.getBigDecimal("stop_loss"),
            takeProfit = rs.getBigDecimal("take_profit"),
            instrumentType = enumValueOf(rs.getString("instrument_type")),
            leverage = rs.getBigDecimal("leverage"),
            goPerContract = rs.getBigDecimal("go_per_contract"),
            marginUsed = rs.getBigDecimal("margin_used"),
            liquidationPrice = rs.getBigDecimal("liquidation_price"),
            variationMargin = rs.getBigDecimal("variation_margin") ?: BigDecimal.ZERO,
            stopLossPoints = rs.getInt("stop_loss_points").takeIf { !rs.wasNull() },
            trailingStopPrice = rs.getBigDecimal("trailing_stop_price"),
            pnl = rs.getBigDecimal("pnl"),
            status = enumValueOf(rs.getString("status")),
            alorOrderId = rs.getString("alor_order_id"),
            closeReason = rs.getString("close_reason"),
            openedAt = rs.getTimestamp("opened_at").toLocalDateTime(),
            closedAt = rs.getTimestamp("closed_at")?.toLocalDateTime()
        )
    }

    fun findByStatus(status: PositionStatus): List<Position> {
        val sql = "SELECT * FROM positions WHERE status = :status ORDER BY opened_at DESC"
        return namedTemplate.query(sql, mapOf("status" to status.name), rowMapper)
    }

    fun findById(id: Long): Position {
        val sql = "SELECT * FROM positions WHERE id = :id"
        return namedTemplate.query(sql, mapOf("id" to id), rowMapper).first()
    }

    fun findByAlorOrderId(alorOrderId: String): Position? {
        val sql = "SELECT * FROM positions WHERE alor_order_id = :alorOrderId"
        return namedTemplate.query(sql, mapOf("alorOrderId" to alorOrderId), rowMapper).firstOrNull()
    }

    fun findAll(): List<Position> {
        return namedTemplate.query("SELECT * FROM positions ORDER BY opened_at DESC", rowMapper)
    }

    fun findClosedSince(since: LocalDateTime): List<Position> {
        val sql = "SELECT * FROM positions WHERE status != 'OPEN' AND closed_at >= :since ORDER BY closed_at DESC"
        return namedTemplate.query(sql, mapOf("since" to since), rowMapper)
    }

    fun findClosedByTickerSince(ticker: String, since: LocalDateTime): List<Position> {
        val sql = """
            SELECT * FROM positions
            WHERE status != 'OPEN' AND ticker = :ticker AND closed_at >= :since
            ORDER BY closed_at DESC
        """.trimIndent()
        return namedTemplate.query(sql, mapOf("ticker" to ticker, "since" to since), rowMapper)
    }

    fun save(position: Position): Position {
        return if (position.id == null) {
            insert(position)
        } else {
            update(position)
            position
        }
    }

    private fun insert(position: Position): Position {
        val sql = """
            INSERT INTO positions (ticker, direction, quantity, entry_price, current_price, close_price,
                stop_loss, take_profit, instrument_type, leverage, go_per_contract, margin_used,
                liquidation_price, variation_margin, stop_loss_points, trailing_stop_price, pnl, status,
                alor_order_id, close_reason, opened_at, closed_at)
            VALUES (:ticker, :direction, :quantity, :entryPrice, :currentPrice, :closePrice,
                :stopLoss, :takeProfit, :instrumentType, :leverage, :goPerContract, :marginUsed,
                :liquidationPrice, :variationMargin, :stopLossPoints, :trailingStopPrice, :pnl, :status,
                :alorOrderId, :closeReason, :openedAt, :closedAt)
            RETURNING id    
        """.trimIndent()
        val keyHolder = GeneratedKeyHolder()
        namedTemplate.update(sql, createParams(position), keyHolder)
        return position.copy(id = keyHolder.key?.toLong())
    }

    private fun update(position: Position) {
        val sql = """
            UPDATE positions SET
                ticker = :ticker, direction = :direction, quantity = :quantity, entry_price = :entryPrice,
                current_price = :currentPrice, close_price = :closePrice, stop_loss = :stopLoss,
                take_profit = :takeProfit, instrument_type = :instrumentType, leverage = :leverage,
                go_per_contract = :goPerContract, margin_used = :marginUsed, liquidation_price = :liquidationPrice,
                variation_margin = :variationMargin, stop_loss_points = :stopLossPoints,
                trailing_stop_price = :trailingStopPrice, pnl = :pnl, status = :status,
                alor_order_id = :alorOrderId, close_reason = :closeReason, opened_at = :openedAt,
                closed_at = :closedAt
            WHERE id = :id
        """.trimIndent()
        namedTemplate.update(sql, createParams(position).addValue("id", position.id))
    }

    fun deleteAll() {
        namedTemplate.update("DELETE FROM positions", emptyMap<String, Any>())
    }

    private fun createParams(position: Position): MapSqlParameterSource {
        return MapSqlParameterSource()
            .addValue("ticker", position.ticker)
            .addValue("direction", position.direction.name)
            .addValue("quantity", position.quantity)
            .addValue("entryPrice", position.entryPrice)
            .addValue("currentPrice", position.currentPrice)
            .addValue("closePrice", position.closePrice)
            .addValue("stopLoss", position.stopLoss)
            .addValue("takeProfit", position.takeProfit)
            .addValue("instrumentType", position.instrumentType.name)
            .addValue("leverage", position.leverage)
            .addValue("goPerContract", position.goPerContract)
            .addValue("marginUsed", position.marginUsed)
            .addValue("liquidationPrice", position.liquidationPrice)
            .addValue("variationMargin", position.variationMargin)
            .addValue("stopLossPoints", position.stopLossPoints)
            .addValue("trailingStopPrice", position.trailingStopPrice)
            .addValue("pnl", position.pnl)
            .addValue("status", position.status.name)
            .addValue("alorOrderId", position.alorOrderId)
            .addValue("closeReason", position.closeReason)
            .addValue("openedAt", position.openedAt)
            .addValue("closedAt", position.closedAt)
    }
}
