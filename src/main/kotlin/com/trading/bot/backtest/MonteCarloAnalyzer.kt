package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import com.trading.bot.model.entity.Candle
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import kotlin.random.Random

/**
 * Результат Monte Carlo анализа кривой капитала бэктеста (roadmap 13.7.8).
 *
 * Bootstrap-ресемплинг ПЕРИОДНЫХ ДОХОДНОСТЕЙ кривой капитала с возвращением:
 * каждый прогон случайно переставляет/повторяет доходности фактического пути и
 * накапливает их мультипликативно от стартового капитала. Так проверяется,
 * достигнута ли доходность удачным порядком движения капитала (или несколькими
 * выбросами), а не устойчивым преимуществом стратегии.
 */
data class MonteCarloResult(
    val simulations: Int,
    /** Медианная доходность пути (0.5 квантиль), в долях капитала. */
    val medianReturn: Double,
    /** Доходность 5-го процентиля (95% доверительная нижняя граница / VaR). */
    val p5Return: Double,
    /** Доходность 95-го процентиля. */
    val p95Return: Double,
    /** Средняя доходность пути. */
    val avgReturn: Double,
    /** Худшая доходность среди всех симуляций. */
    val minReturn: Double,
    /** Лучшая доходность среди всех симуляций. */
    val maxReturn: Double,
    /** Доля симуляций с отрицательной итоговой доходностью (0..1). */
    val probabilityOfLoss: Double,
    /** P(MDD >= 20%) по путям (доля путей, где макс. просадка >= 20%). */
    val probabilityMddExceeds20: Double = 0.0,
    /** P(MDD >= 30%). */
    val probabilityMddExceeds30: Double = 0.0,
    /** P(MDD >= 40%). */
    val probabilityMddExceeds40: Double = 0.0,
    /** P(конечный капитал <= 50% стартового). */
    val probabilityCapitalLossExceeds50: Double = 0.0,
    /** P(конечный капитал <= 20% стартового). */
    val probabilityCapitalLossExceeds20: Double = 0.0,
    /** Медианный конечный капитал худших 1% путей (доля от стартового). */
    val worst1PercentEquity: Double = 1.0,
    /** Медианный конечный капитал худших 5% путей (доля от стартового). */
    val worst5PercentEquity: Double = 1.0,
    /** P(разорение): путь пробил floor капитала (по умолчанию <= 10% стартового). */
    val probabilityOfRuin: Double = 0.0,
    /** Метод ресемплинга: "iid" | "stationary" | "block". */
    val blockMethod: String = "iid",
    /** Средняя длина блока (stationary) или фиксированная (block). */
    val avgBlockLength: Double = 1.0,
) {
    /**
     * Критерий устойчивости по Monte Carlo для МИНИМАЛЬНОГО РИСКА (аудит P1):
     * даже нижний 5-й процентиль прибыльный, убыточных путей < 25%, риск MDD>=40%
     * и риск разорения под контролем. Порог риск-разорения завышен относительно
     * классического (обычно < 1%), т.к. для «минимальный доход при минимальном
     * риске» мы не допускаем руин-вероятность более 5%.
     */
    fun isRobust(): Boolean =
        p5Return > 0.0 &&
            probabilityOfLoss < 0.25 &&
            probabilityMddExceeds40 < 0.30 &&
            probabilityOfRuin < 0.05
}

/** Результат одного стресс-сценария исполнения (roadmap 13.7.8). */
data class StressScenarioResult(
    val name: String,
    val description: String,
    val commissionMultiplier: Double,
    val slippageMultiplier: Double,
    val totalReturn: Double,
    val sharpeRatio: Double,
    val sortinoRatio: Double,
    val maxDrawdown: Double,
    val profitFactor: Double,
    val winRate: Double,
    val totalTrades: Int,
    val passable: Boolean,
) {
    companion object {
        fun of(
            name: String,
            description: String,
            commissionMultiplier: Double,
            slippageMultiplier: Double,
            result: BacktestResult,
        ): StressScenarioResult =
            StressScenarioResult(
                name = name,
                description = description,
                commissionMultiplier = commissionMultiplier,
                slippageMultiplier = slippageMultiplier,
                totalReturn = result.totalReturn,
                sharpeRatio = result.sharpeRatio,
                sortinoRatio = result.sortinoRatio,
                maxDrawdown = result.maxDrawdown,
                profitFactor = result.profitFactor,
                winRate = result.winRate,
                totalTrades = result.totalTrades,
                passable = result.isPassable(),
            )
    }
}

