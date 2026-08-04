package com.trading.bot.model

import java.math.BigDecimal

data class TradeStats(
    val ticker: String,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRate: Double,
    val avgWin: BigDecimal,
    val avgLoss: BigDecimal,
    val profitFactor: Double,
    val maxConsecutiveLosses: Int,
    val avgHoldTimeMinutes: Long,
    val slHitRate: Double,
    val tpHitRate: Double,
    val strategyCloseRate: Double,
    val bestEntryHour: Int?,
    val worstEntryHour: Int?,
    val blindSpots: List<BlindSpot>,
)

data class BlindSpot(
    val conditionPattern: String,
    val lossRate: Double,
    val occurrenceCount: Int,
    val recommendation: String,
)

data class TimePattern(
    val ticker: String,
    val hourlyWinRates: Map<Int, Double>,
)
