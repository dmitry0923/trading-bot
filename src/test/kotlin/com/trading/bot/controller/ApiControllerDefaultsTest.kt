package com.trading.bot.controller

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.application.TradingGate
import com.trading.bot.backtest.BacktestEngine
import com.trading.bot.backtest.BacktestResult
import com.trading.bot.backtest.FinalHoldoutValidator
import com.trading.bot.backtest.HistoricalDataLoader
import com.trading.bot.backtest.LoadResult
import com.trading.bot.backtest.MonteCarloAnalyzer
import com.trading.bot.backtest.PanelBacktestService
import com.trading.bot.backtest.PortfolioBacktestGuard
import com.trading.bot.backtest.ValidationResult
import com.trading.bot.backtest.WalkForwardAnalyzer
import com.trading.bot.backtest.WfaConfig
import com.trading.bot.config.BacktestConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.infrastructure.tracing.LlmTrace
import com.trading.bot.model.CloseReason
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.RagAnalysis
import com.trading.bot.model.dto.RagAnalyzeRequest
import com.trading.bot.model.dto.RagTraceRequest
import com.trading.bot.model.entity.Position
import com.trading.bot.model.entity.Strategy
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.BacktestResultRepository
import com.trading.bot.repository.BlindSpotRepository
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.DailyRiskSnapshotRepository
import com.trading.bot.repository.FundingHistoryRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.repository.StrategyAdjustmentRepository
import com.trading.bot.repository.StrategyRepository
import com.trading.bot.repository.TradeEventRepository
import com.trading.bot.service.AdaptiveRiskService
import com.trading.bot.service.ClearingService
import com.trading.bot.service.DashboardService
import com.trading.bot.service.DashboardSseService
import com.trading.bot.service.DeploymentApprovalService
import com.trading.bot.service.DrawdownProtectionService
import com.trading.bot.service.EmergencyStopService
import com.trading.bot.service.FrozenStrategyStore
import com.trading.bot.service.InvestorService
import com.trading.bot.service.LineageService
import com.trading.bot.service.LiveStrategyFingerprintProvider
import com.trading.bot.service.PaperTradingService
import com.trading.bot.service.ProfitForecastService
import com.trading.bot.service.RagErrorAnalyzer
import com.trading.bot.service.RedisCacheService
import com.trading.bot.service.RiskManagementService
import com.trading.bot.service.SettingsService
import com.trading.bot.service.StrategyService
import com.trading.bot.service.TraceQueryService
import com.trading.bot.service.TradeAnalysisService
import com.trading.bot.service.TradingAccountService
import com.trading.bot.service.TradingBotService
import com.trading.bot.service.TradingControlService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [ApiController] — ветви default/`?:` на тяжёлых research-эндпоинтах без
 * 95-аргументного вызова: запросы идут через MockMvc (Spring сам подставляет
 * query-параметры и defaults), движок/анализаторы — моки.
 *
 * Покрывает: `/backtest` без overrides и с ними (сборка генератора), ресемплинг
 * ТФ, `loadHistory`, явные `leverage`/`capitalSlice`/`includeTrades`, а также
 * guard-ветви `/strategies/{ticker}` и `/positions/{id}/resolve-reconciliation`.
 */
class ApiControllerDefaultsTest {
    private val sampleResult =
        BacktestResult(
            ticker = "SBER",
            totalReturn = 1.5,
            sharpeRatio = 0.7,
            maxDrawdown = 2.0,
            winRate = 0.5,
            profitFactor = 1.3,
            totalTrades = 2,
            avgHoldBars = 5.0,
            equityCurve = listOf(BigDecimal("100000"), BigDecimal("101500")),
            monthlyReturns = emptyMap(),
        )
    private val candleRepository = mock<CandleRepository>()
    private val backtestConfig = BacktestConfig()
    private val backtestEngine =
        BacktestEngine(
            candleRepo = candleRepository,
            instrumentsConfig = InstrumentsConfig(),
            backtestConfig = backtestConfig,
            meterRegistry = SimpleMeterRegistry(),
        )
    private val historicalDataLoader = mock<HistoricalDataLoader>()
    private val walkForwardAnalyzer = mock<WalkForwardAnalyzer>()
    private val strategyRepository = mock<StrategyRepository>()
    private val positionRepository = mock<PositionRepository>()
    private val redisCacheService = mock<RedisCacheService>()
    private val paperTradingService = mock<PaperTradingService>()
    private val ragErrorAnalyzer = mock<RagErrorAnalyzer>()
    private val traceQueryService = mock<TraceQueryService>()
    private val tradingAccountService = mock<TradingAccountService>()
    private val dailyRiskSnapshotRepository = mock<DailyRiskSnapshotRepository>()
    private val drawdownProtectionService = mock<DrawdownProtectionService>()
    private val dashboardService = mock<DashboardService>()

