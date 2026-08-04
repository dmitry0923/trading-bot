package com.trading.bot.repository

import com.trading.bot.infrastructure.db.bindOrNull
import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.Strategy
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

@Repository
class StrategyRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toStrategy(row: Row): Strategy =
        Strategy(
            id = row.get("id", Long::class.javaObjectType),
            ticker = row.require("ticker", String::class.java),
            action = enumValueOf(row.require("action", String::class.java)),
            targetPrice = row.require("target_price", BigDecimal::class.java),
            quantity = row.require("quantity", Int::class.javaObjectType),
            stopLoss = row.get("stop_loss", BigDecimal::class.java),
            takeProfit = row.get("take_profit", BigDecimal::class.java),
            trailingStop = row.require("trailing_stop", Boolean::class.javaObjectType),
            confidence = row.require("confidence", Double::class.javaObjectType),
            reasoning = row.require("reasoning", String::class.java),
            rawJson = row.get("raw_json", String::class.java),
            cycleId = row.require("cycle_id", String::class.java),
            validUntil = row.require("valid_until", LocalDateTime::class.java),
            createdAt = row.require("created_at", LocalDateTime::class.java),
        )

    suspend fun findTop50ByOrderByCreatedAtDesc(): List<Strategy> =
        databaseClient
            .sql("SELECT * FROM strategies ORDER BY created_at DESC LIMIT 50")
            .map { row, _ -> toStrategy(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun findTopByTickerOrderByCreatedAtDesc(ticker: String): Strategy? {
        val sql = "SELECT * FROM strategies WHERE ticker = :ticker ORDER BY created_at DESC LIMIT 1"
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .map { row, _ -> toStrategy(row) }
            .one()
            .awaitSingleOrNull()
    }

    suspend fun save(strategy: Strategy): Strategy {
        val sql =
            """
            INSERT INTO strategies (ticker, action, target_price, quantity, stop_loss, take_profit, trailing_stop, confidence, reasoning, raw_json, cycle_id, valid_until, created_at)
            VALUES (:ticker, :action, :targetPrice, :quantity, :stopLoss, :takeProfit, :trailingStop, :confidence, :reasoning, :rawJson, :cycleId, :validUntil, :createdAt)
            RETURNING id
            """.trimIndent()
        val id =
            databaseClient
                .sql(sql)
                .bind("ticker", strategy.ticker)
                .bind("action", strategy.action.name)
                .bind("targetPrice", strategy.targetPrice)
                .bind("quantity", strategy.quantity)
                .bindOrNull("stopLoss", strategy.stopLoss)
                .bindOrNull("takeProfit", strategy.takeProfit)
                .bind("trailingStop", strategy.trailingStop)
                .bind("confidence", strategy.confidence)
                .bindOrNull("reasoning", strategy.reasoning)
                .bindOrNull("rawJson", strategy.rawJson)
                .bindOrNull("cycleId", strategy.cycleId)
                .bind("validUntil", strategy.validUntil)
                .bind("createdAt", strategy.createdAt)
                .map { row, _ -> row.require("id", Long::class.javaObjectType) }
                .one()
                .awaitSingle()
        return strategy.copy(id = id)
    }
}