/** Сводный отчёт об устойчивости стратегии: Monte Carlo + стресс-сценарии. */
data class BacktestRobustnessReport(
    val base: StressScenarioResult,
    val monteCarlo: MonteCarloResult,
    val stress: List<StressScenarioResult>,
) {
    /** Стратегия устойчива, если базовый прогон проходит критерии, Monte Carlo
     *  не обнаруживает хрупкости, и ни один стресс-сценарий не роняет стратегию. */
    fun isRobust(): Boolean =
        base.passable &&
            monteCarlo.isRobust() &&
            stress.all { it.passable }
}

/**
 * Чистая математика Monte Carlo (без Spring — unit-тестируется напрямую).
 *
 * Поддерживаются три метода ресемплинга ПЕРИОДНЫХ ДОХОДНОСТЕЙ кривой капитала
 * (мультипликативный компаундинг от стартового капитала):
 *
 *  1. [simulate] — классический bootstrap с возвращением (IID): каждая позиция
 *     периода независима от соседних. Сохраняет распределение, но игнорирует
 *     serial correlation и volatility clustering.
 *  2. [simulateStationary] — stationary bootstrap (Politis-Romano): длина блока
 *     L распределена геометрически ~ Geom(p), p = 1/avgBlockLength. Сохраняет
 *     автокорреляцию, кластеры волатильности и серии плохих периодов.
 *  3. [simulateBlock] — block bootstrap с фиксированной длиной окна: жёстче
 *     сохраняет последовательности, но чувствителен к выбору длины блока.
 *
 * Для всех методов дополнительно рассчитываются веротяности хвостовых событий
 * риска: P(MDD >= X), P(капитал <= 50%/20%), риск разорения и худшие хвосты —
 * критично для цели «минимальный доход при минимальном риске».
 */
object MonteCarlo {
    /** Капитал ниже этой доли от стартового считается разорением (по умолчанию 10%). */
    const val RUIN_FLOOR_FRACTION = 0.10

    fun simulate(
        periodReturns: List<Double>,
        initialCapital: BigDecimal,
        simulations: Int,
        seed: Long = 42,
    ): MonteCarloResult {
        if (emptyGuard(periodReturns, simulations, initialCapital)) {
            return emptyResult(simulations, "iid", 1.0)
        }
        val rnd = Random(seed)
        val capital = initialCapital.toDouble()
        val n = periodReturns.size
        val pathData =
            Array(simulations) {
                val returnsOfPath = ArrayList<Double>(n)
                repeat(n) { returnsOfPath.add(periodReturns[rnd.nextInt(n)]) }
                buildPathFrom(capital, returnsOfPath)
            }
        return aggregate(pathData, capital, simulations, "iid", 1.0)
    }

    /**
     * Stationary bootstrap (Politis-Romano): длина блока геометрическая со средним
     * [avgBlockLength]. Лучше IID воспроизводит serial correlation / кластеризацию.
     */
    fun simulateStationary(
        periodReturns: List<Double>,
        initialCapital: BigDecimal,
        simulations: Int,
        avgBlockLength: Double = 5.0,
        seed: Long = 42,
    ): MonteCarloResult {
        if (emptyGuard(periodReturns, simulations, initialCapital)) {
            return emptyResult(simulations, "stationary", avgBlockLength)
        }
        val rnd = Random(seed)
        val capital = initialCapital.toDouble()
        val n = periodReturns.size
        val p = 1.0 / avgBlockLength
        val pathData =
            Array(simulations) {
                var idx = rnd.nextInt(n)
                var blockRemaining = 0
                val returnsOfPath = ArrayList<Double>(n)
                repeat(n) {
                    if (blockRemaining == 0) {
                        idx = rnd.nextInt(n)
                        blockRemaining = geometric(rnd, p)
                    }
                    returnsOfPath.add(periodReturns[idx])
                    idx = (idx + 1) % n
                    blockRemaining--
                }
                buildPathFrom(capital, returnsOfPath)
            }
        return aggregate(pathData, capital, simulations, "stationary", avgBlockLength)
    }

