package com.trading.bot.repository
import com.trading.bot.model.Strategy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface StrategyRepository : JpaRepository<Strategy, Long> {
    fun findTopByTickerOrderByCreatedAtDesc(ticker: String): Strategy?
    fun findTop50ByOrderByCreatedAtDesc(): List<Strategy>
}
