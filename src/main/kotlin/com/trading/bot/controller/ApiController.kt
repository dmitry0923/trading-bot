package com.trading.bot.controller

import com.trading.bot.application.TradingGate
import com.trading.bot.backtest.BacktestEngine
import com.trading.bot.backtest.HistoricalDataLoader
import com.trading.bot.config.LlmProvider
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.dto.RagAnalysis
import com.trading.bot.model.dto.RagAnalyzeRequest
import com.trading.bot.model.dto.RagTraceRequest
import com.trading.bot.model.dto.TimePattern
import com.trading.bot.model.dto.TradeStats
import com.trading.bot.model.entity.BlindSpotEntity
import com.trading.bot.model.entity.BotSettings
import com.trading.bot.model.entity.StrategyAdjustment
import com.trading.bot.model.entity.TradeEvent
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.BlindSpotRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.repository.StrategyAdjustmentRepository
import com.trading.bot.repository.StrategyRepository
import com.trading.bot.repository.TradeEventRepository
import com.trading.bot.service.AdaptiveRiskService
import com.trading.bot.service.ClearingService
import com.trading.bot.service.DashboardService
import com.trading.bot.service.DashboardSseService
import com.trading.bot.service.DrawdownProtectionService
import com.trading.bot.service.InvestorService
import com.trading.bot.service.PaperTradingService
import com.trading.bot.service.ProfitForecastService
import com.trading.bot.service.RagErrorAnalyzer
import com.trading.bot.service.RedisCacheService
import com.trading.bot.service.RiskManagementService
import com.trading.bot.service.SettingsService
import com.trading.bot.service.StrategyService
import com.trading.bot.service.TraceQueryService
import com.trading.bot.service.TradeAnalysisService
import com.trading.bot.service.TradingBotService
import com.trading.bot.service.TradingControlService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * REST API для React Dashboard.
 *
 * Отдаёт данные дашборда, позиций, стратегий, логов агентов, аналитики,
 * настроек и бэктеста. Реальный-time обновления дашборда — через SSE
 * `/api/v1/dashboard/stream` ([DashboardSseService]).
 *
 * @see com.trading.bot.service.DashboardService
 * @see com.trading.bot.service.DashboardSseService
 */