    /** Block bootstrap с фиксированной длиной блока [blockLength]. */
    fun simulateBlock(
        periodReturns: List<Double>,
        initialCapital: BigDecimal,
        simulations: Int,
        blockLength: Int = 5,
        seed: Long = 42,
    ): MonteCarloResult {
        require(blockLength >= 1) { "blockLength must be >= 1" }
        if (emptyGuard(periodReturns, simulations, initialCapital)) {
            return emptyResult(simulations, "block", blockLength.toDouble())
        }
        val rnd = Random(seed)
        val capital = initialCapital.toDouble()
        val n = periodReturns.size
        val pathData =
            Array(simulations) {
                val returnsOfPath = ArrayList<Double>(n)
                while (returnsOfPath.size < n) {
                    val start = rnd.nextInt(n)
                    for (j in 0 until blockLength) {
                        if (returnsOfPath.size >= n) break
                        returnsOfPath.add(periodReturns[(start + j) % n])
                    }
                }
                buildPathFrom(capital, returnsOfPath)
            }
        return aggregate(pathData, capital, simulations, "block", blockLength.toDouble())
    }

    /** exp-распределение длины блока: минимальное значение 1 (Politis-Romano). */
    private fun geometric(
        rnd: Random,
        p: Double,
    ): Int {
        val u = rnd.nextDouble().coerceIn(1e-12, 1.0)
        return kotlin.math
            .ceil(kotlin.math.ln(1.0 - u) / kotlin.math.ln(1.0 - p))
            .toInt()
            .coerceAtLeast(1)
    }

    private fun emptyGuard(
        periodReturns: List<Double>,
        simulations: Int,
        initialCapital: BigDecimal,
    ): Boolean = periodReturns.isEmpty() || simulations <= 0 || initialCapital <= BigDecimal.ZERO

    private fun emptyResult(
        simulations: Int,
        method: String,
        blockLen: Double,
    ): MonteCarloResult =
        MonteCarloResult(
            simulations = simulations,
            medianReturn = 0.0,
            p5Return = 0.0,
            p95Return = 0.0,
            avgReturn = 0.0,
            minReturn = 0.0,
            maxReturn = 0.0,
            probabilityOfLoss = 0.0,
            probabilityOfRuin = 0.0,
            blockMethod = method,
            avgBlockLength = blockLen,
        )

    /** Данные одного пути: финальная доходность, макс. просадка и МИНИМАЛЬНЫЙ капитал за путь. */
    private data class PathData(
        val finalReturn: Double,
        val maxDrawdown: Double,
        val finalCapitalFraction: Double,
        val minCapitalFraction: Double,
    )

    private fun buildPathFrom(
        capital: Double,
        returns: List<Double>,
    ): PathData {
        var equity = capital
        var peak = capital
        var maxDd = 0.0
        var minEquity = capital
        for (r in returns) {
            val factor = 1.0 + r
            equity = if (factor > 0.0) equity * factor else 0.0
            if (equity < minEquity) minEquity = equity
            if (equity > peak) peak = equity
            val dd = if (peak > 0.0) 1.0 - equity / peak else 1.0
            if (dd > maxDd) maxDd = dd
        }
        val finalReturn = if (capital > 0.0) (equity - capital) / capital else 0.0
        val finalCapitalFraction = if (capital > 0.0) equity / capital else 0.0
        val minCapitalFraction = if (capital > 0.0) minEquity / capital else 0.0
        return PathData(finalReturn, maxDd, finalCapitalFraction, minCapitalFraction)
    }

