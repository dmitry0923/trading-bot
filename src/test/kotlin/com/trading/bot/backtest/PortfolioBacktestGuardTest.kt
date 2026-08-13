package com.trading.bot.backtest

import com.trading.bot.config.TradingConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class PortfolioBacktestGuardTest {
    private val panelService = mock<PanelBacktestService>()
    private val tradingConfig = TradingConfig()
    private val meterRegistry = SimpleMeterRegistry()

    private fun panelResponse(summary: PanelBacktestSummary): PanelBacktestResponse =
        PanelBacktestResponse(
            tickers = tradingConfig.tickers,
            days = 365,
            timeframe = "MINUTE_10",
            initialCapital = BigDecimal("100000"),
            slPercent = 0.02,
            tpPercent = 0.04,
            minBarsForSignal = 30,
            results = emptyList(),
            summary = summary,
        )

    private fun summaryOf(passable: List<Boolean>): PanelBacktestSummary =
        PanelBacktestSummarizer.summarize(
            passable.map { p ->
                PanelTickerSummary(
                    ticker = "X",
                    totalReturn = if (p) 0.3 else -0.1,
                    sharpeRatio = if (p) 1.5 else 0.5,
                    sortinoRatio = 1.0,
                    maxDrawdown = if (p) 0.1 else 0.3,
                    winRate = 0.5,
                    profitFactor = if (p) 1.5 else 0.8,
                    totalTrades = 120,
                    passable = p,
                )
            },
        )

    private suspend fun stubPanel(passable: List<Boolean>): PanelBacktestResponse {
        val panel = panelResponse(summaryOf(passable))
        whenever(panelService.run(any())).thenReturn(panel)
        return panel
    }

    @Test
    fun `checkPortfolio runs panel over all configured tickers`() =
        runBlocking {
            val guard = PortfolioBacktestGuard(panelService, tradingConfig, meterRegistry)
            var capturedTickers: List<String>? = null
            whenever(panelService.run(any())).thenAnswer { inv ->
                val request = inv.getArgument<PanelBacktestRequest>(0)
                capturedTickers = request.tickers
                panelResponse(summaryOf(listOf(true, false, true, true, false, true, true, false, true, true)))
            }

            val check = guard.checkPortfolio()

            verify(panelService).run(any())
            assertEquals(tradingConfig.tickers, capturedTickers)
            assertTrue(check.verdict.accepted)
            assertEquals(7, check.verdict.passCount)
            assertEquals(10, check.verdict.tickerCount)
            assertEquals(0.7, check.verdict.passShare, 1e-9)
            assertEquals(0.0, meterRegistry.counter("bt.portfolio.gate", "verdict", "REJECT").count())
            assertEquals(1.0, meterRegistry.counter("bt.portfolio.gate", "verdict", "PASS").count())
            assertEquals(0.7, meterRegistry.get("bt.portfolio.pass_share").gauge().value(), 1e-9)
        }

    @Test
    fun `checkPortfolio rejects when minority passes`() =
        runBlocking {
            val guard = PortfolioBacktestGuard(panelService, tradingConfig, meterRegistry)
            stubPanel(listOf(true, false, false, false))

            val check = guard.checkPortfolio()

            assertFalse(check.verdict.accepted)
            assertEquals(1, check.verdict.passCount)
            assertEquals(0.25, check.verdict.passShare, 1e-9)
        }

    @Test
    fun `checkPortfolio rejects empty portfolio`() =
        runBlocking {
            val guard = PortfolioBacktestGuard(panelService, tradingConfig, meterRegistry)
            stubPanel(emptyList())

            val check = guard.checkPortfolio()

            assertFalse(check.verdict.accepted)
            assertEquals(0, check.verdict.tickerCount)
        }

    @Test
    fun `evaluate records reject metric`() {
        val guard = PortfolioBacktestGuard(panelService, tradingConfig, meterRegistry)

        val verdict = guard.evaluate(summaryOf(listOf(false, false)))

        assertFalse(verdict.accepted)
        assertEquals(1.0, meterRegistry.counter("bt.portfolio.gate", "verdict", "REJECT").count())
        assertEquals(0.0, meterRegistry.counter("bt.portfolio.gate", "verdict", "PASS").count())
    }
}
