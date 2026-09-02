package com.trading.bot.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Единый DeploymentGate: консолидация backtest / walk-forward / holdout /
 * Monte Carlo + стресс в один статус допуска. Проверяет, что ни одно слабое
 * звено не пропускается в LIVE, и что research-режим никогда не даёт LIVE.
 */
class DeploymentGateTest {
    private val capital = BigDecimal("100000")

    private fun strongBacktest(
        isFutures: Boolean = false,
        trades: Int = 250,
        significant: Boolean = true,
    ): BacktestResult {
        val returns =
            if (significant) {
                List(trades) { if (it % 2 == 0) 40.0 else -15.0 } // явный положительный edge, PF>1.3, значим
            } else {
                List(trades) { if (it % 10 == 0) 5.0 else -1.0 } // слабый edge, малозначим
            }
        val equity = returns.runningFold(capital) { acc, r -> acc.add(BigDecimal.valueOf(r)) }
        return BacktestMetrics.compute(
            ticker = "SBER",
            equityCurve = equity,
            tradeReturns = returns,
            isFutures = isFutures,
        )
    }

    private fun strongWalkForward(
        trades: Int = 250,
        consistency: Double = 1.0,
    ): ValidationResult {
        val returns = List(trades) { if (it % 2 == 0) 40.0 else -15.0 }
        val equity = returns.runningFold(capital) { acc, r -> acc.add(BigDecimal.valueOf(r)) }
        val aggregate = BacktestMetrics.compute("SBER", equity, tradeReturns = returns)
        val positiveFolds = (consistency * 4).toInt()
        val folds =
            (0 until 4).map { i ->
                FoldValidation(
                    foldIndex = i,
                    inSample = aggregate,
                    outOfSample = aggregate.copy(totalReturn = if (i < positiveFolds) 0.02 else -0.01),
                    chosenSlPercent = 0.02,
                    chosenTpPercent = 0.04,
                )
            }
        return ValidationResult(folds, aggregate)
    }

    private fun holdout(
        passable: Boolean = true,
        trades: Int = 250,
    ): HoldoutValidation {
        val returns = List(trades) { if (it % 2 == 0) 40.0 else -15.0 }
        val hEquity =
            if (passable) {
                returns.runningFold(capital) { acc, r -> acc.add(BigDecimal.valueOf(r)) }
            } else {
                List(returns.size + 1) { capital.subtract(BigDecimal.valueOf(it * 50L)) }
            }
        val holdoutResult = BacktestMetrics.compute("SBER", hEquity, tradeReturns = returns)
        return HoldoutValidation(
            walkForward = strongWalkForward(),
            holdout = holdoutResult,
            paramsUsed = GridParams(slPercent = 0.02, tpPercent = 0.04),
        )
    }

    private fun robustReport(): BacktestRobustnessReport {
        val baseResult = strongBacktest()
        val mc =
            MonteCarloResult(
                simulations = 1000,
                medianReturn = 0.10,
                p5Return = 0.02,
                p95Return = 0.22,
                avgReturn = 0.10,
                minReturn = -0.05,
                maxReturn = 0.30,
                probabilityOfLoss = 0.05,
            )
        val base = StressScenarioResult.of("base", "Базовый", 1.0, 1.0, baseResult)
        return BacktestRobustnessReport(
            base = base,
            monteCarlo = mc,
            stress =
                listOf(
                    StressScenarioResult.of("s1", "Стресс 1", 2.0, 1.0, strongBacktest()),
                    StressScenarioResult.of("s2", "Стресс 2", 1.0, 2.0, strongBacktest()),
                ),
        )
    }

    private fun criteria(
        researchMode: Boolean = false,
        confirmed: Boolean = true,
        holdout: HoldoutValidation? = holdout(),
        robustness: BacktestRobustnessReport? = robustReport(),
        backtest: BacktestResult? = null,
        wf: ValidationResult? = null,
    ): DeploymentCriteria =
        DeploymentCriteria(
            backtest = backtest ?: strongBacktest(),
            validation = wf ?: strongWalkForward(),
            holdout = holdout,
            robustness = robustness,
            researchMode = researchMode,
            confirmedForProduction = confirmed,
        )

