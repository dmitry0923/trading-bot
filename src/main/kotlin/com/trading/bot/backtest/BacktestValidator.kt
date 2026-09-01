package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import com.trading.bot.config.InstrumentsConfig
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

    /**
     * Комбинированная проходимость: стратегия принимается только если она
     * устойчива ([isRobust] — консистентность, отсутствие переобучения)
     * И агрегированные OOS-метрики проходят минимальные пороги приёмки
     * [BacktestResult.isPassable] (пороги зависят от класса инструмента:
     * акции Sharpe > 1.2, MDD < 15%, PF > 1.3, >= 200 сделок; фьючерсы
     * MDD <= 40%, PF > 1.3, >= 10 сделок).
     */
    fun isPassable(): Boolean = isRobust() && aggregateOutOfSample.isPassable()
}

/** Результат одного фолда walk-forward валидации. */
data class FoldValidation(
    val foldIndex: Int,
    val inSample: BacktestResult,
    val outOfSample: BacktestResult,
    val chosenSlPercent: Double,
    val chosenTpPercent: Double,
    /** Для фьючерсов настройка идёт в пунктах (BT-004); акции — null. */
    val chosenSlPoints: Int? = null,
    val chosenTpPoints: Int? = null,
)

/** Параметры сетки настройки SL/TP: акции — проценты, фьючерсы — пункты. */
data class GridParams(
    val slPercent: Double = 0.0,
    val tpPercent: Double = 0.0,
    val slPoints: Int? = null,
    val tpPoints: Int? = null,
)

/** Параметризованный прогон in-sample окна для walk-forward. */
private data class Candidate(
    val params: GridParams,
    val result: BacktestResult,
)

/**
 * Walk-forward валидация (расширяющееся окно, C-002).
 *
 * Свечи делятся на последовательные фолды. Для каждого фолда:
 *  1. in-sample (train) окно — подбор SL/TP по сетке параметров;
 *  2. out-of-sample (test) окно — прогон с выбранными параметрами (данные,
 *     не участвовавшие в настройке);
 *  3. агрегация всех OOS-сделок -> [ValidationResult].
 *
 * При [BacktestValidator.validate] с expanding-окном (по умолчанию) train
 * каждого фолда — ВСЯ история до начала test-окна, т.е. train монотонно
 * растёт: fold 0 -> [0, seg), fold 1 -> [0, 2·seg), ... Fold 0 при этом
 * намеренно имеет пустой train (нет истории для настройки) и использует
 * первую пару сетки как дефолт — это корректное поведение расширяющегося
 * окна, а не баг.
 *
 * Если стратегия устойчива, качество на train сохраняется на test;
 * переобучение выявляется расхождением train/test метрик.
 */