    private fun aggregate(
        pathData: Array<PathData>,
        capital: Double,
        simulations: Int,
        method: String,
        blockLen: Double,
    ): MonteCarloResult {
        val pathReturns = pathData.map { it.finalReturn }.toDoubleArray()
        pathReturns.sort()

        fun percentile(q: Double): Double {
            val idx = (q * simulations).toInt().coerceIn(0, simulations - 1)
            return pathReturns[idx]
        }

        val mdd20 = pathData.count { it.maxDrawdown >= 0.20 }.toDouble() / simulations
        val mdd30 = pathData.count { it.maxDrawdown >= 0.30 }.toDouble() / simulations
        val mdd40 = pathData.count { it.maxDrawdown >= 0.40 }.toDouble() / simulations
        val loss50 = pathData.count { it.finalCapitalFraction <= 0.50 }.toDouble() / simulations
        val loss20 = pathData.count { it.finalCapitalFraction <= 0.20 }.toDouble() / simulations
        // Разорение = капитал достиг RUIN_FLOOR_FRACTION В ЛЮБОЙ ТОЧКЕ пути (не только в конце):
        // 100% -> 8% -> 90% считается разорением, т.к. в процессе капитал падал ниже 10%.
        val ruin = pathData.count { it.minCapitalFraction <= RUIN_FLOOR_FRACTION }.toDouble() / simulations

        // Худшие хвосты по конечному капиталу (доля от стартового).
        val sortedFractions = pathData.map { it.finalCapitalFraction }.sorted()

        fun tailMedian(tailSize: Int): Double {
            if (tailSize <= 0) return 1.0
            val head = sortedFractions.take(tailSize)
            return if (head.isEmpty()) 1.0 else head[head.size / 2]
        }
        val worst1 = tailMedian((simulations * 0.01).toInt().coerceAtLeast(1))
        val worst5 = tailMedian((simulations * 0.05).toInt().coerceAtLeast(1))

        return MonteCarloResult(
            simulations = simulations,
            medianReturn = percentile(0.5),
            p5Return = percentile(0.05),
            p95Return = percentile(0.95),
            avgReturn = pathReturns.average(),
            minReturn = pathReturns.first(),
            maxReturn = pathReturns.last(),
            probabilityOfLoss = pathReturns.count { it < 0.0 }.toDouble() / simulations,
            probabilityMddExceeds20 = mdd20,
            probabilityMddExceeds30 = mdd30,
            probabilityMddExceeds40 = mdd40,
            probabilityCapitalLossExceeds50 = loss50,
            probabilityCapitalLossExceeds20 = loss20,
            worst1PercentEquity = worst1,
            worst5PercentEquity = worst5,
            probabilityOfRuin = ruin,
            blockMethod = method,
            avgBlockLength = blockLen,
        )
    }
}

/**
 * Анализ устойчивости бэктеста (roadmap 13.7.8, review MR-004/H-003).
 *
 * Дополняет walk-forward валидацию ([BacktestValidator]) двумя проверками:
 *
 * 1. **Monte Carlo** — bootstrap по ПЕРИОДНЫМ ДОХОДНОСТЯМ кривой капитала
 *    (мультипликативный компаундинг): если большинство случайных путей убыточны,
 *    доходность не является преимуществом стратегии;
 * 2. **Стресс-сценарии исполнения** — перепрогон движка с увеличенными комиссией
 *    и проскальзыванием (×2/×5 и комбинированный ×3+×3): устойчивость к росту
 *    издержек, типичному для стрессовых условий рынка.
 */
