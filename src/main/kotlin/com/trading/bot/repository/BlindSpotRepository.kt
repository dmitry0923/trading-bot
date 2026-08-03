package com.trading.bot.repository

import com.trading.bot.model.BlindSpotEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface BlindSpotRepository : JpaRepository<BlindSpotEntity, Long> {
    fun findByIsActiveTrue(): List<BlindSpotEntity>
    fun findByTickerAndIsActiveTrue(ticker: String): List<BlindSpotEntity>
}
