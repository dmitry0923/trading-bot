package com.trading.bot.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PanelBacktestSummarizerTest {
    private fun ticker(
        totalReturn: Double,
        passable: Boolean,
        totalTrades: Int = 10,
    ) = PanelTickerSummary(
        ticker = "X",
        totalReturn = totalReturn,
        sharpeRatio = 1.0,
        sortinoRatio = 1.0,
        maxDrawdown = 0.1,
        winRate = 0.5,
        profitFactor = 1.5,
        totalTrades = totalTrades,
        passable = passable,
    )

    @Test
    fun `empty results produce zero summary`() {
        val summary = PanelBacktestSummarizer.summarize(emptyList())
        assertEquals(0, summary.tickerCount)
        assertEquals(0, summary.passCount)
        assertEquals(0.0, summary.passShare)
        assertEquals(0.0, summary.avgTotalReturn)
        assertEquals(0.0, summary.medianTotalReturn)
        assertEquals(0.0, summary.minTotalReturn)
        assertEquals(0.0, summary.maxTotalReturn)
        assertEquals(0, summary.totalTrades)
    }

    @Test
    fun `summary computes pass share and average returns`() {
        val summary =
            PanelBacktestSummarizer.summarize(
                listOf(
                    ticker(totalReturn = 0.30, passable = true, totalTrades = 20),
                    ticker(totalReturn = -0.10, passable = false, totalTrades = 5),
                    ticker(totalReturn = 0.10, passable = true, totalTrades = 15),
                ),
            )
        assertEquals(3, summary.tickerCount)
        assertEquals(2, summary.passCount)
        assertEquals(2.0 / 3.0, summary.passShare, 1e-9)
        assertEquals(0.10, summary.avgTotalReturn, 1e-9)
        assertEquals(0.10, summary.medianTotalReturn, 1e-9)
        assertEquals(-0.10, summary.minTotalReturn, 1e-9)
        assertEquals(0.30, summary.maxTotalReturn, 1e-9)
        assertEquals(40, summary.totalTrades)
    }

    @Test
    fun `median is average of middle two for even count`() {
        val summary =
            PanelBacktestSummarizer.summarize(
                listOf(
                    ticker(totalReturn = -0.20, passable = false),
                    ticker(totalReturn = 0.10, passable = true),
                    ticker(totalReturn = 0.30, passable = true),
                    ticker(totalReturn = 0.50, passable = true),
                ),
            )
        assertEquals((0.10 + 0.30) / 2.0, summary.medianTotalReturn, 1e-9)
        assertEquals(3, summary.passCount)
        assertEquals(0.75, summary.passShare, 1e-9)
    }
}

class PanelBacktestTimeframeNormalizationTest {
    @Test
    fun `numeric aliases map to canonical names`() {
        assertEquals("MINUTE_10", PanelBacktestService.normalizeTimeframe("10"))
        assertEquals("MINUTE_1", PanelBacktestService.normalizeTimeframe("1"))
        assertEquals("MINUTE_5", PanelBacktestService.normalizeTimeframe("5"))
        assertEquals("MINUTE_15", PanelBacktestService.normalizeTimeframe("15"))
        assertEquals("MINUTE_30", PanelBacktestService.normalizeTimeframe("30"))
        assertEquals("HOUR_1", PanelBacktestService.normalizeTimeframe("60"))
    }

    @Test
    fun `string aliases map to canonical names`() {
        assertEquals("HOUR_1", PanelBacktestService.normalizeTimeframe("1h"))
        assertEquals("DAY_1", PanelBacktestService.normalizeTimeframe("1d"))
    }

    @Test
    fun `canonical names pass through unchanged`() {
        assertEquals("MINUTE_10", PanelBacktestService.normalizeTimeframe("MINUTE_10"))
        assertEquals("HOUR_1", PanelBacktestService.normalizeTimeframe("HOUR_1"))
        assertEquals("DAY_1", PanelBacktestService.normalizeTimeframe("DAY_1"))
    }

    @Test
    fun `case insensitive`() {
        assertEquals("HOUR_1", PanelBacktestService.normalizeTimeframe("1H"))
        assertEquals("DAY_1", PanelBacktestService.normalizeTimeframe("1D"))
    }
}
