package com.trading.bot.backtest

import java.math.BigDecimal
import java.time.LocalDateTime

data class BacktestResult(
    val ticker: String,
    val startDate: LocalDateTime,
    val endDate: LocalDateTime,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRate: Double,
    val totalReturn: BigDecimal,
    val totalReturnPercent: Double,
    val maxDrawdown: BigDecimal,
    val maxDrawdownPercent: Double,
    val sharpeRatio: Double,
    val profitFactor: Double,
    val averageWin: BigDecimal,
    val averageLoss: BigDecimal,
    val equityCurve: List<EquityPoint>,
    val trades: List<BacktestTrade>
)

data class EquityPoint(
    val timestamp: LocalDateTime,
    val equity: BigDecimal
)

data class BacktestTrade(
    val entryTime: LocalDateTime,
    val exitTime: LocalDateTime?,
    val direction: String,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal?,
    val quantity: Int,
    val pnl: BigDecimal?,
    val pnlPercent: Double?,
    val exitReason: String?
)
