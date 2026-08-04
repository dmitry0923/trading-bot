package com.trading.bot.backtest

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Метрики результата бэктеста.
 */
data class BacktestResult(
    val ticker: String,
    val totalReturn: Double,
    val sharpeRatio: Double,
    val maxDrawdown: Double,
    val winRate: Double,
    val profitFactor: Double,
    val totalTrades: Int,
    val avgHoldBars: Double,
    val equityCurve: List<BigDecimal>,
    val monthlyReturns: Map<String, Double>,
) {
    /**
     * Критерии приёма стратегии в прод:
     * Sharpe > 1.2, MDD < 15%, Profit Factor > 1.3, >= 100 сделок.
     */
    fun isPassable(): Boolean =
        sharpeRatio > 1.2 &&
            maxDrawdown < 0.15 &&
            profitFactor > 1.3 &&
            totalTrades >= 100
}

object BacktestMetrics {
    fun compute(
        ticker: String,
        equityCurve: List<BigDecimal>,
        tradeReturns: List<Double>,
    ): BacktestResult {
        val totalReturn =
            if (equityCurve.size >= 2 && equityCurve.first() > BigDecimal.ZERO) {
                equityCurve
                    .last()
                    .subtract(equityCurve.first())
                    .divide(equityCurve.first(), 6, RoundingMode.HALF_UP)
                    .toDouble()
            } else {
                0.0
            }

        val sharpe = sharpeRatio(tradeReturns)
        val mdd = maxDrawdown(equityCurve)
        val wins = tradeReturns.count { it > 0 }
        val winRate = if (tradeReturns.isNotEmpty()) wins.toDouble() / tradeReturns.size else 0.0

        val grossProfit = tradeReturns.filter { it > 0 }.sum()
        val grossLoss = tradeReturns.filter { it < 0 }.sum().let { if (it == 0.0) 0.0 else -it }
        val profitFactor =
            if (grossLoss > 0) {
                grossProfit / grossLoss
            } else if (grossProfit > 0) {
                Double.POSITIVE_INFINITY
            } else {
                0.0
            }

        return BacktestResult(
            ticker = ticker,
            totalReturn = totalReturn,
            sharpeRatio = sharpe,
            maxDrawdown = mdd,
            winRate = winRate,
            profitFactor = profitFactor,
            totalTrades = tradeReturns.size,
            avgHoldBars = 0.0,
            equityCurve = equityCurve,
            monthlyReturns = emptyMap(),
        )
    }

    fun sharpeRatio(
        periodReturns: List<Double>,
        rfPerPeriod: Double = 0.0,
    ): Double {
        if (periodReturns.size < 2) return 0.0
        val mean = periodReturns.average()
        val variance = periodReturns.map { (it - mean) * (it - mean) }.average()
        if (variance == 0.0) return 0.0
        val std = kotlin.math.sqrt(variance)
        return (mean - rfPerPeriod) / std * kotlin.math.sqrt(periodReturns.size.toDouble())
    }

    fun maxDrawdown(equityCurve: List<BigDecimal>): Double {
        if (equityCurve.size < 2) return 0.0
        var peak = equityCurve.first()
        var maxDd = 0.0
        for (eq in equityCurve) {
            if (eq > peak) peak = eq
            val dd = BigDecimal.ONE.subtract(eq.divide(peak, 6, RoundingMode.HALF_UP)).toDouble()
            if (dd > maxDd) maxDd = dd
        }
        return maxDd
    }
}
