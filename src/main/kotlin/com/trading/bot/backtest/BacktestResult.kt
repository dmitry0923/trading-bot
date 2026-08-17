package com.trading.bot.backtest

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Метрики результата бэктеста (C-002).
 *
 * Помимо базовых (Sharpe/MDD/PF/win rate) содержит Sortino, Calmar, Expectancy,
 * Win-Loss Ratio, AvgTrade и Recovery Factor — для полноценной оценки
 * устойчивости стратегии (не только доходности, но и качества сделок).
 *
 * Sharpe/Sortino/Calmar считаются по КРИВОЙ КАПИТАЛА (периодные доходности
 * equityCurve), а не по сделкам: риск-метрики должны учитывать путь капитала
 * во времени и продолжительность удержания позиции, иначе каждая сделка
 * равновзвешена независимо от того, сколько баров она была открыта.
 */
data class BacktestResult(
    val ticker: String,
    val totalReturn: Double,
    val sharpeRatio: Double,
    val sortinoRatio: Double = 0.0,
    val maxDrawdown: Double,
    val winRate: Double,
    val profitFactor: Double,
    val totalTrades: Int,
    val avgHoldBars: Double,
    val equityCurve: List<BigDecimal>,
    val monthlyReturns: Map<String, Double>,
    val expectancy: Double = 0.0,
    val winLossRatio: Double = 0.0,
    val avgTrade: Double = 0.0,
    val recoveryFactor: Double = 0.0,
    val calmarRatio: Double = 0.0,
    val tradeReturns: List<Double> = emptyList(),
    /** Суммарная комиссия за все сделки (рубли). */
    val totalCommissionPaid: BigDecimal = BigDecimal.ZERO,
    /** Доля комиссий в общей прибыли: commission / |gross_profit| × 100. */
    val costDragPercent: Double = 0.0,
) {
    /**
     * Критерии приёма стратегии в прод:
     * Sharpe > 1.2, MDD < 15%, Profit Factor > 1.3, >= 200 сделок.
     */
    fun isPassable(): Boolean =
        sharpeRatio > 1.2 &&
            maxDrawdown < 0.15 &&
            profitFactor > 1.3 &&
            totalTrades >= 200

    /**
     * Компактное представление метрик для персиста в `backtest_results`
     * (roadmap v2.2, 13.7.3): без equityCurve/monthlyReturns/tradeReturns.
     */
    fun metrics(): Map<String, Any> =
        mapOf(
            "totalReturn" to totalReturn,
            "sharpeRatio" to sharpeRatio,
            "sortinoRatio" to sortinoRatio,
            "maxDrawdown" to maxDrawdown,
            "winRate" to winRate,
            "profitFactor" to profitFactor,
            "totalTrades" to totalTrades,
            "avgHoldBars" to avgHoldBars,
            "expectancy" to expectancy,
            "winLossRatio" to winLossRatio,
            "avgTrade" to avgTrade,
            "recoveryFactor" to recoveryFactor,
            "calmarRatio" to calmarRatio,
            "totalCommissionPaid" to totalCommissionPaid.toDouble(),
            "costDragPercent" to costDragPercent,
            "passable" to isPassable(),
        )
}

