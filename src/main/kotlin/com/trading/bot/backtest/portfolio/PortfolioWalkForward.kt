package com.trading.bot.backtest.portfolio

import com.trading.bot.backtest.BacktestMetrics
import com.trading.bot.backtest.BacktestResult
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Walk-forward валидация research-портфеля cross-sectional momentum + carry
 * (`docs/26`), аналог [com.trading.bot.backtest.pairs.PairsWalkForward] для
 * многомерного юниверса.
 *
 * У портфеля нет in-sample сетки (все параметры фиксированы заранее), поэтому фолды —
 * чистые OOS-окна: история режется на [folds] последовательных равных сегментов, движок
 * прогоняется независимо на каждом (свой warmup [PortfolioMomentumEngine.Params.lookbackBars]).
 * Вердикт — по агрегату сделок фолдов и [consistency] (доля фолдов с положительной
 * доходностью). Live-путь не затрагивается.
 */
object PortfolioWalkForward {
    /** Результат одного фолда (OOS-окна). */
    data class Fold(
        val foldIndex: Int,
        val from: LocalDateTime,
        val to: LocalDateTime,
        val result: BacktestResult,
    )

    /** Агрегированный результат walk-forward. */
    data class Result(
        val folds: List<Fold>,
        val aggregate: BacktestResult,
        /** Доля фолдов с положительной доходностью (0..1). */
        val consistency: Double,
        /** OOS Sharpe > 0.5, PF > 1.1, consistency >= 0.6, >= 100 сделок. */
        val robust: Boolean,
        val verdict: String,
    )

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    fun run(
        bars: List<PortfolioMomentumEngine.Bar>,
        specs: List<PortfolioMomentumEngine.LegSpec>,
        params: PortfolioMomentumEngine.Params,
        folds: Int = 6,
        funding: List<Map<LocalDate, Double>> = emptyList(),
        isTradingDay: (LocalDate) -> Boolean = { true },
    ): Result {
        val label = specs.joinToString("+") { it.ticker }
        val effectiveFolds = folds.coerceAtLeast(2)
        val minBarsPerFold = params.lookbackBars + params.rebalanceBars + 2
        if (bars.size < minBarsPerFold * effectiveFolds) {
            val need = minBarsPerFold * effectiveFolds
            return Result(
                folds = emptyList(),
                aggregate = emptyResult(label),
                consistency = 0.0,
                robust = false,
                verdict = "INCONCLUSIVE (bars ${bars.size} < $need for $effectiveFolds folds)",
            )
        }

        val segment = bars.size / effectiveFolds
        val foldsRuns =
            (0 until effectiveFolds).map { i ->
                val start = i * segment
                val end = if (i == effectiveFolds - 1) bars.size else (i + 1) * segment
                val slice = bars.subList(start, end)
                val result =
                    PortfolioMomentumEngine.run(
                        bars = slice,
                        specs = specs,
                        params = params,
                        funding = funding,
                        isTradingDay = isTradingDay,
                    )
                Fold(
                    foldIndex = i,
                    from = slice.first().time,
                    to = slice.last().time,
                    result = metricsFrom(label, result.equityCurve, result.equityTimestamps, result.trades),
                )
            }

        val aggregate = aggregate(label, foldsRuns, params.initialCapital)
        val consistency = foldsRuns.count { it.result.totalReturn > 0 }.toDouble() / foldsRuns.size
        val robust =
            aggregate.sharpeRatio > 0.5 &&
                aggregate.profitFactor > 1.1 &&
                consistency >= 0.6 &&
                aggregate.totalTrades >= 100
        return Result(
            folds = foldsRuns,
            aggregate = aggregate,
            consistency = consistency,
            robust = robust,
            verdict = verdict(aggregate, consistency),
        )
    }

    private fun metricsFrom(
        label: String,
        equity: List<Double>,
        timestamps: List<LocalDateTime>,
        trades: List<PortfolioMomentumEngine.Trade>,
    ): BacktestResult =
        BacktestMetrics.compute(
            ticker = label,
            equityCurve = equity.map { BigDecimal.valueOf(it) },
            equityTimestamps = timestamps,
            tradeReturns = trades.map { it.netPnl },
            holdBars = trades.map { it.holdBars },
            totalCommission = BigDecimal.valueOf(trades.sumOf { it.commission }),
            isFutures = true,
        )

    private fun aggregate(
        label: String,
        folds: List<Fold>,
        initialCapital: Double,
    ): BacktestResult {
        val returns = folds.flatMap { it.result.tradeReturns }
        if (returns.isEmpty()) return emptyResult(label)
        val equity = ArrayList<BigDecimal>(returns.size + 1)
        var acc = BigDecimal.valueOf(initialCapital)
        equity.add(acc)
        for (r in returns) {
            acc = acc.add(BigDecimal.valueOf(r))
            equity.add(acc)
        }
        val totalTrades = returns.size
        val avgHold = folds.sumOf { it.result.avgHoldBars * it.result.totalTrades } / totalTrades
        val commission = folds.fold(BigDecimal.ZERO) { a, f -> a.add(f.result.totalCommissionPaid) }
        return BacktestMetrics
            .compute(
                ticker = label,
                equityCurve = equity,
                tradeReturns = returns,
                totalCommission = commission,
                isFutures = true,
            ).copy(avgHoldBars = avgHold)
    }

    private fun verdict(
        aggregate: BacktestResult,
        consistency: Double,
    ): String =
        when {
            aggregate.totalTrades < 30 -> "INCONCLUSIVE (trades ${aggregate.totalTrades} < 30)"
            consistency < 0.6 -> "REJECTED (consistency ${"%.3f".format(consistency)})"
            aggregate.profitFactor < 1.3 -> "REJECTED (oos PF ${"%.3f".format(aggregate.profitFactor)})"
            aggregate.probabilityOfNoEdge > 0.05 -> "INCONCLUSIVE (P(noEdge) ${"%.3f".format(aggregate.probabilityOfNoEdge)})"
            else -> "CANDIDATE (oos PF ${"%.3f".format(aggregate.profitFactor)}, consistency ${"%.3f".format(consistency)})"
        }

    private fun emptyResult(label: String): BacktestResult =
        BacktestMetrics.compute(label, emptyList(), tradeReturns = emptyList(), isFutures = true)
}
