package com.trading.bot.backtest

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
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
    /** Фьючерсный инструмент — применяются отдельные acceptance-пороги. */
    val isFutures: Boolean = false,
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
    /** Нижняя граница 95% bootstrap-CI среднего P&L сделки. */
    val meanTradeCI95Low: Double = 0.0,
    /** Верхняя граница 95% bootstrap-CI среднего P&L сделки. */
    val meanTradeCI95High: Double = 0.0,
    /** Доля bootstrap-средних с P&L <= 0 (аналог p-value для edge > 0). */
    val probabilityOfNoEdge: Double = 1.0,
    /** true, если edge статистически значим на 95% (probabilityOfNoEdge <= 0.05). */
    val edgeStatisticallySignificant: Boolean = false,
    /** Число bootstrap-итераций для оценки значимости. */
    val significanceSimulations: Int = 0,
) {
    /**
     * Критерии приёма стратегии в прод, зависят от класса инструмента.
     *
     * Акции (длинный горизонт, короткие SL/TP, много сделок):
     *   Sharpe > 1.2, MDD < 15%, Profit Factor > 1.3, >= 200 сделок.
     *
     * Фьючерсы (длинные SL/TP в пунктах, 6-22 сделки/год — панельная калибровка
     * из AGENTS.md): MDD <= 40% (калибровочный оптимум CNYRUBF), PF > 1.3,
     * >= 10 сделок, Sharpe > 1.2. MDD<15%/>=200 сделок физически нереализуемы
     * для длинных фьючерсных панельных стратегий (SL 300 пт, SL срабатывает
     * раньше liq-уровня) — единый порог с акциями навсегда отклонял бы их.
     */
    fun isPassable(): Boolean {
        val tradesThreshold = if (isFutures) 10 else 200
        val mddThreshold = if (isFutures) 0.40 else 0.15
        return sharpeRatio > 1.2 &&
            maxDrawdown <= mddThreshold &&
            profitFactor > 1.3 &&
            totalTrades >= tradesThreshold
    }

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
            "edgeStatisticallySignificant" to edgeStatisticallySignificant,
            "meanTradeCI95Low" to meanTradeCI95Low,
            "meanTradeCI95High" to meanTradeCI95High,
            "probabilityOfNoEdge" to probabilityOfNoEdge,
            "significanceSimulations" to significanceSimulations,
            "passable" to isPassable(),
        )
}

object BacktestMetrics {
    fun compute(
        ticker: String,
        equityCurve: List<BigDecimal>,
        equityTimestamps: List<LocalDateTime> = emptyList(),
        tradeReturns: List<Double>,
        holdBars: List<Int> = emptyList(),
        totalCommission: BigDecimal = BigDecimal.ZERO,
        isFutures: Boolean = false,
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

        val monthlyReturns = computeMonthlyReturns(equityCurve, equityTimestamps)

        val sig = TradeSignificance.bootstrap(tradeReturns)

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
            isFutures = isFutures,
            equityCurve = equityCurve,
            monthlyReturns = monthlyReturns,
            expectancy = expectancy,
            winLossRatio = winLossRatio,
            avgTrade = avgTrade,
            recoveryFactor = recoveryFactor,
            calmarRatio = calmarRatio,
            tradeReturns = tradeReturns,
            totalCommissionPaid = totalCommission,
            costDragPercent = costDragPercent,
            meanTradeCI95Low = sig.ci95Low,
            meanTradeCI95High = sig.ci95High,
            probabilityOfNoEdge = sig.probabilityOfNoEdge,
            edgeStatisticallySignificant = sig.edgeStatisticallySignificant,
            significanceSimulations = sig.simulations,
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

    /**
     * Monthly returns: key = "yyyy-MM", value = (last_equity_in_month - first_equity_in_month) / first_equity_in_month.
     * Calendar months. Months with only one equity point yield 0.0.
     */
    fun computeMonthlyReturns(
        equityCurve: List<BigDecimal>,
        equityTimestamps: List<LocalDateTime>,
    ): Map<String, Double> {
        if (equityCurve.size < 2 || equityTimestamps.size < 2) return emptyMap()

        val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

        data class MonthEntry(
            val equity: BigDecimal,
            val month: YearMonth,
        )
        val entries =
            equityCurve.zip(equityTimestamps) { eq, t ->
                MonthEntry(eq, YearMonth.from(t))
            }

        val grouped = linkedMapOf<YearMonth, MutableList<BigDecimal>>()
        for (e in entries) {
            grouped.getOrPut(e.month) { mutableListOf() }.add(e.equity)
        }

        val result = linkedMapOf<String, Double>()
        for ((month, equities) in grouped) {
            if (equities.size < 2 || equities.first() <= BigDecimal.ZERO) {
                result[month.format(monthFormatter)] = 0.0
                continue
            }
            val start = equities.first()
            val end = equities.last()
            result[month.format(monthFormatter)] =
                end
                    .subtract(start)
                    .divide(start, 6, RoundingMode.HALF_UP)
                    .toDouble()
        }
        return result
    }
}
