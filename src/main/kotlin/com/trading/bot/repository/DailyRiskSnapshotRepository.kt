package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.DailyRiskSnapshot
import io.r2dbc.spi.Row
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate

/**
 * R2DBC-репозиторий дневных риск-снапшотов.
 *
 * Методы НЕ suspend: дневное состояние риска читается/пишется редко
 * (на закрытие позиции и в начале нового торгового дня) и используется
 * из синхронного state machine риска ([com.trading.bot.service.RiskManagementService]).
 * Внутри — блокирующий [reactor.core.publisher.Mono.block] на короткую операцию.
 */
@Repository
class DailyRiskSnapshotRepository(
    private val databaseClient: DatabaseClient
) {
    private fun toDailyRiskSnapshot(row: Row): DailyRiskSnapshot = DailyRiskSnapshot(
        id = row.get("id", Long::class.javaObjectType),
        tradeDate = row.require("trade_date", LocalDate::class.java),
        dailyPnl = row.require("daily_pnl", BigDecimal::class.java),
        limitReached = row.require("limit_reached", Boolean::class.javaObjectType),
        maxDrawdownToday = row.require("max_drawdown_today", BigDecimal::class.java)
    )

    fun findByDate(tradeDate: LocalDate): DailyRiskSnapshot? {
        val sql = "SELECT * FROM daily_risk_snapshot WHERE trade_date = :tradeDate"
        return databaseClient.sql(sql)
            .bind("tradeDate", tradeDate)
            .map { row, _ -> toDailyRiskSnapshot(row) }
            .one()
            .block()
    }

    fun deleteAll() {
        databaseClient.sql("DELETE FROM daily_risk_snapshot").then().block()
    }

    /**
     * Upsert снапшота для торгового дня (одна строка на дату).
     */
    fun upsert(tradeDate: LocalDate, dailyPnl: BigDecimal, limitReached: Boolean, maxDrawdownToday: BigDecimal) {
        val sql = """
            INSERT INTO daily_risk_snapshot (trade_date, daily_pnl, limit_reached, max_drawdown_today, updated_at)
            VALUES (:tradeDate, :dailyPnl, :limitReached, :maxDrawdownToday, NOW())
            ON CONFLICT (trade_date) DO UPDATE SET
                daily_pnl = EXCLUDED.daily_pnl,
                limit_reached = EXCLUDED.limit_reached,
                max_drawdown_today = EXCLUDED.max_drawdown_today,
                updated_at = NOW()
        """.trimIndent()
        databaseClient.sql(sql)
            .bind("tradeDate", tradeDate)
            .bind("dailyPnl", dailyPnl)
            .bind("limitReached", limitReached)
            .bind("maxDrawdownToday", maxDrawdownToday)
            .then()
            .block()
    }
}