@Component
class MonteCarloAnalyzer(
    private val backtestEngine: BacktestEngine,
    private val backtestConfig: BacktestConfig = BacktestConfig(),
) {
    private val logger = KotlinLogging.logger {}

    private data class Scenario(
        val name: String,
        val description: String,
        val commissionMultiplier: Double,
        val slippageMultiplier: Double,
    )

    /** Сетка стресс-сценариев: рост комиссии и проскальзывания относительно базовых ставок. */
    private val scenarios =
        listOf(
            Scenario("commission_x2", "Комиссия ×2", 2.0, 1.0),
            Scenario("commission_x5", "Комиссия ×5", 5.0, 1.0),
            Scenario("slippage_x2", "Проскальзывание ×2", 1.0, 2.0),
            Scenario("slippage_x5", "Проскальзывание ×5", 1.0, 5.0),
            Scenario("combined_stress", "Комиссия ×3 + проскальзывание ×3", 3.0, 3.0),
        )

    /**
     * Полный отчёт по тикеру: базовый прогон, Monte Carlo по его сделкам и
     * стресс-перепрогоны с ужесточённым исполнением.
     *
     * При передаче [parameters] (замороженный набор стратегии) базовый прогон,
     * Monte Carlo и стресс используют ИМЕННО эти параметры (SL/TP/леверидж/риск/
     * лимит контрактов) — robustness верифицирует ту же стратегию, что пойдёт на
     * holdout/live, а не config-дефолты. [signalGeneratorOverride] (confidence/
     * regime) должен быть тем же, что применялся в holdout.
     *
     * Без [parameters] сохраняется поведение по config-дефолтам (для standalone
     * robustness-эндпоинта).
     */
    suspend fun analyze(
        ticker: String,
        candles: List<Candle>,
        parameters: StrategyParameters? = null,
        initialCapital: BigDecimal = backtestConfig.initialCapital,
        minBarsForSignal: Int = backtestConfig.minBarsForSignal,
        slPercent: Double = backtestConfig.slPercent / 100.0,
        tpPercent: Double = backtestConfig.tpPercent / 100.0,
        simulations: Int = backtestConfig.monteCarloSimulations,
        seed: Long = backtestConfig.monteCarloSeed,
        method: String = backtestConfig.mcMethod,
        avgBlockLength: Double = backtestConfig.mcAvgBlockLength,
        blockLength: Int = backtestConfig.mcBlockLength,
        signalGeneratorOverride: BacktestSignalGenerator? = null,
    ): BacktestRobustnessReport {
        // Замороженные параметры стратегии (если переданы) приоритетнее config-дефолтов —
        // robustness обязан проверять ту же стратегию, что уйдёт на holdout/live.
        val effSlPercent = parameters?.slPercent ?: slPercent
        val effTpPercent = parameters?.tpPercent ?: tpPercent
        val effSlPoints = parameters?.slPoints
        val effTpPoints = parameters?.tpPoints
        val effLeverage = parameters?.leverage ?: 1.0
        val effRisk = parameters?.riskPerTradePercent
        val effMaxContracts = parameters?.futuresMaxContractsPerPosition
        val effOverride =
            signalGeneratorOverride
                ?: parameters?.confidenceThreshold?.let {
                    LiveStrategyBacktestSignalGenerator(adaptiveConfidenceThreshold = it)
                }
        val baseResult =
            backtestEngine.simulate(
                ticker,
                candles,
                initialCapital,
                minBarsForSignal,
                effSlPercent,
                effTpPercent,
                commissionMultiplier = 1.0,
                slippageMultiplier = 1.0,
                slPoints = effSlPoints,
                tpPoints = effTpPoints,
                leverage = effLeverage,
                capitalSlice = null,
                riskPerTradePercent = effRisk,
                futuresMaxContractsPerPosition = effMaxContracts,
                signalGeneratorOverride = effOverride,
            )
        val base = StressScenarioResult.of("base", "Базовый прогон (комиссия 0.05%, проскальзывание 0.1%)", 1.0, 1.0, baseResult)

        val periodReturns = BacktestMetrics.periodReturnsFromEquity(baseResult.equityCurve)
        val monteCarlo =
            when (method.lowercase()) {
                "stationary" -> {
                    MonteCarlo.simulateStationary(periodReturns, initialCapital, simulations, avgBlockLength, seed)
                }

                "block" -> {
                    MonteCarlo.simulateBlock(periodReturns, initialCapital, simulations, blockLength, seed)
                }

                else -> {
                    MonteCarlo.simulate(periodReturns, initialCapital, simulations, seed)
                } // "iid" и любие иные
            }
        val stress =
            scenarios.map { s ->
                StressScenarioResult.of(
                    s.name,
                    s.description,
                    s.commissionMultiplier,
                    s.slippageMultiplier,
                    backtestEngine.simulate(
                        ticker,
                        candles,
                        initialCapital,
                        minBarsForSignal,
                        effSlPercent,
                        effTpPercent,
                        commissionMultiplier = s.commissionMultiplier,
                        slippageMultiplier = s.slippageMultiplier,
                        slPoints = effSlPoints,
                        tpPoints = effTpPoints,
                        leverage = effLeverage,
                        capitalSlice = null,
                        riskPerTradePercent = effRisk,
                        futuresMaxContractsPerPosition = effMaxContracts,
                        signalGeneratorOverride = effOverride,
                    ),
                )
            }

        val report = BacktestRobustnessReport(base, monteCarlo, stress)
        logger.info {
            "Robustness $ticker: base=${formatPct(base.totalReturn)} mc_p5=${formatPct(monteCarlo.p5Return)} " +
                "mc_pLoss=${String.format("%.1f%%", monteCarlo.probabilityOfLoss * 100)} " +
                "mc_mdd40=${String.format("%.1f%%", monteCarlo.probabilityMddExceeds40 * 100)} " +
                "mc_ruin=${String.format("%.1f%%", monteCarlo.probabilityOfRuin * 100)} " +
                "method=${monteCarlo.blockMethod} " +
                "stressFailed=${stress.count { !it.passable }} " +
                "-> ${if (report.isRobust()) "ROBUST" else "FRAGILE"}"
        }
        return report
    }

    private fun formatPct(v: Double): String = String.format("%.2f%%", v * 100)
}
