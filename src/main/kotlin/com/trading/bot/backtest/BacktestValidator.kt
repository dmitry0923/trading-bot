package com.trading.bot.backtest

import com.trading.bot.model.entity.Candle
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Результат walk-forward валидации стратегии (C-002).
 *
 * Оценка «устойчивости» выходит за рамки in-sample бэктеста: стратегия
 * прогоняется на out-of-sample окнах (train -> tune SL/TP -> test), и решающими
 * становятся агрегированные OOS-метрики и консистентность по фолдам.
 */
data class ValidationResult(
    val folds: List<FoldValidation>,
    val aggregateOutOfSample: BacktestResult,
) {
    /** Доля фолдов с положительной OOS-доходностью (0..1). */
    val consistency: Double
        get() =
            if (folds.isEmpty()) {
                0.0
            } else {
                folds.count { it.outOfSample.totalReturn > 0 }.toDouble() / folds.size
            }

    /**
     * Критерии устойчивости: OOS Sharpe > 0.5, OOS PF > 1.1, >= 60% прибыльных
     * фолдов, >= 100 OOS-сделок. Проваливается при переобучении (train хорош,
     * OOS нет) и при тонком распределении сделок.
     */
    fun isRobust(): Boolean =
        aggregateOutOfSample.sharpeRatio > 0.5 &&
            aggregateOutOfSample.profitFactor > 1.1 &&
            consistency >= 0.6 &&
            aggregateOutOfSample.totalTrades >= 100
}

/** Результат одного фолда walk-forward валидации. */
data class FoldValidation(
    val foldIndex: Int,
    val inSample: BacktestResult,
    val outOfSample: BacktestResult,
    val chosenSlPercent: Double,
    val chosenTpPercent: Double,
)

/** Параметризованный прогон in-sample окна для walk-forward. */
private data class Candidate(
    val sl: Double,
    val tp: Double,
    val result: BacktestResult,
)

/**
 * Walk-forward валидация (скользящее окно, C-002).
 *
 * Свечи делятся на последовательные фолды. Для каждого фолда:
 *  1. in-sample (train) окно — подбор SL/TP по сетке параметров;
 *  2. out-of-sample (test) окно — прогон с выбранными параметрами (данные,
 *     не участвовавшие в настройке);
 *  3. агрегация всех OOS-сделок -> [ValidationResult].
 *
 * Если стратегия устойчива, качество на train сохраняется на test;
 * переобучение выявляется расхождением train/test метрик.
 */
@Component
class BacktestValidator(
    private val backtestEngine: BacktestEngine,
) {
    private val logger = KotlinLogging.logger {}

    /** Сетка параметров для in-sample настройки: пары (SL%, TP%). */
    private val parameterGrid =
        listOf(
            0.01 to 0.02,
            0.02 to 0.04,
            0.03 to 0.06,
        )

    /**
     * Walk-forward прогон по свечам тикера.
     *
     * @param ticker тикер
     * @param candles исторические свечи (сортировка по времени выполняется внутри)
     * @param folds количество фолдов (>= 2)
     * @param initialCapital стартовый капитал для каждого окна
     * @param minBarsForSignal минимальное число баров для сигнала
     * @return [ValidationResult]; при недостатке данных — пустой (не robust)
     */
    fun validate(
        ticker: String,
        candles: List<Candle>,
        folds: Int = 4,
        initialCapital: BigDecimal = BigDecimal("100000"),
        minBarsForSignal: Int = 30,
    ): ValidationResult {
        val sorted = candles.sortedBy { it.time }
        if (folds < 2 || sorted.size < minBarsForSignal * (folds + 1)) {
            logger.warn { "Walk-forward $ticker: insufficient candles (${sorted.size}), cannot validate" }
            return ValidationResult(
                folds = emptyList(),
                aggregateOutOfSample = emptyResult(ticker),
            )
        }

        val segment = sorted.size / folds
        val foldResults =
            (0 until folds).map { i ->
                val testStart = i * segment
                val testEnd = if (i == folds - 1) sorted.size else (i + 1) * segment
                val train = sorted.subList(0, testStart)
                val test = sorted.subList(testStart, testEnd)

                val (sl, tp) =
                    if (train.size >= minBarsForSignal * 2) {
                        tuneParams(ticker, train, initialCapital, minBarsForSignal)
                    } else {
                        parameterGrid.first()
                    }
                val inSample = backtestEngine.simulate(ticker, train, initialCapital, minBarsForSignal, sl, tp)
                val outOfSample = backtestEngine.simulate(ticker, test, initialCapital, minBarsForSignal, sl, tp)
                FoldValidation(
                    foldIndex = i,
                    inSample = inSample,
                    outOfSample = outOfSample,
                    chosenSlPercent = sl,
                    chosenTpPercent = tp,
                )
            }

        val aggregate = aggregateOutOfSample(ticker, foldResults, initialCapital)
        return ValidationResult(foldResults, aggregate)
    }

    /**
     * Подбор (SL, TP) на in-sample окне: максимизирует Profit Factor при
     * достаточном числе сделок, при равенстве — по Sharpe.
     */
    private fun tuneParams(
        ticker: String,
        train: List<Candle>,
        initialCapital: BigDecimal,
        minBarsForSignal: Int,
    ): Pair<Double, Double> {
        val candidates =
            parameterGrid.map { (sl, tp) ->
                val r = backtestEngine.simulate(ticker, train, initialCapital, minBarsForSignal, sl, tp)
                Candidate(sl, tp, r)
            }
        val best =
            candidates.maxWithOrNull(
                compareBy<Candidate>(
                    { it.result.profitFactor.isFinite() && it.result.totalTrades >= 30 },
                    { it.result.profitFactor },
                    { it.result.sharpeRatio },
                ),
            )
        return best?.let { it.sl to it.tp } ?: parameterGrid.first()
    }

    /**
     * Агрегация OOS-сделок всех фолдов: сводная кривая капитала строится
     * накоплением P&L от стартового капитала (без скачков на границах фолдов),
     * метрики считаются по объединённым OOS-сделкам.
     */
    private fun aggregateOutOfSample(
        ticker: String,
        folds: List<FoldValidation>,
        initialCapital: BigDecimal,
    ): BacktestResult {
        val tradeReturns = folds.flatMap { it.outOfSample.tradeReturns }
        if (tradeReturns.isEmpty()) return emptyResult(ticker)
        val equity = ArrayList<BigDecimal>(tradeReturns.size + 1)
        var acc = initialCapital
        equity.add(initialCapital)
        for (r in tradeReturns) {
            acc = acc.add(BigDecimal.valueOf(r))
            equity.add(acc)
        }
        return BacktestMetrics.compute(ticker, equity, tradeReturns)
    }

    private fun emptyResult(ticker: String): BacktestResult = BacktestMetrics.compute(ticker, emptyList(), emptyList())
}
