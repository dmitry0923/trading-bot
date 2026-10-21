package com.trading.bot.backtest.portfolio

import com.trading.bot.backtest.BacktestResult
import com.trading.bot.backtest.DeploymentCheck
import com.trading.bot.backtest.DeploymentDecision
import com.trading.bot.backtest.DeploymentStatus
import com.trading.bot.backtest.MonteCarloResult
import com.trading.bot.backtest.ValidationResult

/**
 * Критерии допуска портфеля cross-sectional momentum + carry (`docs/26` §11),
 * аналог тикерного [com.trading.bot.backtest.DeploymentCriteria], но с учётом
 * специфики класса:
 *
 *  - у портфеля НЕТ in-sample сетки параметров — WFA ([validation]) состоит из
 *    чистых OOS-окон, а [devBacktest] — прогон на dev-части (до holdout-границы);
 *  - Monte Carlo считается по OOS-сделкам dev-WFA (портфельный движок не
 *    использует SL/TP и не имеет стресс-перепрогонов по издержкам), поэтому
 *    передаётся результат [MonteCarloResult], а не [com.trading.bot.backtest.BacktestRobustnessReport];
 *  - [degenerateFolds] — прямое следствие урока §11.9: фолд, где после warmup
 *    остаётся меньше [minSignalRebalances] ребалансов, вырожден и не является
 *    тестом momentum; такой вердикт НЕ должен считаться прохождением.
 *
 * @property devBacktest базовый прогон на dev-части (до holdout-границы)
 * @property validation walk-forward на dev-части (чистые OOS-фолды)
 * @property holdout прогон на независимом holdout-окне (последние
 *   `holdoutFraction` истории), трогается РОВНО один раз; `null` — не выполнен
 * @property monteCarlo Monte Carlo по доходностям holdout/dev; `null` — не запускался
 * @property foldsCount фактическое число OOS-фолдов WFA
 * @property degenerateFolds фолды, вырожденные по вместимости сигнала
 *   (сделок/фолд ниже [minTradesPerFold] или ребалансов < [minSignalRebalances])
 * @property requiredHoldoutTrades минимальная выборка holdout
 * @property researchMode при `true` максимум [DeploymentStatus.PAPER_ALLOWED]
 * @property confirmedForProduction операторское подтверждение production-параметров
 */
data class PortfolioDeploymentCriteria(
    val devBacktest: BacktestResult,
    val validation: ValidationResult,
    val holdout: BacktestResult?,
    val monteCarlo: MonteCarloResult?,
    val foldsCount: Int,
    val degenerateFolds: List<PortfolioDeploymentGate.FoldCapacity> = emptyList(),
    val requiredHoldoutTrades: Int = PortfolioDeploymentGate.DEFAULT_REQUIRED_HOLDOUT_TRADES,
    val researchMode: Boolean = true,
    val confirmedForProduction: Boolean = false,
    /**
     * Причина, по которой WFA не построила ни одного фолда (нехватка баров dev-части
     * под `folds × (skip + lookback + rebalance + 2)`) — из `PortfolioWalkForward.verdict`.
     * Пустая строка, когда фолды построены.
     */
    val capacityReason: String = "",
)

/**
 * Чистый портфельный гейт допуска (без Spring — unit-тестируемый).
 *
 * Энфорсит тот же pipeline, что тикерный [com.trading.bot.backtest.DeploymentGate]
 * (`RESEARCH → BACKTEST → WALK-FORWARD → ROBUSTNESS → HOLDOUT → PAPER → LIVE`),
 * но с портфельными порогами и обязательной проверкой **структурной вместимости
 * фолдов** — чтобы «красивый результат на вырожденном фолде» (2 ребаланса,
 * ~4 сделки, как в §11.9) не выглядел прохождением.
 *
 * LIVE_ALLOWED достигается ТОЛЬКО когда одновременно:
 *   - dev-backtest passable (PF/Sharpe/MDD/сделки — пороги [BacktestResult.isPassable]);
 *   - WFA passable (robust + OOS passable), >= [MIN_WALK_FORWARD_TRADES] OOS-сделок,
 *     consistency >= [MIN_CONSISTENCY], НИ ОДНОГО вырожденного фолда;
 *   - holdout passable И выборка >= [DEFAULT_REQUIRED_HOLDOUT_TRADES];
 *   - Monte Carlo robust (p5 > 0, pLoss < 25%, P(MDD>=40%) < 30%, ruin < 5%);
 *   - edge статистически значим на dev-WFA OOS (P(noEdge) <= 0.05, CI95 не содержит ноль);
 *   - researchMode=false И confirmedForProduction=true.
 */