    private val controller: ApiController =
        ApiController(
            strategyRepository = strategyRepository,
            positionRepository = positionRepository,
            agentLogRepository = mock<AgentLogRepository>(),
            redisCacheService = redisCacheService,
            riskManagementService = mock<RiskManagementService>(),
            drawdownProtectionService = drawdownProtectionService,
            strategyService = mock<StrategyService>(),
            tradingBotService = mock<TradingBotService>(),
            settingsService = mock<SettingsService>(),
            tradeAnalysisService = mock<TradeAnalysisService>(),
            adaptiveRiskService = mock<AdaptiveRiskService>(),
            blindSpotRepository = mock<BlindSpotRepository>(),
            adjustmentRepository = mock<StrategyAdjustmentRepository>(),
            tradeEventRepository = mock<TradeEventRepository>(),
            backtestEngine = backtestEngine,
            walkForwardAnalyzer = walkForwardAnalyzer,
            monteCarloAnalyzer = mock<MonteCarloAnalyzer>(),
            finalHoldoutValidator = mock<FinalHoldoutValidator>(),
            backtestResultRepository = mock<BacktestResultRepository>(),
            panelBacktestService = mock<PanelBacktestService>(),
            portfolioBacktestGuard = mock<PortfolioBacktestGuard>(),
            backtestConfig = backtestConfig,
            riskConfig = RiskConfig(),
            objectMapper = ObjectMapper(),
            historicalDataLoader = historicalDataLoader,
            candleRepository = candleRepository,
            dailyRiskSnapshotRepository = dailyRiskSnapshotRepository,
            dashboardService = dashboardService,
            dashboardSseService = mock<DashboardSseService>(),
            investorService = mock<InvestorService>(),
            clearingService = mock<ClearingService>(),
            profitForecastService = mock<ProfitForecastService>(),
            tradingAccountService = tradingAccountService,
            tradingControlService = mock<TradingControlService>(),
            emergencyStopService = mock<EmergencyStopService>(),
            tradingGate = mock<TradingGate>(),
            paperTradingService = paperTradingService,
            lineageService = mock<LineageService>(),
            ragErrorAnalyzer = ragErrorAnalyzer,
            traceQueryService = traceQueryService,
            meterRegistry = SimpleMeterRegistry(),
            deploymentApprovalService = mock<DeploymentApprovalService>(),
            liveStrategyFingerprintProvider = mock<LiveStrategyFingerprintProvider>(),
            frozenStrategyStore = mock<FrozenStrategyStore>(),
            moexFundingHistoryLoader = mock<com.trading.bot.backtest.MoexFundingHistoryLoader>(),
            fundingHistoryRepository = mock<FundingHistoryRepository>(),
            contrarianAgent = mock<ContrarianAgent>(),
            arbitratorAgent = mock<ArbitratorAgent>(),
        )

    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(controller).build()

    private fun stubCandles() {
        runBlocking {
            whenever(
                candleRepository.findByTickerAndTimeframeAndTimeBetween(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    anyLocalDateTime(),
                    anyLocalDateTime(),
                ),
            ).thenReturn(emptyList())
            whenever(historicalDataLoader.loadAndSave(Mockito.anyString(), Mockito.anyInt())).thenReturn(LoadResult("SBER", 0, 0))
        }
    }