@RestController
@RequestMapping("/api/v1")
class ApiController(
    private val strategyRepository: StrategyRepository,
    private val positionRepository: PositionRepository,
    private val agentLogRepository: AgentLogRepository,
    private val redisCacheService: RedisCacheService,
    private val riskManagementService: RiskManagementService,
    private val drawdownProtectionService: DrawdownProtectionService,
    private val strategyService: StrategyService,
    private val tradingBotService: TradingBotService,
    private val settingsService: SettingsService,
    private val tradeAnalysisService: TradeAnalysisService,
    private val adaptiveRiskService: AdaptiveRiskService,
    private val blindSpotRepository: BlindSpotRepository,
    private val adjustmentRepository: StrategyAdjustmentRepository,
    private val tradeEventRepository: TradeEventRepository,
    private val backtestEngine: BacktestEngine,
    private val historicalDataLoader: HistoricalDataLoader,
    private val dashboardService: DashboardService,
    private val dashboardSseService: DashboardSseService,
    private val investorService: InvestorService,
    private val clearingService: ClearingService,
    private val profitForecastService: ProfitForecastService,
    private val tradingControlService: TradingControlService,
    private val tradingGate: TradingGate,
    private val paperTradingService: PaperTradingService,
    private val ragErrorAnalyzer: RagErrorAnalyzer,
    private val traceQueryService: TraceQueryService,
    private val meterRegistry: MeterRegistry,
) {
    @GetMapping("/settings")
    fun getSettings(): BotSettings = settingsService.getSettings()

    @PostMapping("/settings")
    fun updateSettings(
        @RequestBody settings: BotSettings,
    ): BotSettings {
        settingsService.updateSettings(settings)
        return settings
    }

    /**
     * Статус Shadow Mode / Decision-level A/B эксперимента.
     */
    @GetMapping("/experiment/status")
    fun experimentStatus(): Map<String, Any?> = paperTradingService.status()

    /**
     * Включить/выключить эксперимент через BotSettings.
     */
    @PostMapping("/experiment/enable")
    fun experimentEnable(
        @RequestParam enabled: Boolean,
    ): BotSettings {
        val current = settingsService.getSettings()
        val updated = current.copy(experimentEnabled = enabled)
        settingsService.updateSettings(updated)
        return updated
    }

    /**
     * Последние решения эксперимента (обе руки).
     */
    @GetMapping("/experiment/decisions")
    suspend fun experimentDecisions(
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<com.trading.bot.model.entity.ExperimentDecision> = paperTradingService.recentDecisions(limit)

    /**
     * Статус RAG-корпуса и анализатора.
     */
    @GetMapping("/rag/status")
    suspend fun ragStatus(): Map<String, Any?> = ragErrorAnalyzer.status()

    /**
     * Переиндексация RAG-корпуса из S3/MinIO.
     */
    @PostMapping("/rag/refresh")
    suspend fun ragRefresh(): Map<String, Any> {
        val size = ragErrorAnalyzer.refresh()
        return mapOf("indexed" to size, "status" to ragErrorAnalyzer.status())
    }

    /**
     * RAG-анализ ошибки: извлекает похожие трейсы и строит разбор первопричины.
     */
    @PostMapping("/rag/analyze")
    suspend fun ragAnalyze(
        @RequestBody request: RagAnalyzeRequest,
    ): RagAnalysis {
        require(request.query.isNotBlank()) { "query must not be blank" }
        return ragErrorAnalyzer.analyze(request.query, request.ticker, request.k)
    }

    /**
     * RAG-анализ конкретного трейса по storage_key.
     */
    @PostMapping("/rag/analyze-trace")
    suspend fun ragAnalyzeTrace(
        @RequestBody request: RagTraceRequest,
    ): RagAnalysis {
        require(request.storageKey.isNotBlank()) { "storageKey must not be blank" }
        return ragErrorAnalyzer.analyzeTrace(request.storageKey, request.k)
    }

    /**
     * Дешёвый доступ к LLM-трейсам для расследования инцидентов (без LLM-разбора):
     * - `key` — конкретный трейс по storage_key (agent_logs.storage_key);
     * - `cycleId` — все трейсы агентов конкретного цикла;
     * - без параметров — последние трейсы по бакету.
     */
    @GetMapping("/traces")
    suspend fun getTraces(
        @RequestParam(required = false) key: String?,
        @RequestParam(required = false) cycleId: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): Map<String, Any> {
        meterRegistry.counter("api.traces").increment()
        return when {
            !key.isNullOrBlank() -> {
                val trace = traceQueryService.getByStorageKey(key)
                if (trace == null) {
                    throw ResponseStatusException(HttpStatus.NOT_FOUND, "trace not found: $key")
                }
                mapOf("trace" to trace)
            }

            !cycleId.isNullOrBlank() -> {
                mapOf("traces" to traceQueryService.listByCycleId(cycleId, limit))
            }

            else -> {
                mapOf("traces" to traceQueryService.listRecent(limit))
            }
        }
    }

    /**
     * Агрегированная панель для React Dashboard: открытые позиции с live P&L,
     * дневная статистика, paused-тикеры, режим торговли.
     */
    @GetMapping("/dashboard")
    suspend fun getDashboard(): Map<String, Any> {
        meterRegistry.counter("api.dashboard").increment()
        return dashboardService.build()
    }

    /**
     * Real-time поток дашборда (Server-Sent Events).
     *
     * Подписчик немедленно получает текущий снимок, далее — обновления при
     * событиях домена (позиции, стратегии, исполнение, тики цен). Название
     * события: `dashboard`, данные — JSON (см. [com.trading.bot.service.DashboardService.build]).
     */
    @GetMapping("/dashboard/stream", produces = ["text/event-stream"])
    fun streamDashboard(): SseEmitter {
        meterRegistry.counter("api.dashboard.stream").increment()
        return dashboardSseService.subscribe()
    }

    @GetMapping("/strategies")
    suspend fun getStrategies() = strategyRepository.findTop50ByOrderByCreatedAtDesc()

    @GetMapping("/strategies/{ticker}")
    suspend fun getStrategy(
        @PathVariable ticker: String,
    ) = redisCacheService.getStrategy(ticker)
        ?: strategyRepository.findTopByTickerOrderByCreatedAtDesc(ticker)

    @GetMapping("/positions")
    suspend fun getOpenPositions() = positionRepository.findByStatus(PositionStatus.OPEN)

    @GetMapping("/positions/all")
    suspend fun getAllPositions() = positionRepository.findAll()

    @GetMapping("/logs")
    suspend fun getLogs(
        @RequestParam(required = false) ticker: String?,
        @RequestParam(required = false) agent: String?,
        @RequestParam(defaultValue = "100") limit: Int,
    ) = agentLogRepository.findFiltered(ticker, agent, limit)

    @GetMapping("/risk/daily-pnl")
    fun getDailyPnl() = mapOf("dailyPnl" to riskManagementService.getDailyPnL())

    /**
     * Multi-Tier Drawdown Protection: AUM, дневной/скользящие (7д, 30д) лимиты в % от AUM,
     * серия убытков подряд, Shadow/Read-only режим LLM-агента.
     */
    @GetMapping("/risk/drawdown")
    suspend fun getDrawdownStatus() = drawdownProtectionService.computeStatus()

    /**
     * Append-only audit trail позиции (Event Sourcing): события
     * POSITION_OPENED / POSITION_UPDATED / POSITION_CLOSED в порядке sequence.
     */
    @GetMapping("/positions/{positionId}/events")
    suspend fun getPositionEvents(
        @PathVariable positionId: Long,
    ): List<TradeEvent> {
        val aggregateId = UUID.nameUUIDFromBytes("position:$positionId".toByteArray())
        return tradeEventRepository.findByAggregateId(aggregateId)
    }

    @PostMapping("/strategy/trigger")
    fun triggerStrategy() {
        meterRegistry.counter("api.trigger.strategy").increment()
        strategyService.runStrategyCycle()
    }

    @PostMapping("/bot/trigger")
    fun triggerBot() {
        meterRegistry.counter("api.trigger.bot").increment()
        tradingBotService.runBotCycle()
    }

    @GetMapping("/backtest/{ticker}")
    suspend fun backtest(
        @PathVariable ticker: String,
        @RequestParam(defaultValue = "365") days: Int,
        @RequestParam(defaultValue = "false") loadHistory: Boolean,
    ): Map<String, Any> {
        meterRegistry
            .counter(
                "api.backtest",
                Tags
                    .of("ticker", ticker),
            ).increment()
        if (loadHistory) {
            historicalDataLoader.loadAndSave(ticker, days)
        }
        val result = backtestEngine.run(ticker, days)
        return mapOf(
            "ticker" to result.ticker,
            "totalReturn" to result.totalReturn,
            "sharpeRatio" to result.sharpeRatio,
            "maxDrawdown" to result.maxDrawdown,
            "winRate" to result.winRate,
            "profitFactor" to result.profitFactor,
            "totalTrades" to result.totalTrades,
            "passable" to result.isPassable(),
            "equityCurve" to result.equityCurve,
            "timestamp" to
                LocalDateTime
                    .now()
                    .toString(),
        )
    }

    @GetMapping("/analytics/trade-stats")
    suspend fun getTradeStats(
        @RequestParam(defaultValue = "14") days: Int,
    ): Map<String, TradeStats> {
        meterRegistry.counter("api.analytics.trade-stats").increment()
        return tradeAnalysisService.analyzeLastNDays(days)
    }

    @GetMapping("/analytics/adaptive-params/{ticker}")
    suspend fun getAdaptiveParams(
        @PathVariable ticker: String,
    ): Map<String, Any> {
        meterRegistry
            .counter(
                "api.analytics.adaptive-params",
                Tags
                    .of("ticker", ticker),
            ).increment()
        return mapOf(
            "ticker" to ticker,
            "confidenceThreshold" to adaptiveRiskService.getAdaptiveConfidenceThreshold(ticker),
            "maxPositionRub" to adaptiveRiskService.calculateOptimalPositionSize(ticker),
            "isInRecovery" to adaptiveRiskService.isInDrawdownRecovery(),
            "shouldPause" to adaptiveRiskService.shouldPauseTrading(ticker),
        )
    }

    @GetMapping("/analytics/blind-spots")
    suspend fun getBlindSpots(): List<BlindSpotEntity> {
        meterRegistry.counter("api.analytics.blind-spots").increment()
        return blindSpotRepository.findByIsActiveTrue()
    }

    @GetMapping("/analytics/adjustments")
    suspend fun getAdjustments(
        @RequestParam(required = false) ticker: String?,
    ): List<StrategyAdjustment> {
        meterRegistry.counter("api.analytics.adjustments").increment()
        return if (ticker != null) {
            adjustmentRepository.findByTickerOrderByCreatedAtDesc(ticker)
        } else {
            adjustmentRepository.findAll()
        }
    }

    @GetMapping("/analytics/time-pattern/{ticker}")
    suspend fun getTimePattern(
        @PathVariable ticker: String,
        @RequestParam(defaultValue = "30") days: Int,
    ): TimePattern {
        meterRegistry
            .counter(
                "api.analytics.time-pattern",
                Tags
                    .of("ticker", ticker),
            ).increment()
        return tradeAnalysisService.timePatternAnalysis(ticker, days)
    }

    @GetMapping("/analytics/health")
    suspend fun getAnalyticsHealth(): Map<String, Any> {
        val stats = tradeAnalysisService.analyzeLastNDays(7)
        val totalTrades = stats.values.sumOf { it.totalTrades }
        val avgWinRate = if (stats.isNotEmpty()) stats.values.map { it.winRate }.average() else 0.0
        return mapOf(
            "totalTickersAnalyzed" to stats.size,
            "totalTradesLast7Days" to totalTrades,
            "averageWinRate" to String.format("%.2f", avgWinRate * 100) + "%",
            "pausedTickers" to stats.filter { it.value.maxConsecutiveLosses >= 4 }.keys,
            "timestamp" to
                LocalDateTime
                    .now()
                    .toString(),
        )
    }

    // ---------- Текущий пользователь и роли ----------

    @GetMapping("/me")
    fun me(): Map<String, Any> {
        val auth =
            SecurityContextHolder.getContext().authentication
                ?: return emptyMap()
        return mapOf(
            "username" to auth.name,
            "roles" to auth.authorities.map { it.authority },
        )
    }

    // ---------- LLM провайдеры ----------

    @GetMapping("/llm/providers")
    fun getLlmProviders(): Map<String, Any> {
        val settings = settingsService.getSettings()
        return mapOf(
            "providers" to LlmProvider.entries.map { it.name },
            "active" to (settings.llmProvider.ifBlank { "ROUTER_AI" }),
            "model" to settings.llmModel,
            "default" to "ROUTER_AI",
        )
    }

    // ---------- Инвесторы и статистика ----------

    @GetMapping("/investors")
    suspend fun getInvestors() = investorService.listInvestors()

    @PostMapping("/investors")
    suspend fun createInvestor(
        @RequestBody request: Map<String, Any>,
    ): Any {
        val name = request["name"] as? String ?: throw IllegalArgumentException("name is required")
        val email = request["email"] as? String
        val deposit = BigDecimal((request["initialDeposit"] ?: "0").toString())
        return investorService.createInvestor(name, email, deposit)
    }

    @GetMapping("/investors/{investorId}")
    suspend fun getInvestor(
        @PathVariable investorId: UUID,
    ) = investorService.getInvestor(investorId)

    @PostMapping("/investors/{investorId}/deposit")
    suspend fun depositInvestor(
        @PathVariable investorId: UUID,
        @RequestBody request: Map<String, Any>,
    ) = investorService.deposit(investorId, BigDecimal((request["amount"] ?: "0").toString()))

    @PostMapping("/investors/{investorId}/withdraw")
    suspend fun withdrawInvestor(
        @PathVariable investorId: UUID,
        @RequestBody request: Map<String, Any>,
    ) = investorService.withdraw(investorId, BigDecimal((request["amount"] ?: "0").toString()), request["description"] as? String)

    @GetMapping("/investors/{investorId}/transactions")
    suspend fun getInvestorTransactions(
        @PathVariable investorId: UUID,
    ) = investorService.transactions(investorId)

    @GetMapping("/investors/allocations")
    suspend fun getInvestorAllocations() = investorService.allocations()

    // ---------- Прогноз прибыли ----------

    @GetMapping("/forecast")
    suspend fun getForecast(
        @RequestParam(defaultValue = "90") horizonDays: Int,
        @RequestParam(defaultValue = "1000000") capitalBase: BigDecimal,
    ) = profitForecastService.forecast(horizonDays, capitalBase)

    // ---------- Клиринг с инвесторами ----------

    @GetMapping("/clearing/quote")
    suspend fun getClearingQuote(
        @RequestParam investorId: UUID,
        @RequestParam(required = false) date: LocalDateTime?,
    ) = clearingService.calculateWithdrawal(investorId, date ?: LocalDateTime.now())

    @PostMapping("/clearing/settle")
    suspend fun settleClearing(
        @RequestParam investorId: UUID,
        @RequestParam(required = false) date: LocalDateTime?,
    ) = clearingService.settleWithdrawal(investorId, date ?: LocalDateTime.now())

    @GetMapping("/clearing/pool")
    suspend fun getClearingPool() = clearingService.poolStats()

    // ---------- Управление торговлей ----------

    @GetMapping("/trading/status")
    suspend fun getTradingStatus(): Map<String, Any> {
        val settings = settingsService.getSettings()
        val status = tradingGate.getStatus()
        return buildMap {
            put("tradingEnabled", status.enabled)
            status.reason?.let { put("reason", it.name) }
            status.source?.let { put("source", it.name) }
            status.detail?.let { put("detail", it) }
            status.blockedAt?.let { put("blockedAt", it.toString()) }
            put(
                "blocks",
                status.blocks.map { b ->
                    buildMap {
                        put("reason", b.reason.name)
                        put("source", b.source.name)
                        put("detail", b.detail)
                        put("timestamp", b.timestamp.toString())
                        b.ticker?.let { put("ticker", it) }
                    }
                },
            )
            put("tradingMode", settings.tradingMode)
            put("forceCloseEnabled", settings.forceCloseEnabled)
            put("forceCloseTime", settings.forceCloseTime)
            put("openPositions", positionRepository.findOpenCount())
        }
    }

    @PostMapping("/trading/enable")
    fun enableTrading(): Map<String, Any> {
        tradingControlService.setTradingEnabled(true)
        return mapOf("tradingEnabled" to true)
    }

    @PostMapping("/trading/disable")
    fun disableTrading(): Map<String, Any> {
        tradingControlService.setTradingEnabled(false)
        return mapOf("tradingEnabled" to false)
    }

    @PostMapping("/trading/force-close")
    suspend fun forceClose(
        @RequestParam(defaultValue = "FORCE_CLOSE") reason: String,
    ): Map<String, Any> = mapOf("closed" to tradingControlService.forceCloseNow(reason))

    @PostMapping("/trading/force-close-at")
    fun forceCloseAt(
        @RequestParam time: String,
    ): Map<String, Any> {
        LocalTime.parse(time) // валидация формата HH:mm
        val current = settingsService.getSettings()
        settingsService.updateSettings(current.copy(forceCloseEnabled = true, forceCloseTime = time))
        return mapOf("forceCloseEnabled" to true, "forceCloseTime" to time)
    }

    @PostMapping("/trading/force-close-cancel")
    fun cancelForceClose(): Map<String, Any> {
        val current = settingsService.getSettings()
        settingsService.updateSettings(current.copy(forceCloseEnabled = false, forceCloseTime = ""))
        return mapOf("forceCloseEnabled" to false)
    }
}