object PortfolioDeploymentGate {
    const val MIN_WALK_FORWARD_TRADES = 100
    const val MIN_CONSISTENCY = 0.6
    const val DEFAULT_REQUIRED_HOLDOUT_TRADES = 30

    /** Фолд обязан содержать хотя бы столько ребалансов, чтобы быть тестом сигнала. */
    const val MIN_SIGNAL_REBALANCES = 3

    /** Минимальное число сделок на фолд (иначе фолд — не тест momentum). */
    const val MIN_TRADES_PER_FOLD = 4

    /**
     * Структурная вместимость одного фолда: сколько сигнальной динамики остаётся
     * после прогрева `skipBars + lookbackBars` (урок §11.9).
     *
     * @property foldIndex индекс фолда
     * @property segmentBars число баров в сегменте фолда
     * @property signalBars баров после warmup
     * @property rebalances ребалансов внутри сигнального окна
     * @property trades сделок в фолде
     */
    data class FoldCapacity(
        val foldIndex: Int,
        val segmentBars: Int,
        val signalBars: Int,
        val rebalances: Int,
        val trades: Int,
    ) {
        /** Фолд вырожден: слишком мало ребалансов ИЛИ слишком мало сделок. */
        val degenerate: Boolean
            get() = rebalances < MIN_SIGNAL_REBALANCES || trades < MIN_TRADES_PER_FOLD
    }

    fun decide(criteria: PortfolioDeploymentCriteria): DeploymentDecision {
        val checks = buildChecks(criteria)
        val status =
            when {
                !checks.devBacktestPassed -> {
                    DeploymentStatus.REJECTED
                }

                !checks.walkForwardPassed -> {
                    DeploymentStatus.RESEARCH_ONLY
                }

                !checks.holdoutPassed || !checks.robustnessPassed || !checks.edgeSignificant -> {
                    DeploymentStatus.PAPER_ALLOWED
                }

                criteria.researchMode || !criteria.confirmedForProduction -> {
                    DeploymentStatus.PAPER_ALLOWED
                }

                else -> {
                    DeploymentStatus.LIVE_ALLOWED
                }
            }
        return DeploymentDecision(
            status = status,
            checks = checks.list,
            researchMode = criteria.researchMode,
            confirmedForProduction = criteria.confirmedForProduction,
        )
    }