    @Test
    fun `backtest endpoint resolves defaults and explicit overrides`() {
        stubCandles()

        val defaults = dispatch(get("/api/v1/backtest/SBER").param("days", "10"))
        assertEquals(200, defaults.status)
        assertTrue(defaults.contentAsString.contains("\"ticker\":\"SBER\""), defaults.contentAsString)
        assertTrue(defaults.contentAsString.contains("\"totalTrades\":0"), defaults.contentAsString)
        assertTrue(defaults.contentAsString.contains("\"trades\":[]"), defaults.contentAsString)

        // Явные значения: время, плечо, capitalSlice, trade-log.
        val explicit =
            dispatch(
                get("/api/v1/backtest/SBER")
                    .param("days", "10")
                    .param("timeframe", "MINUTE_10")
                    .param("leverage", "2.0")
                    .param("capitalSlice", "0.5")
                    .param("riskPerTradePercent", "5.0")
                    .param("futuresMaxContractsPerPosition", "3")
                    .param("includeTrades", "true"),
            )
        assertEquals(200, explicit.status)
        assertTrue(explicit.contentAsString.contains("\"trades\":[]"), explicit.contentAsString)
    }

    @Test
    fun `backtest endpoint resamples a research timeframe`() {
        stubCandles()

        val response =
            dispatch(
                get("/api/v1/backtest/SBER")
                    .param("days", "10")
                    .param("timeframe", "HOUR_1"),
            )

        assertEquals(200, response.status)
        assertTrue(response.contentAsString.contains("\"ticker\":\"SBER\""), response.contentAsString)
    }

    @Test
    fun `backtest endpoint loads history on demand`() {
        stubCandles()

        val response =
            dispatch(
                get("/api/v1/backtest/SBER")
                    .param("days", "10")
                    .param("loadHistory", "true"),
            )

        assertEquals(200, response.status)
        runBlocking { verify(historicalDataLoader).loadAndSave("SBER", 10) }
    }

    @Test
    fun `backtest endpoint builds a filtered signal generator when overrides are provided`() {
        stubCandles()

        val response =
            dispatch(
                get("/api/v1/backtest/SBER")
                    .param("days", "10")
                    .param("sessionFilterEnabled", "true")
                    .param("sessionFilterStartMinutes", "600")
                    .param("sessionFilterEndMinutes", "1080")
                    .param("orbEnabled", "true")
                    .param("orbWindowBars", "3")
                    .param("squeezeEnabled", "true")
                    .param("llmVetoEnabled", "false"),
            )

        assertEquals(200, response.status)
        assertTrue(response.contentAsString.contains("\"ticker\":\"SBER\""), response.contentAsString)
    }

    @Test
    fun `backtest endpoint builds the llm veto and regime aware generator`() {
        stubCandles()
        backtestConfig.regimeDetectionEnabled = true
        try {
            val response =
                dispatch(
                    get("/api/v1/backtest/SBER")
                        .param("days", "10")
                        .param("llmVetoEnabled", "true")
                        .param("llmVetoBlockOnUnknown", "true")
                        .param("mlDirectionEnabled", "true")
                        .param("mlDirectionBlockOnUnknown", "false"),
                )

            assertEquals(200, response.status)
        } finally {
            backtestConfig.regimeDetectionEnabled = false
        }
    }

    @Test
    fun `strategy snapshot returns not found and the mapped payload`() {
        val strategy =
            Strategy(
                id = 7L,
                ticker = "GAZP",
                action = StrategyAction.BUY,
                targetPrice = BigDecimal("101"),
                quantity = 1,
                stopLoss = BigDecimal("99"),
                takeProfit = BigDecimal("105"),
                signalStrength = 0.7,
                reasoning = "trend",
                cycleId = "c1",
                validUntil = LocalDateTime.of(2026, 10, 31, 12, 0),
            )
        runBlocking {
            whenever(redisCacheService.getStrategy("SBER")).thenReturn(null)
            whenever(redisCacheService.getStrategy("GAZP")).thenReturn(strategy)
        }

        val missing = runCatching { runBlocking { controller.getStrategy("SBER") } }
        assertTrue(missing.exceptionOrNull() is org.springframework.web.server.ResponseStatusException, "${missing.exceptionOrNull()}")

        val payload = runBlocking { controller.getStrategy("GAZP") }
        assertEquals("GAZP", payload["ticker"])
        assertEquals("BUY", payload["action"]?.toString())
        assertEquals(0.7, payload["confidence"])
    }

