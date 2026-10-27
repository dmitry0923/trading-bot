package com.trading.bot.repository

import com.trading.bot.infrastructure.db.bindOrNull
import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.ExperimentDecision
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * R2DBC-репозиторий ledger'а решений эксперимента (таблица experiment_decisions).
 */
@Repository
class ExperimentDecisionRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toDecision(row: Row): ExperimentDecision =
        ExperimentDecision(
            id = row.get("id", Long::class.javaObjectType),
            cycleId = row.require("cycle_id", String::class.java),
            experimentId = row.require("experiment_id", String::class.java),
            arm = row.require("arm", String::class.java),
            ticker = row.require("ticker", String::class.java),
            timeframe = row.get("timeframe", String::class.java),
            action = row.require("action", String::class.java),
            targetPrice = row.get("target_price", BigDecimal::class.java),
            quantity = row.require("quantity", Int::class.javaObjectType),
            stopLoss = row.get("stop_loss", BigDecimal::class.java),
            takeProfit = row.get("take_profit", BigDecimal::class.java),
            signalStrength = row.get("signal_strength", BigDecimal::class.java)?.toDouble(),
            reasoning = row.get("reasoning", String::class.java),
            isPaper = row.require("is_paper", Boolean::class.javaObjectType),
            version = row.get("version", String::class.java),
            rawOutput = row.get("raw_output", String::class.java),
            executed = row.require("executed", Boolean::class.javaObjectType),
            resultPnl = row.get("result_pnl", BigDecimal::class.java),
            closed = row.require("closed", Boolean::class.javaObjectType),
            decidedAt = row.require("decided_at", LocalDateTime::class.java),
        )

    suspend fun save(decision: ExperimentDecision): ExperimentDecision {
        val sql =
            """
            INSERT INTO experiment_decisions (cycle_id, experiment_id, arm, ticker, timeframe, action,
                target_price, quantity, stop_loss, take_profit, signal_strength, reasoning, is_paper, version,
                raw_output, executed, result_pnl, closed, decided_at)
            VALUES (:cycleId, :experimentId, :arm, :ticker, :timeframe, :action,
                :targetPrice, :quantity, :stopLoss, :takeProfit, :signalStrength, :reasoning, :isPaper, :version,
                :rawOutput, :executed, :resultPnl, :closed, :decidedAt)
            RETURNING id
            """.trimIndent()
        val id =
            databaseClient
                .sql(sql)
                .bind("cycleId", decision.cycleId)
                .bind("experimentId", decision.experimentId)
                .bind("arm", decision.arm)
                .bind("ticker", decision.ticker)
                .bindOrNull("timeframe", decision.timeframe)
                .bind("action", decision.action)
                .bindOrNull("targetPrice", decision.targetPrice)
                .bind("quantity", decision.quantity)
                .bindOrNull("stopLoss", decision.stopLoss)
                .bindOrNull("takeProfit", decision.takeProfit)
                .bindOrNull("signalStrength", decision.signalStrength)
                .bindOrNull("reasoning", decision.reasoning)
                .bind("isPaper", decision.isPaper)
                .bindOrNull("version", decision.version)
                .bindOrNull("rawOutput", decision.rawOutput)
                .bind("executed", decision.executed)
                .bindOrNull("resultPnl", decision.resultPnl)
                .bind("closed", decision.closed)
                .bind("decidedAt", decision.decidedAt)
                .map { row, _ -> row.get("id", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        return decision.copy(id = id)
    }

    /** Последние решения (все руки). */
    suspend fun findRecent(limit: Int): List<ExperimentDecision> =
        databaseClient
            .sql("SELECT * FROM experiment_decisions ORDER BY decided_at DESC LIMIT :limit")
            .bind("limit", limit.coerceIn(1, 500))
            .map { row, _ -> toDecision(row) }
            .all()
            .collectList()
            .awaitSingle()

    /**
     * Решения без зафиксированного исхода (`closed = FALSE`).
     *
     * Крах между `transitionToClosed` и `markOutcome` терял исход эксперимента
     * навсегда: `onPositionClosed` — единственный писатель `result_pnl`, а
     * бэкфилла не существовало (скан-10 P3).
     */
    suspend fun findUnclosed(limit: Int = 500): List<ExperimentDecision> =
        databaseClient
            .sql("SELECT * FROM experiment_decisions WHERE closed = FALSE ORDER BY decided_at ASC LIMIT :limit")
            .bind("limit", limit.coerceIn(1, 1000))
            .map { row, _ -> toDecision(row) }
            .all()
            .collectList()
            .awaitSingle()

    /**
     * Все решения конкретной руки по тикеру в цикле. В одном цикле на каждый
     * тикер (и таймфрейм) пишется своя строка, поэтому результат — список,
     * а не одна запись: `LIMIT 1`/`.one()` теряли остальные строки.
     */
    suspend fun findByCycleIdArmAndTicker(
        cycleId: String,
        arm: String,
        ticker: String,
    ): List<ExperimentDecision> =
        databaseClient
            .sql(
                "SELECT * FROM experiment_decisions WHERE cycle_id = :cycleId AND arm = :arm AND ticker = :ticker ORDER BY decided_at ASC",
            ).bind("cycleId", cycleId)
            .bind("arm", arm)
            .bind("ticker", ticker)
            .map { row, _ -> toDecision(row) }
            .all()
            .collectList()
            .awaitSingle()

    /**
     * Фиксирует результат конкретной строки руки при закрытии контрольной позиции.
     * result_pnl: CONTROL — фактический P&L, VARIANT — гипотетический.
     *
     * Матчинг по `(cycleId, arm, ticker, timeframe)`: без тикера/таймфрейма P&L
     * одного инструмента проставлялся всем строкам руки в цикле.
     */
    suspend fun markResult(
        cycleId: String,
        arm: String,
        ticker: String,
        timeframe: String?,
        resultPnl: BigDecimal,
    ) {
        databaseClient
            .sql(
                """
                UPDATE experiment_decisions
                SET result_pnl = :resultPnl, closed = TRUE
                WHERE cycle_id = :cycleId AND arm = :arm AND ticker = :ticker
                    AND COALESCE(timeframe, '') = COALESCE(:timeframe, '')
                """.trimIndent(),
            ).bind("resultPnl", resultPnl)
            .bind("cycleId", cycleId)
            .bind("arm", arm)
            .bind("ticker", ticker)
            .bindOrNull("timeframe", timeframe)
            .then()
            .awaitSingleOrNull()
    }
}
