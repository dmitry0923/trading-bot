package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.DailyRiskSnapshot
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
    private val databaseClient: DatabaseClient,
) {
    private fun toDailyRiskSnapshot(row: Row): DailyRiskSnapshot =
        DailyRiskSnapshot(
            id = row.get("id", Long::class.javaObjectType),
            tradeDate = row.require("trade_date", LocalDate::class.java),
            dailyPnl = row.require("daily_pnl", BigDecimal::class.java),
            limitReached = row.require("limit_reached", Boolean::class.javaObjectType),
            maxDrawdownToday = row.require("max_drawdown_today", BigDecimal::class.java),
        )

    fun findByDate(
        tradeDate: LocalDate,
        accountId: Long? = null,
    ): DailyRiskSnapshot? {
        val sql =
            if (accountId == null) {
                "SELECT * FROM daily_risk_snapshot WHERE trade_date = :tradeDate AND account_id IS NULL"
            } else {
                "SELECT * FROM daily_risk_snapshot WHERE trade_date = :tradeDate AND account_id = :accountId"
            }
        val spec = databaseClient.sql(sql).bind("tradeDate", tradeDate)
        val finalSpec = if (accountId != null) spec.bind("accountId", accountId) else spec
        return finalSpec
            .map { row, _ -> toDailyRiskSnapshot(row) }
            .one()
            .block()
    }

    /**
     * История дневных снапшотов за последние [days] дней (включая сегодня), по дате ASC.
     * Источник данных для `GET /api/v1/risk/daily-pnl-history` (график дневных P&L).
     */
    fun findRecent(days: Int): List<DailyRiskSnapshot> =
        findRecent(days, accountId = null)

    /**
     * История дневных снапшотов аккаунта (multi-account): accountId = null → legacy
     * (account_id IS NULL). Источник `GET /api/v1/accounts/{id}/daily-pnl`.
     */
    fun findRecent(
        days: Int,
        accountId: Long?,
    ): List<DailyRiskSnapshot> {
        val sinceDate = LocalDate.now().minusDays(days.toLong())
        val sql =
            if (accountId == null) {
                "SELECT * FROM daily_risk_snapshot WHERE trade_date >= :sinceDate AND account_id IS NULL ORDER BY trade_date ASC"
            } else {
                "SELECT * FROM daily_risk_snapshot WHERE trade_date >= :sinceDate AND account_id = :accountId ORDER BY trade_date ASC"
            }
        val spec = databaseClient.sql(sql).bind("sinceDate", sinceDate)
        val finalSpec = if (accountId != null) spec.bind("accountId", accountId) else spec
        return finalSpec
            .map { row, _ -> toDailyRiskSnapshot(row) }
            .all()
            .collectList()
            .block()
            ?: emptyList()
    }

    fun deleteAll() {
        databaseClient.sql("DELETE FROM daily_risk_snapshot").then().block()
    }

    /**
     * Upsert снапшота для торгового дня (одна строка на (дата, account_id)).
     */
    fun upsert(
        tradeDate: LocalDate,
        dailyPnl: BigDecimal,
        limitReached: Boolean,
        maxDrawdownToday: BigDecimal,
        accountId: Long? = null,
    ) {
        val sql =
            if (accountId == null) {
                """
                INSERT INTO daily_risk_snapshot (trade_date, daily_pnl, limit_reached, max_drawdown_today, updated_at)
                VALUES (:tradeDate, :dailyPnl, :limitReached, :maxDrawdownToday, NOW())
                ON CONFLICT (trade_date) WHERE account_id IS NULL DO UPDATE SET
                    daily_pnl = EXCLUDED.daily_pnl,
                    limit_reached = EXCLUDED.limit_reached,
                    max_drawdown_today = EXCLUDED.max_drawdown_today,
                    updated_at = NOW()
                """.trimIndent()
            } else {
                """
                INSERT INTO daily_risk_snapshot (trade_date, account_id, daily_pnl, limit_reached, max_drawdown_today, updated_at)
                VALUES (:tradeDate, :accountId, :dailyPnl, :limitReached, :maxDrawdownToday, NOW())
                ON CONFLICT (trade_date, account_id) DO UPDATE SET
                    daily_pnl = EXCLUDED.daily_pnl,
                    limit_reached = EXCLUDED.limit_reached,
                    max_drawdown_today = EXCLUDED.max_drawdown_today,
                    updated_at = NOW()
                """.trimIndent()
            }
        val spec =
            databaseClient
                .sql(sql)
                .bind("tradeDate", tradeDate)
                .bind("dailyPnl", dailyPnl)
                .bind("limitReached", limitReached)
                .bind("maxDrawdownToday", maxDrawdownToday)
        val finalSpec = if (accountId != null) spec.bind("accountId", accountId) else spec
        finalSpec
            .then()
            .block()
    }
}