    @Test
    fun `validate endpoint resolves query defaults and explicit overrides`() {
        stubCandles()
        val validation = ValidationResult(folds = emptyList(), aggregateOutOfSample = sampleResult)
        runBlocking {
            whenever(walkForwardAnalyzer.run(Mockito.anyString(), Mockito.anyList(), anyWfaConfig()))
                .thenReturn(validation)
        }

        val defaults = dispatch(get("/api/v1/backtest/SBER/validate").param("days", "10"))
        assertEquals(200, defaults.status)
        assertTrue(defaults.contentAsString.contains("\"oosTrades\":2"), defaults.contentAsString)

        val overrides =
            dispatch(
                get("/api/v1/backtest/SBER/validate")
                    .param("days", "10")
                    .param("folds", "2")
                    .param("leverage", "2.0")
                    .param("riskPerTradePercent", "5.0")
                    .param("futuresMaxContractsPerPosition", "3")
                    .param("maxHoldBars", "5")
                    .param("adaptiveConfidenceThreshold", "0.7")
                    .param("timeframe", "HOUR_1")
                    .param("includeOosTrades", "true")
                    .param("sessionFilterEnabled", "true"),
            )

        assertEquals(200, overrides.status)
        assertTrue(overrides.contentAsString.contains("\"timeframe\":\"HOUR_1\""), overrides.contentAsString)
    }

    @Test
    fun `holdout without candles returns not found`() {
        stubCandles()

        val response = dispatch(get("/api/v1/backtest/SBER/holdout").param("days", "10"))

        assertEquals(404, response.status)
    }

    private fun dispatch(builder: MockHttpServletRequestBuilder): MockHttpServletResponse {
        val initial = mockMvc.perform(builder).andReturn()
        if (!initial.request.isAsyncStarted) return initial.response
        val dispatched =
            mockMvc
                .perform(asyncDispatch(initial))
                .andReturn()
        return dispatched.response
    }

    @Test
    fun `reconciliation resolver validates status and the position state`() {
        val missing = runCatching { runBlocking { controller.resolveReconciliation(1L, "nonsense", null) } }
        assertThrows(IllegalArgumentException::class.java) { missing.getOrThrow() }

        runBlocking { whenever(positionRepository.findByIdOrNull(1L)).thenReturn(null) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { controller.resolveReconciliation(1L, "open", null) }
        }

        val wrongState =
            Position(
                id = 2L,
                ticker = "SBER",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("100"),
                status = PositionStatus.OPEN,
            )
        runBlocking { whenever(positionRepository.findByIdOrNull(2L)).thenReturn(wrongState) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { controller.resolveReconciliation(2L, "closed", null) }
        }
    }

    @Test
    fun `reconciliation resolver closes the position releases the slot and maps the result`() {
        val required =
            Position(
                id = 3L,
                ticker = "SBER",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("100"),
                status = PositionStatus.RECONCILIATION_REQUIRED,
                accountId = 5L,
            )
        runBlocking {
            whenever(positionRepository.findByIdOrNull(3L)).thenReturn(required)
            whenever(positionRepository.resolveReconciliation(3L, PositionStatus.CLOSED, CloseReason.RECONCILE_PHANTOM))
                .thenReturn(true)
        }

        val response = runBlocking { controller.resolveReconciliation(3L, "closed", null) }

        assertEquals(3L, response["positionId"])
        assertEquals(true, response["updated"])
        assertEquals("CLOSED", response["status"])
        runBlocking { verify(positionRepository).releaseEntry("SBER", 5L) }
    }

    @Test
    fun `reconciliation resolver keeps the slot when the update loses the race`() {
        val required =
            Position(
                id = 4L,
                ticker = "SBER",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("100"),
                status = PositionStatus.RECONCILIATION_REQUIRED,
                accountId = 5L,
            )
        runBlocking {
            whenever(positionRepository.findByIdOrNull(4L)).thenReturn(required)
            whenever(positionRepository.resolveReconciliation(4L, PositionStatus.CLOSED, CloseReason.RECONCILE_PHANTOM))
                .thenReturn(false)
        }

        // Невалидный closeReason заменяется дефолтным RECONCILE_PHANTOM.
        val response = runBlocking { controller.resolveReconciliation(4L, "closed", "not-a-reason") }

        assertEquals(false, response["updated"])
        runBlocking { verify(positionRepository, org.mockito.kotlin.never()).releaseEntry("SBER", 5L) }
    }

