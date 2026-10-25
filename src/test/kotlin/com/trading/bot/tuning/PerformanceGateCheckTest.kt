package com.trading.bot.tuning

import com.trading.bot.tuning.PerformanceGateCheck.GateVerdict
import com.trading.bot.tuning.PerformanceGateCheck.MonthlyMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit-тесты [PerformanceGateCheck]: вердикт — наихудший из пяти критериев,
 * границы порогов (PF 1.2/1.0, DD 15/20, WR 48/40, Sharpe 0.3/0.0, trades 20/10).
 */
class PerformanceGateCheckTest {
    private val gate = PerformanceGateCheck()

    private fun metrics(
        profitFactor: Double = 2.0,
        maxDrawdownPct: Double = 5.0,
        winRatePct: Double = 60.0,
        sharpe: Double = 1.0,
        tradesCount: Int = 30,
    ) = MonthlyMetrics(profitFactor, maxDrawdownPct, winRatePct, sharpe, tradesCount)

    @Test
    fun `healthy metrics produce PASS and five reasons`() {
        val result = gate.check(metrics())

        assertEquals(GateVerdict.PASS, result.verdict)
        assertEquals(5, result.reasons.size, "one reason per criterion: ${result.reasons}")
        assertTrue(result.reasons.all { it.startsWith("PASS:") }, "all criteria must pass: ${result.reasons}")
        assertEquals(2.0, result.metrics.profitFactor)
    }

    @Test
    fun `losing profit factor produces ALERT`() {
        val result = gate.check(metrics(profitFactor = 0.9))

        assertEquals(GateVerdict.ALERT, result.verdict)
        assertTrue(result.reasons.any { it.startsWith("ALERT:") && it.contains("PF=0.90") }, "${result.reasons}")
    }

    @Test
    fun `pf in warn band downgrades verdict to WARN`() {
        assertEquals(GateVerdict.WARN, gate.check(metrics(profitFactor = 1.1)).verdict)
        // Ровно на пороге PF_WARN (1.0) — уже не WARN, а ALERT (условие strict >).
        assertEquals(GateVerdict.ALERT, gate.check(metrics(profitFactor = 1.0)).verdict)
    }

    @Test
    fun `drawdown and win rate boundaries are warn band`() {
        assertEquals(GateVerdict.WARN, gate.check(metrics(maxDrawdownPct = 15.0)).verdict)
        assertEquals(GateVerdict.ALERT, gate.check(metrics(maxDrawdownPct = 20.0)).verdict)
        assertEquals(GateVerdict.WARN, gate.check(metrics(winRatePct = 48.0)).verdict)
        assertEquals(GateVerdict.WARN, gate.check(metrics(winRatePct = 40.0)).verdict)
        assertEquals(GateVerdict.ALERT, gate.check(metrics(winRatePct = 39.9)).verdict)
    }

    @Test
    fun `sharpe and sample size boundaries are warn band`() {
        assertEquals(GateVerdict.WARN, gate.check(metrics(sharpe = 0.0)).verdict)
        assertEquals(GateVerdict.ALERT, gate.check(metrics(sharpe = -0.1)).verdict)
        assertEquals(GateVerdict.WARN, gate.check(metrics(tradesCount = 19)).verdict)
        assertEquals(GateVerdict.WARN, gate.check(metrics(tradesCount = 10)).verdict)
        assertEquals(GateVerdict.ALERT, gate.check(metrics(tradesCount = 9)).verdict)
    }

    @Test
    fun `worst criterion wins over better ones`() {
        val result = gate.check(metrics(profitFactor = 2.0, maxDrawdownPct = 25.0, sharpe = 2.0))

        assertEquals(GateVerdict.ALERT, result.verdict, "single critical drawdown must override PASS criteria")
        assertTrue(result.reasons.count { it.startsWith("PASS:") } >= 3, "${result.reasons}")
    }
}
