package com.trading.bot.repository

import com.trading.bot.model.StrategyAdjustment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface StrategyAdjustmentRepository : JpaRepository<StrategyAdjustment, Long> {
    fun findByTickerOrderByCreatedAtDesc(ticker: String): List<StrategyAdjustment>
    fun findByTickerAndAdjustmentTypeOrderByCreatedAtDesc(ticker: String, type: String): List<StrategyAdjustment>
}
