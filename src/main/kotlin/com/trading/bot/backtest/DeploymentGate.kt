package com.trading.bot.backtest

/**
 * Статус допуска стратегии к продвижению. Единый источник истины перед деньгами
 * (P1-аудит: отдельный `BacktestResult.isPassable()` не должен самостоятельно
 * разрешать LIVE).
 *
 * Иерархия строгая: каждый нижестоящий статус достижим только при прохождении
 * всех предыдущих слоёв.
 */
enum class DeploymentStatus {
    /** Стратегия не прошла даже базовый backtest — к рассмотрению не допущена. */
    REJECTED,

    /** Прошёл только базовый backtest — исследовательская находка, не более. */
    RESEARCH_ONLY,

    /** Backtest + WFA пройдены, но holdout/Monte-Carlo/выборка ещё слабы — можно
     *  только paper-trading, НЕ live. */
    PAPER_ALLOWED,

    /** Пройдены все слои (backtest, walk-forward, независимый holdout, Monte Carlo,
     *  пользователь/operator подтвердил research->production перенос) — можно live. */
    LIVE_ALLOWED,
}

/**
 * Компактный результат одной проверки в [DeploymentGate].
 *
 * @property key машинный идентификатор проверки (например "backtest", "walk_forward")
 * @property label человекочитаемое имя
 * @property passed прошла ли проверка
 * @property detail дополнительная информация о пороге/значении
 */
data class DeploymentCheck(
    val key: String,
    val label: String,
    val passed: Boolean,
    val detail: String,
)

/**
 * Единый вход для принятия решения о деплое (roadmap аудит P1).
 *
 * Консолидирует ЧЕТЫРЕ независимых источника:
 *  - [DeploymentCriteria.backtest] — базовый прогон;
 *  - [DeploymentCriteria.validation] — walk-forward OOS;
 *  - [DeploymentCriteria.holdout] — финальный независимый holdout;
 *  - [DeploymentCriteria.robustness] — Monte Carlo + стресс-сценарии.
 *
 * Управляющие флаги:
 *  - [researchMode] — при `true` исследовательские прогоны и параметры
 *    (например confidence=0.63, подобранные с просмотром OOS) НИКОГДА не дают
 *    [DeploymentStatus.LIVE_ALLOWED]: максимум [DeploymentStatus.PAPER_ALLOWED].
 *    Параметры research физически не попадают в live, пока явно не перенесены
 *    в production-конфиг.
 *  - [confirmedForProduction] — операторское подтверждение, что используемые
 *    параметры — производственные (а не исследовательские).
 */
data class DeploymentDecision(
    val status: DeploymentStatus,
    val checks: List<DeploymentCheck>,
    val researchMode: Boolean,
    val confirmedForProduction: Boolean,
) {
    /** Все проверки прошли (независимо от research/operator-флагов). */
    val allChecksPassed: Boolean
        get() = checks.all { it.passed }
}

/**
 * Исходные данные для [DeploymentGate.decide].
 *
 * @property backtest результат базового прогона
 * @property validation результат walk-forward валидации
 * @property holdout результат финального независимого holdout (может быть null,
 *   если holdout ещё не выполнен)
 * @property robustness отчёт Monte Carlo + стресс (может быть null)
 * @property requiredHoldoutTrades минимальное число OOS-сделок holdout-окна для
 *   уверенного вывода (защита от «красивого результата на 2-3 сделках»)
 * @property researchMode по умолчанию `true`: исследовательские прогоны/параметры
 *   НИКОГДА не дают [DeploymentStatus.LIVE_ALLOWED] — максимум [DeploymentStatus.PAPER_ALLOWED].
 *   LIVE достижим только при ЯВНОМ переносе в production-режим.
 * @property confirmedForProduction по умолчанию `false`: операторское подтверждение
 *   production-параметров требуется ЯВНО — без него LIVE недостижим
 */
data class DeploymentCriteria(
    val backtest: BacktestResult,
    val validation: ValidationResult,
    val holdout: HoldoutValidation?,
    val robustness: BacktestRobustnessReport?,
    val requiredHoldoutTrades: Int = DEFAULT_REQUIRED_HOLDOUT_TRADES,
    val researchMode: Boolean = true,
    val confirmedForProduction: Boolean = false,
) {
    companion object {
        const val DEFAULT_REQUIRED_HOLDOUT_TRADES = 30
    }
}

