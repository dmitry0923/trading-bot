package com.trading.bot.repository

import com.trading.bot.model.DailyRiskSnapshot
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate

@Repository
class DailyRiskSnapshotRepository(
    private val namedTemplate: NamedParameterJdbcTemplate
) {
    private val rowMapper = RowMapper { rs: ResultSet, _: Int ->
        DailyRiskSnapshot(
            id = rs.getLong("id"),
            tradeDate = rs.getDate("trade_date").toLocalDate(),
            dailyPnl = rs.getBigDecimal("daily_pnl"),
            limitReached = rs.getBoolean("limit_reached"),
            maxDrawdownToday = rs.getBigDecimal("max_drawdown_today")
        )
    }

    fun findByDate(tradeDate: LocalDate): DailyRiskSnapshot? {
        val sql = "SELECT * FROM daily_risk_snapshot WHERE trade_date = :tradeDate"
        return namedTemplate.query(sql, mapOf("tradeDate" to tradeDate), rowMapper).firstOrNull()
    }

    fun deleteAll() {
        namedTemplate.update("DELETE FROM daily_risk_snapshot", emptyMap<String, Any>())
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
        val params = MapSqlParameterSource()
            .addValue("tradeDate", tradeDate)
            .addValue("dailyPnl", dailyPnl)
            .addValue("limitReached", limitReached)
            .addValue("maxDrawdownToday", maxDrawdownToday)
        namedTemplate.update(sql, params)
    }
}
