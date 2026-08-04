package com.trading.bot.controller

import com.trading.bot.backtest.BacktestEngine
import com.trading.bot.backtest.HistoricalDataLoader
import com.trading.bot.config.TradingConfig
import com.trading.bot.model.BlindSpotEntity
import com.trading.bot.model.BotSettings
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.Strategy
import com.trading.bot.model.StrategyAdjustment
import com.trading.bot.model.TimePattern
import com.trading.bot.model.TradeEvent
import com.trading.bot.model.TradeStats
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.BlindSpotRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.repository.StrategyAdjustmentRepository
import com.trading.bot.repository.StrategyRepository
import com.trading.bot.repository.TradeEventRepository
import com.trading.bot.service.AdaptiveRiskService
import com.trading.bot.service.DashboardService
import com.trading.bot.service.DashboardSseService
import com.trading.bot.service.RedisCacheService
import com.trading.bot.service.RiskManagementService
import com.trading.bot.service.SettingsService
import com.trading.bot.service.StrategyService
import com.trading.bot.service.TradeAnalysisService
import com.trading.bot.service.TradingBotService
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

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
    private val tradingConfig: TradingConfig,
    private val strategyRepository: StrategyRepository,
    private val positionRepository: PositionRepository,
    private val agentLogRepository: AgentLogRepository,
    private val redisCacheService: RedisCacheService,
    private val riskManagementService: RiskManagementService,
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
    private val meterRegistry: MeterRegistry,
) {
    @GetMapping("/settings")
    fun getSettings(): BotSettings = settingsService.getSettings()

    @PostMapping("/settings")
    fun updateSettings(
        @RequestBody settings: BotSettings,
    ): BotSettings =
        try {
            settingsService.updateSettings(settings)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
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
    ): Strategy? {
        val normalizedTicker = validateTicker(ticker)
        return redisCacheService.getStrategy(normalizedTicker)
            ?: strategyRepository.findTopByTickerOrderByCreatedAtDesc(normalizedTicker)
    }

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
     * Append-only audit trail позиции (Event Sourcing): события
     * POSITION_OPENED / POSITION_UPDATED / POSITION_CLOSED в порядке sequence.
     */
    @GetMapping("/positions/{positionId}/events")
    suspend fun getPositionEvents(
        @PathVariable positionId: Long,
    ): List<TradeEvent> {
        if (positionId <= 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "positionId must be positive")
        val aggregateId = java.util.UUID.nameUUIDFromBytes("position:$positionId".toByteArray())
        return tradeEventRepository.findByAggregateId(aggregateId)
    }

    @PostMapping("/strategy/trigger")
    fun triggerStrategy(): Map<String, String> {
        meterRegistry.counter("api.trigger.strategy").increment()
        strategyService.runStrategyCycle()
        return mapOf("status" to "accepted")
    }

    @PostMapping("/bot/trigger")
    fun triggerBot(): Map<String, String> {
        meterRegistry.counter("api.trigger.bot").increment()
        tradingBotService.runBotCycle()
        return mapOf("status" to "accepted")
    }

    @GetMapping("/backtest/{ticker}")
    suspend fun backtest(
        @PathVariable ticker: String,
        @RequestParam(defaultValue = "365") days: Int,
        @RequestParam(defaultValue = "false") loadHistory: Boolean,
    ): Map<String, Any> {
        val normalizedTicker = validateTicker(ticker)
        val normalizedDays = validateDays(days, max = 1_095)
        meterRegistry
            .counter(
                "api.backtest",
                io.micrometer.core.instrument.Tags
                    .of("ticker", normalizedTicker),
            ).increment()
        if (loadHistory) {
            historicalDataLoader.loadAndSave(normalizedTicker, normalizedDays)
        }
        val result = backtestEngine.run(normalizedTicker, normalizedDays)
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
                java.time.LocalDateTime
                    .now()
                    .toString(),
        )
    }

    @GetMapping("/analytics/trade-stats")
    suspend fun getTradeStats(
        @RequestParam(defaultValue = "14") days: Int,
    ): Map<String, TradeStats> {
        meterRegistry.counter("api.analytics.trade-stats").increment()
        return tradeAnalysisService.analyzeLastNDays(validateDays(days))
    }

    @GetMapping("/analytics/adaptive-params/{ticker}")
    suspend fun getAdaptiveParams(
        @PathVariable ticker: String,
    ): Map<String, Any> {
        val normalizedTicker = validateTicker(ticker)
        meterRegistry
            .counter(
                "api.analytics.adaptive-params",
                io.micrometer.core.instrument.Tags
                    .of("ticker", normalizedTicker),
            ).increment()
        return mapOf(
            "ticker" to normalizedTicker,
            "confidenceThreshold" to adaptiveRiskService.getAdaptiveConfidenceThreshold(normalizedTicker),
            "maxPositionRub" to adaptiveRiskService.calculateOptimalPositionSize(normalizedTicker),
            "isInRecovery" to adaptiveRiskService.isInDrawdownRecovery(),
            "shouldPause" to adaptiveRiskService.shouldPauseTrading(normalizedTicker),
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
            adjustmentRepository.findByTickerOrderByCreatedAtDesc(validateTicker(ticker))
        } else {
            adjustmentRepository.findAll()
        }
    }

    @GetMapping("/analytics/time-pattern/{ticker}")
    suspend fun getTimePattern(
        @PathVariable ticker: String,
        @RequestParam(defaultValue = "30") days: Int,
    ): TimePattern {
        val normalizedTicker = validateTicker(ticker)
        meterRegistry
            .counter(
                "api.analytics.time-pattern",
                io.micrometer.core.instrument.Tags
                    .of("ticker", normalizedTicker),
            ).increment()
        return tradeAnalysisService.timePatternAnalysis(normalizedTicker, validateDays(days))
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
                java.time.LocalDateTime
                    .now()
                    .toString(),
        )
    }

    private fun validateDays(
        days: Int,
        max: Int = 365,
    ): Int {
        if (days !in 1..max) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be between 1 and $max")
        }
        return days
    }

    private fun validateTicker(ticker: String): String {
        val value = ticker.trim()
        if (!value.matches(Regex("[A-Za-z0-9._-]{1,20}"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ticker")
        }
        return tradingConfig.tickers.firstOrNull { it.equals(value, ignoreCase = true) }
            ?: value.uppercase()
    }
}
