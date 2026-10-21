package com.trading.bot.backtest.portfolio

import com.trading.bot.backtest.BacktestResult
import com.trading.bot.backtest.DeploymentStatus
import com.trading.bot.backtest.FoldValidation
import com.trading.bot.backtest.MonteCarloResult
import com.trading.bot.backtest.ValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PortfolioDeploymentGateTest {
    private fun result(
        totalReturn: Double = 0.20,
        sharpe: Double = 1.5,
        mdd: Double = 0.10,
        pf: Double = 1.6,
        trades: Int = 120,
        ciLow: Double = 10.0,
        ciHigh: Double = 50.0,
        significant: Boolean = true,
        pNoEdge: Double = 0.01,
    ) = BacktestResult(
        ticker = "U8",
        totalReturn = totalReturn,
        sharpeRatio = sharpe,
        maxDrawdown = mdd,
        winRate = 0.55,
        profitFactor = pf,
        totalTrades = trades,
        avgHoldBars = 21.0,
        isFutures = true,
        equityCurve = listOf(BigDecimal.ONE, BigDecimal.valueOf(1.2)),
        monthlyReturns = emptyMap(),
        meanTradeCI95Low = ciLow,
        meanTradeCI95High = ciHigh,
        probabilityOfNoEdge = pNoEdge,
        edgeStatisticallySignificant = significant,
    )

    private fun validation(
        oos: BacktestResult = result(),
        consistency: Double = 0.75,
        folds: Int = 5,
    ): ValidationResult =
        ValidationResult(
            folds =
                (0 until folds).map { i ->
                    FoldValidation(
                        foldIndex = i,
                        inSample = result(),
                        outOfSample =
                            if (i.toDouble() / folds < consistency) {
                                result(totalReturn = 0.02)
                            } else {
                                result(totalReturn = -0.01)
                            },
                        chosenSlPercent = 0.0,
                        chosenTpPercent = 0.0,
                    )
                },
            aggregateOutOfSample = oos,
        )

    private fun mc(
        p5: Double = 0.05,
        pLoss: Double = 0.10,
        mdd40: Double = 0.10,
        ruin: Double = 0.01,
    ) = MonteCarloResult(
        simulations = 2000,
        medianReturn = 0.20,
        p5Return = p5,
        p95Return = 0.40,
        avgReturn = 0.22,
        minReturn = -0.05,
        maxReturn = 0.60,
        probabilityOfLoss = pLoss,
        probabilityMddExceeds40 = mdd40,
        probabilityOfRuin = ruin,
    )

    private fun capacity(
        folds: Int = 5,
        rebalances: Int = 12,
        trades: Int = 24,
    ) = (0 until folds).map {
        PortfolioDeploymentGate.FoldCapacity(
            foldIndex = it,
            segmentBars = 466,
            signalBars = 217,
            rebalances = rebalances,
            trades = trades,
        )
    }

    private fun criteria(
        dev: BacktestResult = result(),
        valResult: ValidationResult = validation(),
        holdout: BacktestResult? = result(),
        monteCarlo: MonteCarloResult? = mc(),
        foldCapacity: List<PortfolioDeploymentGate.FoldCapacity> = capacity(),
        researchMode: Boolean = true,
        confirmed: Boolean = false,
        capacityReason: String = "",
    ) = PortfolioDeploymentCriteria(
        devBacktest = dev,
        validation = valResult,
        holdout = holdout,
        monteCarlo = monteCarlo,
        foldsCount = foldCapacity.size,
        degenerateFolds = foldCapacity,
        researchMode = researchMode,
        confirmedForProduction = confirmed,
        capacityReason = capacityReason,
    )

    @Test
    fun `all checks pass in research mode yields PAPER_ALLOWED not LIVE`() {
        val decision = PortfolioDeploymentGate.decide(criteria())
        assertEquals(DeploymentStatus.PAPER_ALLOWED, decision.status)
        assertTrue(decision.allChecksPassed, "all checks expected to pass")
    }

    @Test
    fun `confirmed production and non-research yields LIVE_ALLOWED`() {
        val decision =
            PortfolioDeploymentGate.decide(criteria(researchMode = false, confirmed = true))
        assertEquals(DeploymentStatus.LIVE_ALLOWED, decision.status)
    }

    @Test
    fun `failing dev backtest yields REJECTED`() {
        val decision = PortfolioDeploymentGate.decide(criteria(dev = result(sharpe = 0.2)))
        assertEquals(DeploymentStatus.REJECTED, decision.status)
    }

    @Test
    fun `failing walk forward yields RESEARCH_ONLY`() {
        val decision = PortfolioDeploymentGate.decide(criteria(valResult = validation(consistency = 0.2)))
        assertEquals(DeploymentStatus.RESEARCH_ONLY, decision.status)
    }

    @Test
    fun `degenerate fold blocks walk forward - lesson 11_9`() {
        val degenerate = capacity(rebalances = 2, trades = 4)
        val decision = PortfolioDeploymentGate.decide(criteria(foldCapacity = degenerate))
        assertEquals(DeploymentStatus.RESEARCH_ONLY, decision.status)
        val foldCheck = decision.checks.first { it.key == "fold_capacity" }
        assertFalse(foldCheck.passed, "degenerate folds must fail the capacity check")
    }

    @Test
    fun `empty folds fails capacity check`() {
        val decision =
            PortfolioDeploymentGate.decide(
                criteria(valResult = validation(folds = 0), foldCapacity = emptyList()),
            )
        val foldCheck = decision.checks.first { it.key == "fold_capacity" }
        assertFalse(foldCheck.passed)
    }

    @Test
    fun `capacity reason surfaces when no folds ran`() {
        val decision =
            PortfolioDeploymentGate.decide(
                criteria(
                    valResult = validation(folds = 0),
                    foldCapacity = emptyList(),
                    capacityReason = "INCONCLUSIVE (bars 1631 < 2176 for 8 folds)",
                ),
            )
        val foldCheck = decision.checks.first { it.key == "fold_capacity" }
        assertFalse(foldCheck.passed)
        assertTrue(
            foldCheck.detail.contains("bars 1631 < 2176"),
            "reason must explain why no folds ran, got: ${foldCheck.detail}",
        )
    }

    @Test
    fun `missing holdout and mc cap status at PAPER_ALLOWED even when confirmed`() {
        val decision =
            PortfolioDeploymentGate.decide(
                criteria(holdout = null, monteCarlo = null, researchMode = false, confirmed = true),
            )
        assertEquals(DeploymentStatus.PAPER_ALLOWED, decision.status)
    }

    @Test
    fun `insignificant edge caps at PAPER_ALLOWED`() {
        val weak = result(ciLow = -5.0, ciHigh = 40.0, significant = false, pNoEdge = 0.40)
        val decision =
            PortfolioDeploymentGate.decide(
                criteria(valResult = validation(oos = weak), researchMode = false, confirmed = true),
            )
        assertEquals(DeploymentStatus.PAPER_ALLOWED, decision.status)
    }

    @Test
    fun `undersampled holdout caps at PAPER_ALLOWED`() {
        val decision =
            PortfolioDeploymentGate.decide(
                criteria(holdout = result(trades = 5), researchMode = false, confirmed = true),
            )
        assertEquals(DeploymentStatus.PAPER_ALLOWED, decision.status)
    }

    @Test
    fun `unstable monte carlo caps at PAPER_ALLOWED`() {
        val decision =
            PortfolioDeploymentGate.decide(
                criteria(monteCarlo = mc(p5 = -0.2, pLoss = 0.5), researchMode = false, confirmed = true),
            )
        assertEquals(DeploymentStatus.PAPER_ALLOWED, decision.status)
    }

    @Test
    fun `mdd over forty percent fails dev check`() {
        val decision = PortfolioDeploymentGate.decide(criteria(valResult = validation(oos = result(mdd = 0.55))))
        val mdd = decision.checks.first { it.key == "mdd" }
        assertFalse(mdd.passed)
        assertEquals(DeploymentStatus.REJECTED, decision.status)
    }
}
