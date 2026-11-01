package com.trading.bot.controller

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.application.TradingGate
import com.trading.bot.backtest.BacktestEngine
import com.trading.bot.backtest.BacktestResult
import com.trading.bot.backtest.FinalHoldoutValidator
import com.trading.bot.backtest.FrozenStrategy
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
import com.trading.bot.model.dto.InvestorView
import com.trading.bot.model.dto.RagAnalysis
import com.trading.bot.model.dto.RagAnalyzeRequest
import com.trading.bot.model.dto.RagTraceRequest
import com.trading.bot.model.dto.TradeStats
import com.trading.bot.model.entity.BacktestResultEntity
import com.trading.bot.model.entity.BotSettings
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
import com.trading.bot.service.BuildIdentity
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

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
    private val deploymentApprovalService = mock<DeploymentApprovalService>()
    private val buildIdentity = BuildIdentity()
    private val liveStrategyFingerprintProvider = LiveStrategyFingerprintProvider(buildIdentity)
    private val finalHoldoutValidator: FinalHoldoutValidator by lazy {
        FinalHoldoutValidator(
            walkForwardAnalyzer = walkForwardAnalyzer,
            backtestEngine = backtestEngine,
            buildIdentity = buildIdentity,
            fingerprintProvider = liveStrategyFingerprintProvider,
            instrumentsConfig = InstrumentsConfig(),
            backtestConfig = backtestConfig,
        )
    }
    private val settingsService = mock<SettingsService>()
    private val investorService = mock<InvestorService>()
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
    private val frozenStrategyStore = mock<FrozenStrategyStore>()
    private val agentLogRepository = mock<AgentLogRepository>()
    private val blindSpotRepository = mock<BlindSpotRepository>()
    private val adjustmentRepository = mock<StrategyAdjustmentRepository>()
    private val tradeEventRepository = mock<TradeEventRepository>()
    private val backtestResultRepository = mock<BacktestResultRepository>()
    private val tradeAnalysisService = mock<TradeAnalysisService>()
    private val adaptiveRiskService = mock<AdaptiveRiskService>()
    private val riskManagementService = mock<RiskManagementService>()
    private val fundingHistoryRepository = mock<FundingHistoryRepository>()
    private val moexFundingHistoryLoader = mock<com.trading.bot.backtest.MoexFundingHistoryLoader>()
    private val monteCarloAnalyzer = MonteCarloAnalyzer(backtestEngine, backtestConfig)

    private val controller: ApiController =
        ApiController(
            strategyRepository = strategyRepository,
            positionRepository = positionRepository,
            agentLogRepository = agentLogRepository,
            redisCacheService = redisCacheService,
            riskManagementService = riskManagementService,
            drawdownProtectionService = drawdownProtectionService,
            strategyService = mock<StrategyService>(),
            tradingBotService = mock<TradingBotService>(),
            settingsService = settingsService,
            tradeAnalysisService = tradeAnalysisService,
            adaptiveRiskService = adaptiveRiskService,
            blindSpotRepository = blindSpotRepository,
            adjustmentRepository = adjustmentRepository,
            tradeEventRepository = tradeEventRepository,
            backtestEngine = backtestEngine,
            walkForwardAnalyzer = walkForwardAnalyzer,
            monteCarloAnalyzer = monteCarloAnalyzer,
            finalHoldoutValidator = finalHoldoutValidator,
            backtestResultRepository = backtestResultRepository,
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
            investorService = investorService,
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
            deploymentApprovalService = deploymentApprovalService,
            liveStrategyFingerprintProvider = liveStrategyFingerprintProvider,
            frozenStrategyStore = frozenStrategyStore,
            moexFundingHistoryLoader = moexFundingHistoryLoader,
            fundingHistoryRepository = fundingHistoryRepository,
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

    @Test
    fun `robustness endpoint resolves defaults, clamps simulations and uses frozen stops`() {
        stubCandles()
        val frozen =
            FrozenStrategy(
                ticker = "SBER",
                strategyVersion = "v1",
                gitCommitSha = null,
                slPercent = 1.0,
                tpPercent = 2.0,
                slPoints = null,
                tpPoints = null,
                confidenceThreshold = 0.6,
                leverage = 1.0,
                riskPerTradePercent = 5.0,
                futuresMaxContractsPerPosition = 2,
            )
        runBlocking { whenever(frozenStrategyStore.current("SBER")).thenReturn(frozen) }

        val defaults = dispatch(get("/api/v1/backtest/SBER/robustness").param("days", "10"))
        assertEquals(200, defaults.status)
        assertTrue(defaults.contentAsString.contains("\"ticker\":\"SBER\""), defaults.contentAsString)

        val clampedLow = dispatch(get("/api/v1/backtest/SBER/robustness").param("days", "10").param("simulations", "1"))
        assertEquals(200, clampedLow.status)
        assertTrue(clampedLow.contentAsString.contains("\"simulations\":10"), clampedLow.contentAsString)

        // Frozen без %-стопов: slPercent/tpPercent = null → 0.0 (points-политика).
        runBlocking {
            whenever(frozenStrategyStore.current("SBER")).thenReturn(
                frozen.copy(slPercent = null, tpPercent = null),
            )
        }
        assertEquals(200, dispatch(get("/api/v1/backtest/SBER/robustness").param("days", "10")).status)
        runBlocking { whenever(frozenStrategyStore.current("SBER")).thenReturn(frozen) }

        val clampedHigh =
            dispatch(
                get("/api/v1/backtest/SBER/robustness")
                    .param("days", "10")
                    .param("simulations", "1000000")
                    .param("seed", "42")
                    .param("method", "IID")
                    .param("avgBlockLength", "2.0")
                    .param("blockLength", "3"),
            )
        assertEquals(200, clampedHigh.status)
        assertTrue(clampedHigh.contentAsString.contains("\"simulations\":5000"), clampedHigh.contentAsString)
    }

    @Test
    fun `deployment gate without candles returns not found`() {
        stubCandles()

        val response = dispatch(get("/api/v1/backtest/SBER/deployment-gate").param("days", "10"))

        assertEquals(404, response.status)
    }

    @Test
    fun `read endpoints map repository results and clamp limits`() {
        runBlocking {
            whenever(
                agentLogRepository.findFiltered(
                    Mockito.nullable(String::class.javaObjectType),
                    Mockito.nullable(String::class.javaObjectType),
                    Mockito.anyInt(),
                ),
            ).thenReturn(emptyList())
            whenever(riskManagementService.getDailyPnL()).thenReturn(BigDecimal.ZERO)
            whenever(paperTradingService.status()).thenReturn(emptyMap())
            whenever(blindSpotRepository.findByIsActiveTrue()).thenReturn(emptyList())
            whenever(adjustmentRepository.findAll()).thenReturn(emptyList())
            whenever(adjustmentRepository.findByTickerOrderByCreatedAtDesc("SBER")).thenReturn(emptyList())

            whenever(fundingHistoryRepository.hasData("CNYRUBF")).thenReturn(true)
            whenever(fundingHistoryRepository.hasData("SBER")).thenReturn(false)
            assertEquals(true, controller.fundingHistoryStatus("CNYRUBF")["hasHistory"])
            assertEquals(false, controller.fundingHistoryStatus("SBER")["hasHistory"])

            whenever(moexFundingHistoryLoader.loadAndSave("CNYRUBF", 365))
                .thenReturn(LoadResult("CNYRUBF", 5, 5))
            whenever(moexFundingHistoryLoader.loadAndSave("CNYRUBF", 7))
                .thenReturn(LoadResult("CNYRUBF", 2, 2, partial = true))
            assertEquals(5, controller.fundingHistoryBackfill("CNYRUBF", null)["loaded"])
            assertEquals(2, controller.fundingHistoryBackfill("CNYRUBF", 7)["loaded"])

            whenever(backtestResultRepository.findRecent("SBER", 1)).thenReturn(
                listOf(
                    BacktestResultEntity(
                        id = 1L,
                        ticker = "SBER",
                        params = """{"days":10}""",
                        metrics = """{"pf":1.2}""",
                        oos = null,
                    ),
                ),
            )
            val results = controller.backtestResults("SBER", 0)
            assertEquals(1, results["results"].let { (it as List<*>).size })
            org.mockito.kotlin
                .verify(backtestResultRepository)
                .findRecent("SBER", 1)

            assertEquals(emptyList<Any>(), controller.getLogs(null, null, 100))
            assertEquals(0, BigDecimal.ZERO.compareTo(controller.getDailyPnl()["dailyPnl"] as BigDecimal))
            assertEquals(emptyMap<String, Any?>(), controller.experimentStatus())
            assertEquals(emptyList<Any>(), controller.getBlindSpots())
            assertEquals(emptyList<Any>(), controller.getAdjustments(null))
            assertEquals(emptyList<Any>(), controller.getAdjustments("SBER"))
        }
    }

    @Test
    fun `position events analytics health and adaptive params endpoints`() {
        runBlocking {
            whenever(tradeEventRepository.findByAggregateId(org.mockito.kotlin.any())).thenReturn(emptyList())
            assertEquals(emptyList<Any>(), controller.getPositionEvents(5L))

            whenever(tradeAnalysisService.analyzeLastNDays(7)).thenReturn(emptyMap())
            val health = controller.getAnalyticsHealth()
            assertEquals(0, health["totalTickersAnalyzed"])
            assertEquals("0.00%", health["averageWinRate"])

            whenever(tradeAnalysisService.analyzeLastNDays(1)).thenReturn(emptyMap())
            assertEquals(emptyMap<String, Any>(), controller.getTradeStats(0))

            whenever(adaptiveRiskService.diagnoseConfidenceCalibration("SBER")).thenReturn(
                AdaptiveRiskService.ConfidenceCalibrationDiagnostic(
                    ticker = "SBER",
                    source = AdaptiveRiskService.ConfidenceSource.FALLBACK,
                    threshold = 0.6,
                    reason = "cold-start",
                    closedTrades = 0,
                    scoredTrades = 0,
                    sampleSize = 0,
                    winRate = 0.0,
                ),
            )
            whenever(adaptiveRiskService.calculateOptimalPositionSize("SBER")).thenReturn(BigDecimal("1000"))
            whenever(adaptiveRiskService.isInDrawdownRecovery()).thenReturn(false)
            whenever(adaptiveRiskService.shouldPauseTrading("SBER")).thenReturn(false)

            val params = controller.getAdaptiveParams("SBER")
            assertEquals("SBER", params["ticker"])
            assertEquals("FALLBACK", params["confidenceSource"])
            assertEquals(BigDecimal("1000"), params["maxPositionRub"])
            assertEquals(false, params["shouldPause"])
        }
    }

    @Test
    fun `research endpoints use config defaults when query params are absent`() {
        stubCandles()
        val validation = ValidationResult(folds = emptyList(), aggregateOutOfSample = sampleResult)
        runBlocking {
            whenever(walkForwardAnalyzer.run(Mockito.anyString(), Mockito.anyList(), anyWfaConfig()))
                .thenReturn(validation)
        }

        assertEquals(200, dispatch(get("/api/v1/backtest/SBER")).status)
        assertEquals(200, dispatch(get("/api/v1/backtest/SBER/validate")).status)
        assertEquals(200, dispatch(get("/api/v1/backtest/SBER/robustness")).status)
        assertEquals(404, dispatch(get("/api/v1/backtest/SBER/holdout")).status)
        assertEquals(404, dispatch(get("/api/v1/backtest/SBER/deployment-gate")).status)
    }

    @Test
    fun `research endpoints load history on demand`() {
        stubCandles()
        val validation = ValidationResult(folds = emptyList(), aggregateOutOfSample = sampleResult)
        runBlocking {
            whenever(walkForwardAnalyzer.run(Mockito.anyString(), Mockito.anyList(), anyWfaConfig()))
                .thenReturn(validation)
        }

        val paths =
            listOf(
                "backtest/SBER",
                "backtest/SBER/validate",
                "backtest/SBER/robustness",
                "backtest/SBER/holdout",
                "backtest/SBER/deployment-gate",
            )
        paths.forEach { path ->
            dispatch(get("/api/v1/$path").param("days", "10").param("loadHistory", "true"))
        }

        runBlocking { Mockito.verify(historicalDataLoader, Mockito.times(5)).loadAndSave("SBER", 10) }
    }

    @Test
    fun `daily pnl history maps persisted snapshots`() {
        whenever(dailyRiskSnapshotRepository.findRecent(30)).thenReturn(
            listOf(
                com.trading.bot.model.entity.DailyRiskSnapshot(
                    tradeDate = java.time.LocalDate.of(2026, 10, 31),
                    dailyPnl = BigDecimal("-100"),
                    limitReached = false,
                    maxDrawdownToday = BigDecimal("100"),
                ),
            ),
        )

        val history = runBlocking { controller.getDailyPnlHistory(30) }
        val points = history["points"] as List<*>

        assertEquals(1, points.size)
        assertTrue(points.single().toString().contains("2026-10-31"))
    }

    @Test
    fun `reconciliation resolver accepts a valid reason and keeps the slot for OPEN`() {
        val required =
            Position(
                id = 6L,
                ticker = "SBER",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("100"),
                status = PositionStatus.RECONCILIATION_REQUIRED,
                accountId = 5L,
            )
        val captured = arrayOfNulls<CloseReason>(1)
        runBlocking {
            whenever(positionRepository.findByIdOrNull(6L)).thenReturn(required)
            whenever(
                positionRepository.resolveReconciliation(6L, PositionStatus.OPEN, CloseReason.STOP_LOSS),
            ).thenAnswer { invocation ->
                captured[0] = invocation.getArgument(2)
                true
            }
        }

        val response = runBlocking { controller.resolveReconciliation(6L, "open", "stop_loss") }

        assertEquals(true, response["updated"])
        assertEquals(CloseReason.STOP_LOSS, captured[0])
        runBlocking { Mockito.verify(positionRepository, Mockito.never()).releaseEntry("SBER", 5L) }
    }

    @Test
    fun `dashboard stream rejects an unknown account`() {
        runBlocking { whenever(tradingAccountService.findById(99L)).thenReturn(null) }

        val failure = runCatching { controller.streamDashboard(99L) }

        assertTrue(failure.exceptionOrNull() is ResponseStatusException, "${failure.exceptionOrNull()}")
    }

    @Test
    fun `research endpoints accept ml filter veto and execution overrides`() {
        stubCandles()
        val validation = ValidationResult(folds = emptyList(), aggregateOutOfSample = sampleResult)
        runBlocking {
            whenever(walkForwardAnalyzer.run(Mockito.anyString(), Mockito.anyList(), anyWfaConfig()))
                .thenReturn(validation)
        }
        val overrides =
            listOf(
                "mlDirectionEnabled" to "true",
                "mlDirectionBlockOnUnknown" to "false",
                "sessionFilterEnabled" to "true",
                "orbEnabled" to "true",
                "llmVetoEnabled" to "true",
                "llmVetoBlockOnUnknown" to "true",
                "maxHoldBars" to "5",
                "executionPassiveFill" to "true",
                "leverage" to "2.0",
            )

        for (path in listOf("backtest/SBER", "backtest/SBER/validate", "backtest/SBER/robustness")) {
            val builder = get("/api/v1/$path")
            overrides.forEach { (key, value) -> builder.param(key, value) }
            assertEquals(200, dispatch(builder).status, "$path должен принять overrides")
        }

        // holdout/deployment-gate: свечей нет → 404, но overrides и defaults исполняются до guard.
        for (path in listOf("backtest/SBER/holdout", "backtest/SBER/deployment-gate")) {
            val builder =
                get("/api/v1/$path")
                    .param("holdoutFraction", "0.3")
                    .param("timeframe", "HOUR_1")
            overrides.forEach { (key, value) -> builder.param(key, value) }
            assertEquals(404, dispatch(builder).status, path)
        }
    }

    @Test
    fun `config level veto and max hold defaults reach every research endpoint`() {
        stubCandles()
        val validation = ValidationResult(folds = emptyList(), aggregateOutOfSample = sampleResult)
        runBlocking {
            whenever(walkForwardAnalyzer.run(Mockito.anyString(), Mockito.anyList(), anyWfaConfig()))
                .thenReturn(validation)
        }
        val previousVeto = backtestConfig.llmVetoEnabled
        val previousHold = backtestConfig.maxHoldBars
        backtestConfig.llmVetoEnabled = true
        backtestConfig.maxHoldBars = 5
        try {
            for (path in listOf("backtest/SBER", "backtest/SBER/validate", "backtest/SBER/robustness")) {
                assertEquals(200, dispatch(get("/api/v1/$path")).status, path)
            }
            for (path in listOf("backtest/SBER/holdout", "backtest/SBER/deployment-gate")) {
                assertEquals(404, dispatch(get("/api/v1/$path")).status, path)
            }
        } finally {
            backtestConfig.llmVetoEnabled = previousVeto
            backtestConfig.maxHoldBars = previousHold
        }
    }

    @Test
    fun `me endpoint maps authentication and stays empty for anonymous`() {
        val previous = SecurityContextHolder.getContext().authentication
        try {
            SecurityContextHolder.clearContext()
            assertEquals(emptyMap<String, Any>(), controller.me())

            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(
                    "alice",
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
                )
            val me = controller.me()

            assertEquals("alice", me["username"])
            assertEquals(listOf("ROLE_ADMIN"), me["roles"])
        } finally {
            SecurityContextHolder.clearContext()
            if (previous != null) SecurityContextHolder.getContext().authentication = previous
        }
    }

    @Test
    fun `llm providers fall back to ROUTER_AI for a blank configured provider`() {
        whenever(settingsService.getSettings()).thenReturn(BotSettings(llmProvider = "", llmModel = "m1"))

        val blank = controller.getLlmProviders()
        assertEquals("ROUTER_AI", blank["active"])
        assertEquals("m1", blank["model"])

        whenever(settingsService.getSettings()).thenReturn(BotSettings(llmProvider = "OPENAI", llmModel = "m2"))
        assertEquals("OPENAI", controller.getLlmProviders()["active"])
    }

    @Test
    fun `analytics health aggregates stats and lists paused tickers`(): Unit =
        runBlocking {
            val paused =
                TradeStats(
                    ticker = "SBER",
                    totalTrades = 5,
                    winningTrades = 2,
                    losingTrades = 3,
                    winRate = 0.4,
                    avgWin = BigDecimal("100"),
                    avgLoss = BigDecimal("-50"),
                    profitFactor = 1.2,
                    maxConsecutiveLosses = 5,
                    avgHoldTimeMinutes = 30,
                    slHitRate = 0.4,
                    tpHitRate = 0.2,
                    strategyCloseRate = 0.4,
                    bestEntryHour = null,
                    worstEntryHour = null,
                    blindSpots = emptyList(),
                )
            whenever(tradeAnalysisService.analyzeLastNDays(7)).thenReturn(mapOf("SBER" to paused))

            val health = controller.getAnalyticsHealth()

            assertEquals(1, health["totalTickersAnalyzed"])
            assertEquals(5, health["totalTradesLast7Days"])
            assertEquals("40.00%", health["averageWinRate"])
            assertEquals(setOf("SBER"), health["pausedTickers"])
        }

    @Test
    fun `investor endpoints validate the name and parse the amounts`() {
        val view = mock<InvestorView>()
        runBlocking {
            whenever(
                investorService.createInvestor(
                    Mockito.anyString(),
                    Mockito.nullable(String::class.javaObjectType),
                    anyBigDecimal(),
                ),
            ).thenReturn(view)
            whenever(investorService.deposit(org.mockito.kotlin.any(), anyBigDecimal())).thenReturn(view)
        }

        assertThrows<IllegalArgumentException> { runBlocking { controller.createInvestor(mapOf()) } }
        assertEquals(
            view,
            runBlocking { controller.createInvestor(mapOf("name" to "Ivan", "initialDeposit" to "1000")) },
        )
        assertEquals(
            view,
            runBlocking { controller.depositInvestor(UUID.randomUUID(), mapOf("amount" to "500")) },
        )
    }

    @Test
    fun `holdout and deployment gate exercise the real validator pipeline`() {
        stubCandles()
        runBlocking {
            whenever(
                candleRepository.findByTickerAndTimeframeAndTimeBetween(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    anyLocalDateTime(),
                    anyLocalDateTime(),
                ),
            ).thenReturn(sixtyCandles())
            whenever(walkForwardAnalyzer.run(Mockito.anyString(), Mockito.anyList(), anyWfaConfig()))
                .thenReturn(ValidationResult(folds = emptyList(), aggregateOutOfSample = sampleResult))
        }
        val previousSimulations = backtestConfig.monteCarloSimulations
        val previousSeeds = backtestConfig.mcSeedCount
        backtestConfig.monteCarloSimulations = 20
        backtestConfig.mcSeedCount = 1
        try {
            val holdout =
                dispatch(
                    get("/api/v1/backtest/SBER/holdout")
                        .param("days", "10")
                        .param("leverage", "2.0"),
                )
            assertEquals(200, holdout.status)
            assertTrue(holdout.contentAsString.contains("\"holdoutFraction\""), holdout.contentAsString)

            val gate =
                dispatch(
                    get("/api/v1/backtest/SBER/deployment-gate")
                        .param("days", "10")
                        .param("leverage", "2.0")
                        .param("researchMode", "false")
                        .param("confirmedForProduction", "true"),
                )
            assertEquals(200, gate.status)
            assertTrue(gate.contentAsString.contains("\"approvalPersisted\":true"), gate.contentAsString)

            runBlocking {
                Mockito
                    .verify(deploymentApprovalService, Mockito.atLeastOnce())
                    .isLiveAllowed(Mockito.anyString(), Mockito.nullable(String::class.javaObjectType))
            }
        } finally {
            backtestConfig.monteCarloSimulations = previousSimulations
            backtestConfig.mcSeedCount = previousSeeds
        }
    }

    private fun sixtyCandles(): List<com.trading.bot.model.entity.Candle> {
        val start = LocalDateTime.of(2026, 9, 1, 10, 0)
        return (0 until 60).map { i ->
            com.trading.bot.model.entity.Candle(
                ticker = "SBER",
                timeframe = "MINUTE_10",
                openPrice = BigDecimal.valueOf(100.0 + i),
                highPrice = BigDecimal.valueOf(100.5 + i),
                lowPrice = BigDecimal.valueOf(99.5 + i),
                closePrice = BigDecimal.valueOf(100.0 + i),
                volume = 1_000L,
                time = start.plusMinutes(10L * i),
            )
        }
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

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal("1000")
    }
}
