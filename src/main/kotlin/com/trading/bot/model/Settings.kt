package com.trading.bot.model

import java.math.BigDecimal

data class BotSettings(
    val botIntervalMs: Long,
    val strategyIntervalMs: Long,
    val monitorIntervalMs: Long,
    val maxOpenPositionsForNewEntry: Int,
    val tradingMode: String,
    val maxPositionRub: BigDecimal,
    val maxDailyLossRub: BigDecimal,
    val stopLossPercent: Double,
    val takeProfitPercent: Double,
    val trailingStopEnabled: Boolean,
    val trailingStopPercent: Double
)
