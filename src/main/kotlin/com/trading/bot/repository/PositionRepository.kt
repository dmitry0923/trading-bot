package com.trading.bot.repository

import com.trading.bot.infrastructure.db.bindOrNull
import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.Position
import com.trading.bot.model.PositionStatus
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * R2DBC-репозиторий позиций. Все методы suspend: вызываются из корутин,
 * блокирующие JDBC-обёртки ([com.trading.bot.infrastructure.db.BlockingDb]) больше не нужны.
 */
@Repository
class PositionRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toPosition(row: Row): Position =
        Position(
            id = row.get("id", Long::class.javaObjectType),
            ticker = row.require("ticker", String::class.java),
            direction = enumValueOf(row.require("direction", String::class.java)),
            quantity = row.require("quantity", Int::class.javaObjectType),
            entryPrice = row.require("entry_price", BigDecimal::class.java),
            currentPrice = row.get("current_price", BigDecimal::class.java),
            closePrice = row.get("close_price", BigDecimal::class.java),
            stopLoss = row.get("stop_loss", BigDecimal::class.java),
            takeProfit = row.get("take_profit", BigDecimal::class.java),
            instrumentType = enumValueOf(row.require("instrument_type", String::class.java)),
            leverage = row.get("leverage", BigDecimal::class.java),
            goPerContract = row.get("go_per_contract", BigDecimal::class.java),
            marginUsed = row.get("margin_used", BigDecimal::class.java),
            liquidationPrice = row.get("liquidation_price", BigDecimal::class.java),
            variationMargin = row.get("variation_margin", BigDecimal::class.java) ?: BigDecimal.ZERO,
            stopLossPoints = row.get("stop_loss_points", Int::class.javaObjectType),
            trailingStopPrice = row.get("trailing_stop_price", BigDecimal::class.java),
            pnl = row.get("pnl", BigDecimal::class.java),
            status = enumValueOf(row.require("status", String::class.java)),
            alorOrderId = row.get("alor_order_id", String::class.java),
            closeReason = row.get("close_reason", String::class.java),
            openedAt = row.require("opened_at", LocalDateTime::class.java),
            closedAt = row.get("closed_at", LocalDateTime::class.java),
        )

    suspend fun findByStatus(status: PositionStatus): List<Position> {
        val sql = "SELECT * FROM positions WHERE status = :status ORDER BY opened_at DESC"
        return databaseClient
            .sql(sql)
            .bind("status", status.name)
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun findOpenCount(): Int {
        val sql = "SELECT COUNT(*) AS cnt FROM positions WHERE status = 'OPEN'"
        return databaseClient
            .sql(sql)
            .map { row, _ -> row.get("cnt", java.lang.Long::class.java)?.toInt() ?: 0 }
            .one()
            .awaitSingleOrNull()
            ?: 0
    }

    suspend fun findById(id: Long): Position {
        val sql = "SELECT * FROM positions WHERE id = :id"
        return databaseClient
            .sql(sql)
            .bind("id", id)
            .map { row, _ -> toPosition(row) }
            .one()
            .awaitSingle()
    }

    suspend fun findByAlorOrderId(alorOrderId: String): Position? {
        val sql = "SELECT * FROM positions WHERE alor_order_id = :alorOrderId"
        return databaseClient
            .sql(sql)
            .bind("alorOrderId", alorOrderId)
            .map { row, _ -> toPosition(row) }
            .one()
            .awaitSingleOrNull()
    }

    suspend fun findAll(): List<Position> =
        databaseClient
            .sql("SELECT * FROM positions ORDER BY opened_at DESC")
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun findClosedSince(since: LocalDateTime): List<Position> {
        val sql = "SELECT * FROM positions WHERE status != 'OPEN' AND closed_at >= :since ORDER BY closed_at DESC"
        return databaseClient
            .sql(sql)
            .bind("since", since)
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun findClosedByTickerSince(
        ticker: String,
        since: LocalDateTime,
    ): List<Position> {
        val sql =
            """
            SELECT * FROM positions
            WHERE status != 'OPEN' AND ticker = :ticker AND closed_at >= :since
            ORDER BY closed_at DESC
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .bind("since", since)
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun save(position: Position): Position =
        if (position.id == null) {
            insert(position)
        } else {
            update(position)
            position
        }

    private suspend fun insert(position: Position): Position {
        val sql =
            """
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
        val id =
            databaseClient
                .sql(sql)
                .bind("ticker", position.ticker)
                .bind("direction", position.direction.name)
                .bind("quantity", position.quantity)
                .bind("entryPrice", position.entryPrice)
                .bindOrNull("currentPrice", position.currentPrice)
                .bindOrNull("closePrice", position.closePrice)
                .bindOrNull("stopLoss", position.stopLoss)
                .bindOrNull("takeProfit", position.takeProfit)
                .bind("instrumentType", position.instrumentType.name)
                .bindOrNull("leverage", position.leverage)
                .bindOrNull("goPerContract", position.goPerContract)
                .bindOrNull("marginUsed", position.marginUsed)
                .bindOrNull("liquidationPrice", position.liquidationPrice)
                .bind("variationMargin", position.variationMargin)
                .bindOrNull("stopLossPoints", position.stopLossPoints)
                .bindOrNull("trailingStopPrice", position.trailingStopPrice)
                .bindOrNull("pnl", position.pnl)
                .bind("status", position.status.name)
                .bindOrNull("alorOrderId", position.alorOrderId)
                .bindOrNull("closeReason", position.closeReason)
                .bind("openedAt", position.openedAt)
                .bindOrNull("closedAt", position.closedAt)
                .map { row, _ -> row.get("id", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        return position.copy(id = id)
    }

    private suspend fun update(position: Position) {
        val sql =
            """
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
        databaseClient
            .sql(sql)
            .bind("ticker", position.ticker)
            .bind("direction", position.direction.name)
            .bind("quantity", position.quantity)
            .bind("entryPrice", position.entryPrice)
            .bindOrNull("currentPrice", position.currentPrice)
            .bindOrNull("closePrice", position.closePrice)
            .bindOrNull("stopLoss", position.stopLoss)
            .bindOrNull("takeProfit", position.takeProfit)
            .bind("instrumentType", position.instrumentType.name)
            .bindOrNull("leverage", position.leverage)
            .bindOrNull("goPerContract", position.goPerContract)
            .bindOrNull("marginUsed", position.marginUsed)
            .bindOrNull("liquidationPrice", position.liquidationPrice)
            .bind("variationMargin", position.variationMargin)
            .bindOrNull("stopLossPoints", position.stopLossPoints)
            .bindOrNull("trailingStopPrice", position.trailingStopPrice)
            .bindOrNull("pnl", position.pnl)
            .bind("status", position.status.name)
            .bindOrNull("alorOrderId", position.alorOrderId)
            .bindOrNull("closeReason", position.closeReason)
            .bind("openedAt", position.openedAt)
            .bindOrNull("closedAt", position.closedAt)
            .bind("id", position.id!!)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun deleteAll() {
        databaseClient.sql("DELETE FROM positions").then().awaitSingleOrNull()
    }
}