    @Test
    fun `all layers strong - live allowed`() {
        val decision = DeploymentGate.decide(criteria())
        assertEquals(DeploymentStatus.LIVE_ALLOWED, decision.status)
        assertTrue(decision.allChecksPassed)
    }

    @Test
    fun `weak backtest - rejected`() {
        val weak =
            strongBacktest().copy(
                sharpeRatio = 0.5,
                profitFactor = 1.0,
                maxDrawdown = 0.6,
            )
        val decision = DeploymentGate.decide(criteria(backtest = weak))
        assertEquals(DeploymentStatus.REJECTED, decision.status)
    }

    @Test
    fun `weak walk-forward - research only`() {
        val weakWf =
            ValidationResult(
                folds = emptyList(),
                aggregateOutOfSample = strongBacktest().copy(sharpeRatio = 0.3, profitFactor = 1.05),
            )
        val decision = DeploymentGate.decide(criteria(wf = weakWf))
        assertEquals(DeploymentStatus.RESEARCH_ONLY, decision.status)
    }

    @Test
    fun `failing holdout - paper only`() {
        val decision = DeploymentGate.decide(criteria(holdout = holdout(passable = false)))
        assertEquals(DeploymentStatus.PAPER_ALLOWED, decision.status)
    }

    @Test
    fun `insufficient holdout trades - paper only`() {
        val decision = DeploymentGate.decide(criteria(holdout = holdout(trades = 5)))
        assertEquals(DeploymentStatus.PAPER_ALLOWED, decision.status)
    }

    @Test
    fun `missing robustness - paper only`() {
        val decision = DeploymentGate.decide(criteria(robustness = null))
        assertEquals(DeploymentStatus.PAPER_ALLOWED, decision.status)
    }

    @Test
    fun `non-significant edge - paper only`() {
        // Passable backtest, но edge статистически незначим (probabilityOfNoEdge высокий).
        val backtest = strongBacktest().copy(edgeStatisticallySignificant = false, probabilityOfNoEdge = 0.9)
        val decision = DeploymentGate.decide(criteria(backtest = backtest))
        assertEquals(DeploymentStatus.PAPER_ALLOWED, decision.status)
        assertTrue(decision.checks.first { it.key == "edge_significance" }.passed == false)
    }

    @Test
    fun `research mode never allows live`() {
        val decision = DeploymentGate.decide(criteria(researchMode = true))
        // Все проверки технически пройдены, но research-режим запрещает LIVE.
        assertEquals(DeploymentStatus.PAPER_ALLOWED, decision.status)
        assertTrue(decision.allChecksPassed)
        assertFalse(decision.status == DeploymentStatus.LIVE_ALLOWED)
    }

    @Test
    fun `confirmation flag gates live access`() {
        val decision = DeploymentGate.decide(criteria(confirmed = false))
        assertEquals(DeploymentStatus.PAPER_ALLOWED, decision.status)
    }

    @Test
    fun `missing holdout - paper only not live`() {
        val decision = DeploymentGate.decide(criteria(holdout = null))
        assertEquals(DeploymentStatus.PAPER_ALLOWED, decision.status)
    }

    @Test
    fun `low walk-forward consistency caps at paper`() {
        val wf = strongWalkForward(consistency = 0.5)
        val decision = DeploymentGate.decide(criteria(wf = wf))
        assertEquals(DeploymentStatus.RESEARCH_ONLY, decision.status)
    }

    @Test
    fun `insufficient walk-forward trades caps at research`() {
        val wf = strongWalkForward(trades = 50)
        val decision = DeploymentGate.decide(criteria(wf = wf))
        assertEquals(DeploymentStatus.RESEARCH_ONLY, decision.status)
    }

    @Test
    fun `checks expose per-layer detail`() {
        val decision = DeploymentGate.decide(criteria())
        val keys = decision.checks.map { it.key }
        assertTrue("backtest" in keys)
        assertTrue("walk_forward" in keys)
        assertTrue("holdout" in keys)
        assertTrue("robustness" in keys)
        assertTrue(decision.checks.all { it.detail.isNotBlank() })
    }
}