object BacktestMetrics {
    fun compute(
        ticker: String,
        equityCurve: List<BigDecimal>,
        tradeReturns: List<Double>,
        holdBars: List<Int> = emptyList(),
        totalCommission: BigDecimal = BigDecimal.ZERO,
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

        // Sharpe/Sortino по периодным доходностям КРИВОЙ КАПИТАЛА, а не по сделкам:
        // учитывается путь капитала и время удержания позиции.
        val periodReturns = periodReturnsFromEquity(equityCurve)
        val sharpe = sharpeRatio(periodReturns)
        val sortino = sortinoRatio(periodReturns)
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

        val avgTrade = if (tradeReturns.isNotEmpty()) tradeReturns.average() else 0.0
        val avgWin = tradeReturns.filter { it > 0 }.let { if (it.isEmpty()) 0.0 else it.average() }
        val avgLoss = tradeReturns.filter { it < 0 }.let { if (it.isEmpty()) 0.0 else abs(it.average()) }
        val lossRate = 1.0 - winRate
        // Expectancy (Van Tharp): (Win% × AvgWin) − (Loss% × |AvgLoss|), в $ на сделку.
        val expectancy = winRate * avgWin - lossRate * avgLoss
        val winLossRatio =
            if (avgLoss > 0) {
                avgWin / avgLoss
            } else if (avgWin > 0) {
                Double.POSITIVE_INFINITY
            } else {
                0.0
            }
        // Чистая прибыль по кривой капитала (согласована с totalReturn/mdd).
        val netProfit =
            if (equityCurve.size >= 2) {
                equityCurve.last().subtract(equityCurve.first()).toDouble()
            } else {
                tradeReturns.sum()
            }
        val recoveryFactor =
            if (mdd > 0) {
                netProfit / mdd
            } else if (netProfit > 0) {
                Double.POSITIVE_INFINITY
            } else {
                0.0
            }
        val calmarRatio =
            if (mdd > 0) {
                totalReturn / mdd
            } else if (totalReturn > 0) {
                Double.POSITIVE_INFINITY
            } else {
                0.0
            }

        val costDragPercent =
            if (grossProfit > 0) {
                totalCommission.toDouble() / grossProfit * 100.0
            } else {
                0.0
            }

        return BacktestResult(
            ticker = ticker,
            totalReturn = totalReturn,
            sharpeRatio = sharpe,
            sortinoRatio = sortino,
            maxDrawdown = mdd,
            winRate = winRate,
            profitFactor = profitFactor,
            totalTrades = tradeReturns.size,
            avgHoldBars = if (holdBars.isNotEmpty()) holdBars.average() else 0.0,
            equityCurve = equityCurve,
            monthlyReturns = emptyMap(),
            expectancy = expectancy,
            winLossRatio = winLossRatio,
            avgTrade = avgTrade,
            recoveryFactor = recoveryFactor,
            calmarRatio = calmarRatio,
            tradeReturns = tradeReturns,
            totalCommissionPaid = totalCommission,
            costDragPercent = costDragPercent,
        )
    }

    /**
     * Периодные доходности кривой капитала: `(E(i) - E(i-1)) / E(i-1)`.
     * Точки с неположительным знаменателем пропускаются (нет смысла в доходности
     * от нулевого/отрицательного капитала).
     */
    fun periodReturnsFromEquity(equityCurve: List<BigDecimal>): List<Double> {
        if (equityCurve.size < 2) return emptyList()
        val returns = ArrayList<Double>(equityCurve.size - 1)
        for (i in 1 until equityCurve.size) {
            val prev = equityCurve[i - 1]
            if (prev > BigDecimal.ZERO) {
                returns.add(
                    equityCurve[i]
                        .subtract(prev)
                        .divide(prev, 8, RoundingMode.HALF_UP)
                        .toDouble(),
                )
            }
        }
        return returns
    }

    fun sharpeRatio(
        periodReturns: List<Double>,
        rfPerPeriod: Double = 0.0,
    ): Double {
        if (periodReturns.size < 2) return 0.0
        val mean = periodReturns.average()
        val variance = periodReturns.map { (it - mean) * (it - mean) }.average()
        if (variance == 0.0) return 0.0
        val std = sqrt(variance)
        return (mean - rfPerPeriod) / std * sqrt(periodReturns.size.toDouble())
    }

    /**
     * Sortino: учитывает только отрицательные отклонения (downside deviation).
     * Наказывает волатильность вниз, а не весь разброс.
     */
    fun sortinoRatio(
        periodReturns: List<Double>,
        rfPerPeriod: Double = 0.0,
    ): Double {
        if (periodReturns.size < 2) return 0.0
        val mean = periodReturns.average()
        val downside = periodReturns.map { (it - rfPerPeriod).coerceAtMost(0.0) }
        val downsideVariance = downside.map { it * it }.average()
        if (downsideVariance == 0.0) return 0.0
        return (mean - rfPerPeriod) / sqrt(downsideVariance) * sqrt(periodReturns.size.toDouble())
    }

    fun maxDrawdown(equityCurve: List<BigDecimal>): Double {
        if (equityCurve.size < 2) return 0.0
        var peak = equityCurve.first()
        if (peak <= BigDecimal.ZERO) return 0.0
        var maxDd = 0.0
        for (eq in equityCurve) {
            if (eq > peak) peak = eq
            val dd = BigDecimal.ONE.subtract(eq.divide(peak, 6, RoundingMode.HALF_UP)).toDouble()
            if (dd > maxDd) maxDd = dd
        }
        return maxDd
    }
}
