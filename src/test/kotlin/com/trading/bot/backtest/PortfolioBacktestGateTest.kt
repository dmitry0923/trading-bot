package com.trading.bot.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortfolioBacktestGateTest {
    private fun summary(passable: List<Boolean>): PanelBacktestSummary {
        val passCount = passable.count { it }
        return PanelBacktestSummary(
            tickerCount = passable.size,
            passCount = passCount,
            passShare = if (passable.isEmpty()) 0.0 else passCount.toDouble() / passable.size,
            avgTotalReturn = 0.0,
            medianTotalReturn = 0.0,
            minTotalReturn = 0.0,
            maxTotalReturn = 0.0,
            totalTrades = 0,
        )
    }

    @Test
    fun `all tickers pass - accepted`() {
        val verdict = PortfolioBacktestGate.evaluate(summary(listOf(true, true, true)))
        assertTrue(verdict.accepted)
        assertEquals(3, verdict.passCount)
        assertEquals(3, verdict.tickerCount)
        assertEquals(1.0, verdict.passShare, 1e-9)
        assertEquals(PortfolioBacktestGate.DEFAULT_MIN_PASS_SHARE, verdict.minPassShare)
    }

    @Test
    fun `majority passes - accepted`() {
        val verdict = PortfolioBacktestGate.evaluate(summary(listOf(true, false, true, false, true)))
        assertTrue(verdict.accepted)
        assertEquals(0.6, verdict.passShare, 1e-9)
    }

    @Test
    fun `exactly half passes - accepted at default threshold`() {
        val verdict = PortfolioBacktestGate.evaluate(summary(listOf(true, false, true, false)))
        assertTrue(verdict.accepted)
        assertEquals(0.5, verdict.passShare, 1e-9)
    }

    @Test
    fun `minority passes - rejected`() {
        val verdict = PortfolioBacktestGate.evaluate(summary(listOf(true, false, false, false)))
        assertFalse(verdict.accepted)
        assertEquals(0.25, verdict.passShare, 1e-9)
    }

    @Test
    fun `none pass - rejected`() {
        val verdict = PortfolioBacktestGate.evaluate(summary(listOf(false, false, false)))
        assertFalse(verdict.accepted)
        assertEquals(0, verdict.passCount)
    }

    @Test
    fun `empty portfolio - rejected`() {
        val verdict = PortfolioBacktestGate.evaluate(summary(emptyList()))
        assertFalse(verdict.accepted)
        assertEquals(0, verdict.tickerCount)
        assertEquals(0.0, verdict.passShare, 1e-9)
    }

    @Test
    fun `stricter threshold rejects half`() {
        val verdict = PortfolioBacktestGate.evaluate(summary(listOf(true, false, true, false)), minPassShare = 0.75)
        assertFalse(verdict.accepted)
        assertEquals(0.75, verdict.minPassShare)
    }

    @Test
    fun `invalid threshold rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PortfolioBacktestGate.evaluate(summary(listOf(true)), minPassShare = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortfolioBacktestGate.evaluate(summary(listOf(true)), minPassShare = 1.5)
        }
    }
}
