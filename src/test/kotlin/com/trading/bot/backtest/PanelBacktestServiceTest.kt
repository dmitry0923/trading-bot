package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import com.trading.bot.config.RiskConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * [PanelBacktestService] — фолбэки запроса на конфиг, загрузка истории,
 * сборка генератора по порогу уверенности и агрегация ответа.
 */
class PanelBacktestServiceTest {
    private val engine = mock<BacktestEngine>()
    private val config = BacktestConfig()
    private val loader = mock<HistoricalDataLoader>()
    private val service = PanelBacktestService(engine, config, loader, RiskConfig())

    private fun result(ticker: String): BacktestResult =
        BacktestResult(
            ticker = ticker,
            totalReturn = 1.0,
            sharpeRatio = 0.5,
            maxDrawdown = 0.1,
            winRate = 0.5,
            profitFactor = 1.2,
            totalTrades = 3,
            avgHoldBars = 1.0,
            equityCurve = emptyList(),
            monthlyReturns = emptyMap(),
        )

    private suspend fun stubEngine() {
        whenever(
            engine.run(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
            ),
        ).thenReturn(result("SBER"))
    }

    @Test
    fun `null request fields fall back to config and load history`(): Unit =
        runBlocking {
            stubEngine()
            whenever(loader.loadAndSaveAll(any(), any())).thenReturn(emptyMap())

            val response =
                service.run(
                    PanelBacktestRequest(
                        tickers = listOf("SBER", "SBER"),
                        loadHistory = true,
                    ),
                )

            assertEquals(listOf("SBER"), response.tickers)
            assertEquals(config.days, response.days)
            assertEquals(config.timeframe, response.timeframe)
            assertEquals(config.initialCapital, response.initialCapital)
            assertEquals(config.slPercent / 100.0, response.slPercent)
            assertEquals(config.tpPercent / 100.0, response.tpPercent)
            assertEquals(config.minBarsForSignal, response.minBarsForSignal)
            assertEquals(1, response.summary.tickerCount)
            assertEquals(1, Mockito.mockingDetails(loader).invocations.size)
        }

    @Test
    fun `explicit request fields and a confidence threshold build a live generator`(): Unit =
        runBlocking {
            stubEngine()

            val response =
                service.run(
                    PanelBacktestRequest(
                        tickers = listOf("SBER"),
                        days = 30,
                        timeframe = "HOUR_1",
                        initialCapital = BigDecimal("50000"),
                        slPercent = 0.01,
                        tpPercent = 0.05,
                        minBarsForSignal = 10,
                        leverage = 2.0,
                        capitalSlice = 0.5,
                        riskPerTradePercent = 0.02,
                        adaptiveConfidenceThreshold = 0.6,
                        slPoints = 100,
                        tpPoints = 200,
                        futuresMaxContractsPerPosition = 1,
                    ),
                )

            assertEquals(30, response.days)
            assertEquals("HOUR_1", response.timeframe)
            assertEquals(BigDecimal("50000"), response.initialCapital)
            assertEquals(1, response.results.size)
            val generator =
                Mockito
                    .mockingDetails(engine)
                    .invocations
                    .first { it.method.name == "run" }
                    .arguments[15]
            assertEquals(true, generator is LiveStrategyBacktestSignalGenerator)
        }
}