    private fun buildChecks(criteria: PortfolioDeploymentCriteria): CheckBundle {
        val list = mutableListOf<DeploymentCheck>()

        val devPassed =
            criteria.devBacktest.isPassable().also { ok ->
                list +=
                    DeploymentCheck(
                        key = "dev_backtest",
                        label = "Dev-backtest (до holdout-границы)",
                        passed = ok,
                        detail =
                            "Sharpe=${fmt(criteria.devBacktest.sharpeRatio)} " +
                                "MDD=${fmt(criteria.devBacktest.maxDrawdown)} " +
                                "PF=${fmt(criteria.devBacktest.profitFactor)} " +
                                "trades=${criteria.devBacktest.totalTrades}",
                    )
            }

        val mdd = criteria.validation.aggregateOutOfSample.maxDrawdown
        val mddOk = mdd <= MAX_MDD
        list +=
            DeploymentCheck(
                key = "mdd",
                label = "Ограничение просадки OOS-агрегата",
                passed = mddOk,
                detail = "need <= ${fmt(MAX_MDD)}, got ${fmt(mdd)}",
            )

        val wfPassed =
            criteria.validation.isPassable().also { ok ->
                list +=
                    DeploymentCheck(
                        key = "walk_forward",
                        label = "Walk-forward OOS",
                        passed = ok,
                        detail =
                            "consistency=${fmt(criteria.validation.consistency)} " +
                                "oosTrades=${criteria.validation.aggregateOutOfSample.totalTrades} " +
                                "oosSharpe=${fmt(criteria.validation.aggregateOutOfSample.sharpeRatio)} " +
                                "oosPF=${fmt(criteria.validation.aggregateOutOfSample.profitFactor)}",
                    )
            }

        val wfSampleOk =
            criteria.validation.aggregateOutOfSample.totalTrades >= MIN_WALK_FORWARD_TRADES
        list +=
            DeploymentCheck(
                key = "walk_forward_sample",
                label = "Достаточность OOS-выборки WFA",
                passed = wfSampleOk,
                detail = "need >= $MIN_WALK_FORWARD_TRADES, got ${criteria.validation.aggregateOutOfSample.totalTrades}",
            )

        val consistencyOk = criteria.validation.consistency >= MIN_CONSISTENCY
        list +=
            DeploymentCheck(
                key = "consistency",
                label = "Консистентность фолдов",
                passed = consistencyOk,
                detail = "need >= ${fmt(MIN_CONSISTENCY)}, got ${fmt(criteria.validation.consistency)}",
            )

        // Урок §11.9: вырожденный фолд (сегмент − warmup ≈ 2 ребаланса) даёт
        // «красивый» PF на ~4 сделках и не является тестом momentum.
        val degenerate = criteria.degenerateFolds.filter { it.degenerate }
        val capacityOk = criteria.foldsCount > 0 && degenerate.isEmpty()
        list +=
            DeploymentCheck(
                key = "fold_capacity",
                label = "Структурная вместимость фолдов (ребалансы/сделки)",
                passed = capacityOk,
                detail =
                    if (criteria.foldsCount == 0) {
                        if (criteria.capacityReason.isBlank()) {
                            "no folds"
                        } else {
                            "no folds: ${criteria.capacityReason}"
                        }
                    } else {
                        "folds=${criteria.foldsCount} degenerate=${degenerate.size}" +
                            degenerate.joinToString("") {
                                " [f${it.foldIndex}: seg=${it.segmentBars} sig=${it.signalBars} reb=${it.rebalances} tr=${it.trades}]"
                            }
                    },
            )

        val oos = criteria.validation.aggregateOutOfSample
        val ciExcludesZero = oos.meanTradeCI95Low > 0.0 || oos.meanTradeCI95High < 0.0
        val edgeOk = oos.edgeStatisticallySignificant && ciExcludesZero
        list +=
            DeploymentCheck(
                key = "edge_significance",
                label = "Стат. значимость edge (dev-WFA OOS)",
                passed = edgeOk,
                detail =
                    "probabilityOfNoEdge=${fmt(oos.probabilityOfNoEdge)} " +
                        "CI95=[${fmt(oos.meanTradeCI95Low)}; ${fmt(oos.meanTradeCI95High)}] " +
                        "significant=${oos.edgeStatisticallySignificant}",
            )

        val holdout = criteria.holdout
        val holdoutPassed =
            (holdout?.isPassable() == true).also { ok ->
                list +=
                    DeploymentCheck(
                        key = "holdout",
                        label = "Финальный holdout (независимый хвост)",
                        passed = ok,
                        detail =
                            holdout?.let {
                                "holdoutReturn=${fmt(it.totalReturn)} " +
                                    "PF=${fmt(it.profitFactor)} MDD=${fmt(it.maxDrawdown)} " +
                                    "trades=${it.totalTrades} passable=${it.isPassable()}"
                            } ?: "not run",
                    )
            }
        val holdoutSampleOk = (holdout?.totalTrades ?: 0) >= criteria.requiredHoldoutTrades
        list +=
            DeploymentCheck(
                key = "holdout_sample",
                label = "Достаточность holdout-выборки",
                passed = holdoutSampleOk,
                detail = "need >= ${criteria.requiredHoldoutTrades}, got ${holdout?.totalTrades ?: 0}",
            )

        val mc = criteria.monteCarlo
        val robustnessPassed =
            (mc?.isRobust() == true).also { ok ->
                list +=
                    DeploymentCheck(
                        key = "robustness",
                        label = "Monte Carlo (OOS)",
                        passed = ok,
                        detail =
                            mc?.let {
                                "mcRobust=${it.isRobust()} p5=${fmt(it.p5Return)} " +
                                    "pLoss=${fmt(it.probabilityOfLoss)} mdd40=${fmt(it.probabilityMddExceeds40)} " +
                                    "ruin=${fmt(it.probabilityOfRuin)}"
                            } ?: "not run",
                    )
            }

        return CheckBundle(
            list = list,
            devBacktestPassed = devPassed && mddOk,
            walkForwardPassed = wfPassed && wfSampleOk && consistencyOk && capacityOk,
            holdoutPassed = holdoutPassed && holdoutSampleOk,
            robustnessPassed = robustnessPassed,
            edgeSignificant = edgeOk,
        )
    }

    private data class CheckBundle(
        val list: List<DeploymentCheck>,
        val devBacktestPassed: Boolean,
        val walkForwardPassed: Boolean,
        val holdoutPassed: Boolean,
        val robustnessPassed: Boolean,
        val edgeSignificant: Boolean,
    )

    /** Портфельный порог MDD (фьючерсный класс, PLAN-001 §4). */
    const val MAX_MDD = 0.40

    private fun fmt(v: Double): String = String.format("%.3f", v)
}