@Component
class BacktestValidator(
    private val backtestEngine: BacktestEngine,
    private val backtestConfig: BacktestConfig = BacktestConfig(),
    private val instrumentsConfig: InstrumentsConfig = InstrumentsConfig(),
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Сетка параметров для in-sample настройки акций: пары (SL%, TP%).
     * Фьючерсы настраиваются в ПУНКТАХ ([futuresGrid]) — проценты для них
     * игнорируются движком (SL/TP в пунктах, BT-004).
     */
    private val stockGrid =
        listOf(
            GridParams(slPercent = 0.01, tpPercent = 0.02),
            GridParams(slPercent = 0.02, tpPercent = 0.04),
            GridParams(slPercent = 0.03, tpPercent = 0.06),
        )

    /** Сетка настройки фьючерсов в пунктах (R:R 1:2 вокруг [com.trading.bot.config.RiskConfig.defaultStopLossPoints]=50). */
    private val futuresGrid =
        listOf(
            GridParams(slPoints = 25, tpPoints = 50),
            GridParams(slPoints = 50, tpPoints = 100),
            GridParams(slPoints = 100, tpPoints = 200),
            GridParams(slPoints = 150, tpPoints = 300),
            GridParams(slPoints = 300, tpPoints = 600),
        )

    private fun gridFor(ticker: String): List<GridParams> = if (instrumentsConfig.isFutures(ticker)) futuresGrid else stockGrid

    /**
     * Walk-forward прогон по свечам тикера.
     *
     * @param ticker тикер
     * @param candles исторические свечи (сортировка по времени выполняется внутри)
     * @param folds количество фолдов (>= 2)
     * @param expanding режим train-окна: `true` (по умолчанию) — расширяющееся
     *   окно, train фолда i = candles[0 .. i·segment) (вся история до test);
     *   fold 0 имеет пустой train и использует дефолтные параметры первой пары
     *   сетки. `false` — скользящее окно фиксированной длины segment,
     *   предшествующее test-окну.
     * @param initialCapital стартовый капитал для каждого окна
     * @param minBarsForSignal минимальное число баров для сигнала
     * @return [ValidationResult]; при недостатке данных — пустой (не robust)
     */
    suspend fun validate(
        ticker: String,
        candles: List<Candle>,
        folds: Int = 4,
        expanding: Boolean = true,
        initialCapital: BigDecimal = backtestConfig.initialCapital,
        minBarsForSignal: Int = backtestConfig.minBarsForSignal,
        leverage: Double = 1.0,
        riskPerTradePercent: Double? = null,
        futuresMaxContractsPerPosition: Int? = null,
        signalGeneratorOverride: BacktestSignalGenerator? = null,
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
                val train =
                    if (expanding) {
                        sorted.subList(0, testStart)
                    } else {
                        sorted.subList(maxOf(0, testStart - segment), testStart)
                    }
                val test = sorted.subList(testStart, testEnd)

                val params =
                    if (train.size >= minBarsForSignal * 2) {
                        tuneParams(
                            ticker,
                            train,
                            initialCapital,
                            minBarsForSignal,
                            leverage,
                            riskPerTradePercent,
                            futuresMaxContractsPerPosition,
                            signalGeneratorOverride,
                        )
                    } else {
                        gridFor(ticker).first()
                    }
                val inSample =
                    simulateWith(
                        params,
                        ticker,
                        train,
                        initialCapital,
                        minBarsForSignal,
                        leverage,
                        riskPerTradePercent,
                        futuresMaxContractsPerPosition,
                        signalGeneratorOverride,
                    )
                val outOfSample =
                    simulateWith(
                        params,
                        ticker,
                        test,
                        initialCapital,
                        minBarsForSignal,
                        leverage,
                        riskPerTradePercent,
                        futuresMaxContractsPerPosition,
                        signalGeneratorOverride,
                    )
                FoldValidation(
                    foldIndex = i,
                    inSample = inSample,
                    outOfSample = outOfSample,
                    chosenSlPercent = params.slPercent,
                    chosenTpPercent = params.tpPercent,
                    chosenSlPoints = params.slPoints,
                    chosenTpPoints = params.tpPoints,
                )
            }

        val aggregate = aggregateOutOfSample(ticker, foldResults, initialCapital)
        return ValidationResult(foldResults, aggregate)
    }

    /**
     * Прогон с параметрами настройки. Фьючерсы — через [GridParams.slPoints]/
     * [GridParams.tpPoints] (в пунктах); акции — через проценты (8-арг вызов,
     * движок использует ATR/дефолты для пунктовых стопов).
     */
    private suspend fun simulateWith(
        params: GridParams,
        ticker: String,
        candles: List<Candle>,
        initialCapital: BigDecimal,
        minBarsForSignal: Int,
        leverage: Double,
        riskPerTradePercent: Double?,
        futuresMaxContractsPerPosition: Int?,
        signalGeneratorOverride: BacktestSignalGenerator?,
    ): BacktestResult =
        backtestEngine.simulate(
            ticker,
            candles,
            initialCapital,
            minBarsForSignal,
            params.slPercent,
            params.tpPercent,
            commissionMultiplier = 1.0,
            slippageMultiplier = 1.0,
            slPoints = params.slPoints,
            tpPoints = params.tpPoints,
            leverage = leverage,
            capitalSlice = null,
            riskPerTradePercent = riskPerTradePercent,
            futuresMaxContractsPerPosition = futuresMaxContractsPerPosition,
            signalGeneratorOverride = signalGeneratorOverride,
        )

    /**
     * Подбор (SL, TP) на in-sample окне: максимизирует Profit Factor при
     * достаточном числе сделок, при равенстве — по Sharpe. Сетка зависит от
     * типа инструмента: акции — проценты, фьючерсы — пункты (BT-004).
     */
    private suspend fun tuneParams(
        ticker: String,
        train: List<Candle>,
        initialCapital: BigDecimal,
        minBarsForSignal: Int,
        leverage: Double,
        riskPerTradePercent: Double?,
        futuresMaxContractsPerPosition: Int?,
        signalGeneratorOverride: BacktestSignalGenerator?,
    ): GridParams {
        val candidates =
            gridFor(ticker).map { params ->
                Candidate(
                    params,
                    simulateWith(
                        params,
                        ticker,
                        train,
                        initialCapital,
                        minBarsForSignal,
                        leverage,
                        riskPerTradePercent,
                        futuresMaxContractsPerPosition,
                        signalGeneratorOverride,
                    ),
                )
            }
        val best =
            candidates.maxWithOrNull(
                compareBy<Candidate>(
                    { it.result.totalTrades >= 30 },
                    { it.result.profitFactor },
                    { it.result.sharpeRatio },
                ),
            )
        return best?.params ?: gridFor(ticker).first()
    }

    /**
     * Агрегация OOS-сделок всех фолдов: сводная кривая капитала строится
     * накоплением P&L от стартового капитала (без скачков на границах фолдов),
     * метрики считаются по объединённым OOS-сделкам. Комиссии суммируются по
     * всем фолдам и прокидываются в [BacktestMetrics.compute] (costDrag
     * считается от суммарной комиссии).
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
        // avgHoldBars агрегируется по объединённым OOS-сделкам: средневзвешенное
        // по числу сделок каждого фолда (точное среднее по конкатенации сделок).
        val avgHoldBars =
            if (tradeReturns.isNotEmpty()) {
                folds.sumOf { it.outOfSample.avgHoldBars * it.outOfSample.totalTrades } / tradeReturns.size
            } else {
                0.0
            }
        val totalCommission = folds.sumOf { it.outOfSample.totalCommissionPaid }
        val computed =
            BacktestMetrics.compute(
                ticker,
                equity,
                tradeReturns = tradeReturns,
                totalCommission = totalCommission,
                isFutures = instrumentsConfig.isFutures(ticker),
            )
        return computed.copy(avgHoldBars = avgHoldBars)
    }

    private fun emptyResult(ticker: String): BacktestResult =
        BacktestMetrics.compute(ticker, emptyList(), tradeReturns = emptyList(), isFutures = instrumentsConfig.isFutures(ticker))
}
