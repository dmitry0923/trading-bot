package com.trading.bot.repository
import com.trading.bot.model.Position
import com.trading.bot.model.PositionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PositionRepository : JpaRepository<Position, Long> {
    fun findByStatus(status: PositionStatus): List<Position>
    fun findByTickerAndStatus(ticker: String, status: PositionStatus): List<Position>
}
