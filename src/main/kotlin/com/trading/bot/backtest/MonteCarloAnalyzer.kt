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
) {
    /**
     * Критерий устойчивости по Monte Carlo: даже нижний 5-й процентиль путей
     * остаётся прибыльным, и убыточных путей меньше четверти.
     */
    fun isRobust(): Boolean = p5Return > 0.0 && probabilityOfLoss < 0.25
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
 * [simulate] — bootstrap с возвращением по ПЕРИОДНЫМ ДОХОДНОСТЯМ кривой капитала
 * (мультипликативный компаундинг): каждый путь накапливает `periodReturns.size`
 * случайно выбранных (с повторениями) доходностей от `initialCapital`. Это
 * сохраняет распределение доходностей реального пути и эффект сложного процента
 * (в отличие от аддитивного ресемплинга рублёвых P&L сделок).
 */
object MonteCarlo {
    fun simulate(
        periodReturns: List<Double>,
        initialCapital: BigDecimal,
        simulations: Int,
        seed: Long = 42,
    ): MonteCarloResult {
        if (periodReturns.isEmpty() || simulations <= 0 || initialCapital <= BigDecimal.ZERO) {
            return MonteCarloResult(
                simulations = simulations,
                medianReturn = 0.0,
                p5Return = 0.0,
                p95Return = 0.0,
                avgReturn = 0.0,
                minReturn = 0.0,
                maxReturn = 0.0,
                probabilityOfLoss = 0.0,
            )
        }

        val rnd = Random(seed)
        val pathReturns = DoubleArray(simulations)
        val capital = initialCapital.toDouble()
        for (s in 0 until simulations) {
            var equity = capital
            val samples = IntArray(periodReturns.size) { rnd.nextInt(periodReturns.size) }
            for (i in samples) {
                val factor = 1.0 + periodReturns[i]
                equity = if (factor > 0.0) equity * factor else 0.0
            }
            pathReturns[s] = (equity - capital) / capital
        }
        pathReturns.sort()

        fun percentile(q: Double): Double {
            val idx = (q * simulations).toInt().coerceIn(0, simulations - 1)
            return pathReturns[idx]
        }

        return MonteCarloResult(
            simulations = simulations,
            medianReturn = percentile(0.5),
            p5Return = percentile(0.05),
            p95Return = percentile(0.95),
            avgReturn = pathReturns.average(),
            minReturn = pathReturns.first(),
            maxReturn = pathReturns.last(),
            probabilityOfLoss = pathReturns.count { it < 0.0 }.toDouble() / simulations,
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
     */
    suspend fun analyze(
        ticker: String,
        candles: List<Candle>,
        initialCapital: BigDecimal = backtestConfig.initialCapital,
        minBarsForSignal: Int = backtestConfig.minBarsForSignal,
        slPercent: Double = backtestConfig.slPercent / 100.0,
        tpPercent: Double = backtestConfig.tpPercent / 100.0,
        simulations: Int = backtestConfig.monteCarloSimulations,
        seed: Long = backtestConfig.monteCarloSeed,
    ): BacktestRobustnessReport {
        val baseResult =
            backtestEngine.simulate(ticker, candles, initialCapital, minBarsForSignal, slPercent, tpPercent)
        val base = StressScenarioResult.of("base", "Базовый прогон (комиссия 0.05%, проскальзывание 0.1%)", 1.0, 1.0, baseResult)

        val monteCarlo =
            MonteCarlo.simulate(
                BacktestMetrics.periodReturnsFromEquity(baseResult.equityCurve),
                initialCapital,
                simulations,
                seed,
            )
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
                        slPercent,
                        tpPercent,
                        commissionMultiplier = s.commissionMultiplier,
                        slippageMultiplier = s.slippageMultiplier,
                    ),
                )
            }

        val report = BacktestRobustnessReport(base, monteCarlo, stress)
        logger.info {
            "Robustness $ticker: base=${formatPct(base.totalReturn)} mc_p5=${formatPct(monteCarlo.p5Return)} " +
                "mc_pLoss=${String.format("%.1f%%", monteCarlo.probabilityOfLoss * 100)} " +
                "stressFailed=${stress.count { !it.passable }} " +
                "-> ${if (report.isRobust()) "ROBUST" else "FRAGILE"}"
        }
        return report
    }

    private fun formatPct(v: Double): String = String.format("%.2f%%", v * 100)
}