/**
 * Чистый гейт принятия решения о деплое (без Spring — unit-тестируемый).
 *
 * Энфорсит строгий pipeline:
 * ```
 * RESEARCH -> BACKTEST -> WALK-FORWARD -> ROBUSTNESS -> HOLDOUT -> PAPER -> LIVE
 * ```
 * LIVE_ALLOWED достигается ТОЛЬКО когда одновременно:
 *   - backtest.isPassable();
 *   - validation.isPassable() (WFA robust + OOS passable);
 *   - holdout.passed (независимый OOS) И выборка >= requiredHoldoutTrades;
 *   - robustness.isRobust() (Monte Carlo + stress);
 *   - edge статистически значим;
 *   - параметры подтверждены как production (не research), researchMode=false.
 */
object DeploymentGate {
    const val MIN_WALK_FORWARD_TRADES = 100
    const val MIN_CONSISTENCY = 0.6

    fun decide(criteria: DeploymentCriteria): DeploymentDecision {
        val checks = buildChecks(criteria)

        val status =
            when {
                !checks.backtestPassed -> {
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

    private fun buildChecks(criteria: DeploymentCriteria): CheckBundle {
        val list = mutableListOf<DeploymentCheck>()
        val backtestPassed =
            criteria.backtest.isPassable().also { ok ->
                list +=
                    DeploymentCheck(
                        key = "backtest",
                        label = "Базовый backtest",
                        passed = ok,
                        detail =
                            "Sharpe=${fmt(criteria.backtest.sharpeRatio)} MDD=${fmt(criteria.backtest.maxDrawdown)} " +
                                "PF=${fmt(criteria.backtest.profitFactor)} trades=${criteria.backtest.totalTrades}",
                    )
            }

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

        // Edge берётся из dev-WFA OOS-агрегата (данные ДО holdout-границы), НЕ из
        // базового backtest на всей истории — иначе holdout протекал бы в edge.
        val oos = criteria.validation.aggregateOutOfSample
        val edgeOk = oos.edgeStatisticallySignificant
        list +=
            DeploymentCheck(
                key = "edge_significance",
                label = "Стат. значимость edge (dev-WFA OOS)",
                passed = edgeOk,
                detail =
                    "probabilityOfNoEdge=${fmt(oos.probabilityOfNoEdge)} " +
                        "significant=${oos.edgeStatisticallySignificant}",
            )

        val holdout = criteria.holdout
        val holdoutPassed =
            holdout?.passed ==
                true.also { ok ->
                    list +=
                        DeploymentCheck(
                            key = "holdout",
                            label = "Финальный holdout",
                            passed = ok,
                            detail =
                                holdout?.let {
                                    "holdoutReturn=${fmt(it.holdout.totalReturn)} " +
                                        "trades=${it.holdout.totalTrades} passable=${it.holdout.isPassable()}"
                                } ?: "not run",
                        )
                }
        val holdoutSampleOk =
            (holdout?.holdout?.totalTrades ?: 0) >= criteria.requiredHoldoutTrades
        list +=
            DeploymentCheck(
                key = "holdout_sample",
                label = "Достаточность holdout-выборки",
                passed = holdoutSampleOk,
                detail = "need >= ${criteria.requiredHoldoutTrades}, got ${holdout?.holdout?.totalTrades ?: 0}",
            )

        val robustness = criteria.robustness
        val robustnessPassed =
            robustness?.isRobust() ==
                true.also { ok ->
                    list +=
                        DeploymentCheck(
                            key = "robustness",
                            label = "Monte Carlo + stress",
                            passed = ok,
                            detail =
                                robustness?.let {
                                    "mcRobust=${it.monteCarlo.isRobust()} p5=${fmt(it.monteCarlo.p5Return)} " +
                                        "pLoss=${fmt(it.monteCarlo.probabilityOfLoss)} stressFailed=${it.stress.count { s -> !s.passable }}"
                                } ?: "not run",
                        )
                }

        return CheckBundle(
            list = list,
            backtestPassed = backtestPassed,
            walkForwardPassed = wfPassed && wfSampleOk && consistencyOk,
            holdoutPassed = holdoutPassed && holdoutSampleOk,
            robustnessPassed = robustnessPassed,
            edgeSignificant = edgeOk,
        )
    }

    private data class CheckBundle(
        val list: List<DeploymentCheck>,
        val backtestPassed: Boolean,
        val walkForwardPassed: Boolean,
        val holdoutPassed: Boolean,
        val robustnessPassed: Boolean,
        val edgeSignificant: Boolean,
    )

    private fun fmt(v: Double): String = String.format("%.3f", v)
}
