package com.trading.bot.repository

import com.trading.bot.model.Position
import com.trading.bot.model.PositionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface PositionRepository : JpaRepository<Position, Long> {
    fun findByStatus(status: PositionStatus): List<Position>

    @Query("""
        SELECT p FROM Position p
        WHERE p.status != 'OPEN' AND p.closedAt >= :since
        ORDER BY p.closedAt DESC
    """)
    fun findClosedSince(@Param("since") since: LocalDateTime): List<Position>

    @Query("""
        SELECT p FROM Position p
        WHERE p.status != 'OPEN' AND p.ticker = :ticker AND p.closedAt >= :since
        ORDER BY p.closedAt DESC
    """)
    fun findClosedByTickerSince(
        @Param("ticker") ticker: String,
        @Param("since") since: LocalDateTime
    ): List<Position>

    /**
     * JPQL-агрегация вместо native query projection.
     * Возвращает сырые массивы — маппинг в DTO выполняется в сервисе.
     */
    @Query("""
        SELECT
            p.ticker,
            COUNT(p),
            SUM(CASE WHEN p.pnl > 0 THEN 1 ELSE 0 END),
            AVG(CASE WHEN p.pnl > 0 THEN p.pnl END),
            AVG(CASE WHEN p.pnl < 0 THEN p.pnl END),
            p.closeReason,
            COUNT(p)
        FROM Position p
        WHERE p.status != 'OPEN' AND p.closedAt >= :since
        GROUP BY p.ticker, p.closeReason
        ORDER BY p.ticker
    """)
    fun getTradeBreakdownRaw(@Param("since") since: LocalDateTime): List<Array<Any>>
}