    @Test
    fun `traces endpoint covers key cycle and recent branches`() {
        val trace = mock<LlmTrace>()
        runBlocking {
            whenever(traceQueryService.getByStorageKey("k1")).thenReturn(trace)
            whenever(traceQueryService.getByStorageKey("missing")).thenReturn(null)
            whenever(traceQueryService.listByCycleId("c1", 5)).thenReturn(emptyList())
            whenever(traceQueryService.listRecent(7)).thenReturn(emptyList())
        }

        assertEquals(trace, runBlocking { controller.getTraces("k1", null, 20) }["trace"])

        val notFound = runCatching { runBlocking { controller.getTraces("missing", null, 20) } }
        assertTrue(notFound.exceptionOrNull() is ResponseStatusException, "${notFound.exceptionOrNull()}")

        assertEquals(emptyList<Any>(), runBlocking { controller.getTraces(null, "c1", 5) }["traces"])
        assertEquals(emptyList<Any>(), runBlocking { controller.getTraces(" ", null, 7) }["traces"])
    }

    @Test
    fun `rag endpoints validate blank inputs and delegate valid requests`() {
        val analysis = RagAnalysis(query = "q", mode = "RULE_BASED", report = "report", retrievedTraces = emptyList())
        runBlocking {
            whenever(
                ragErrorAnalyzer.analyze(
                    Mockito.anyString(),
                    Mockito.nullable(String::class.javaObjectType),
                    Mockito.nullable(Int::class.javaObjectType),
                ),
            ).thenReturn(analysis)
            whenever(
                ragErrorAnalyzer.analyzeTrace(Mockito.anyString(), Mockito.nullable(Int::class.javaObjectType)),
            ).thenReturn(analysis)
        }

        assertThrows<IllegalArgumentException> { runBlocking { controller.ragAnalyze(RagAnalyzeRequest(" ")) } }
        assertEquals(analysis, runBlocking { controller.ragAnalyze(RagAnalyzeRequest("q", "SBER", 3)) })

        assertThrows<IllegalArgumentException> { runBlocking { controller.ragAnalyzeTrace(RagTraceRequest(" ")) } }
        assertEquals(analysis, runBlocking { controller.ragAnalyzeTrace(RagTraceRequest("k", 1)) })
    }

    @Test
    fun `experiment decisions and daily pnl history map their payloads`() {
        runBlocking { whenever(paperTradingService.recentDecisions(Mockito.anyInt())).thenReturn(emptyList()) }
        assertEquals(emptyList<Any>(), runBlocking { controller.experimentDecisions(10) })

        whenever(dailyRiskSnapshotRepository.findRecent(Mockito.anyInt())).thenReturn(emptyList())
        assertEquals(emptyList<Any>(), runBlocking { controller.getDailyPnlHistory(500) }["points"])
    }

    @Test
    fun `dashboard validates the account and returns the service payload`() {
        runBlocking {
            whenever(tradingAccountService.findById(99L)).thenReturn(null)
            whenever(tradingAccountService.findById(7L)).thenReturn(mock())
            whenever(dashboardService.build(7L)).thenReturn(mapOf("ok" to true))
        }

        val missing = runCatching { runBlocking { controller.getDashboard(99L) } }
        assertTrue(missing.exceptionOrNull() is ResponseStatusException, "${missing.exceptionOrNull()}")

        assertEquals(mapOf("ok" to true), runBlocking { controller.getDashboard(7L) })
    }

    private fun anyWfaConfig(): WfaConfig {
        Mockito.any(WfaConfig::class.java)
        return WfaConfig()
    }

    private fun anyLocalDateTime(): LocalDateTime {
        Mockito.any(LocalDateTime::class.java)
        return LocalDateTime.now()
    }
}
