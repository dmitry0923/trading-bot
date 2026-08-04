package com.trading.bot.controller

import com.trading.bot.backtest.BacktestEngine
import com.trading.bot.backtest.HistoricalDataLoader
import com.trading.bot.config.TradingConfig
import com.trading.bot.model.*
import com.trading.bot.repository.*
import com.trading.bot.service.*
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.math.BigDecimal

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
@CrossOrigin(origins = ["*"])
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
    private val backtestEngine: BacktestEngine,
    private val historicalDataLoader: HistoricalDataLoader,
    private val dashboardService: DashboardService,
    private val dashboardSseService: DashboardSseService,
    private val meterRegistry: MeterRegistry
) {

    @GetMapping("/settings")
    fun getSettings(): BotSettings = settingsService.getSettings()

    @PostMapping("/settings")
    fun updateSettings(@RequestBody settings: BotSettings): BotSettings {
        settingsService.updateSettings(settings)
        return settings
    }

    /**
     * Агрегированная панель для React Dashboard: открытые позиции с live P&L,
     * дневная статистика, paused-тикеры, режим торговли.
     */
    @GetMapping("/dashboard")
    fun getDashboard(): Map<String, Any> {
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
    fun getStrategies() = strategyRepository.findTop50ByOrderByCreatedAtDesc()

    @GetMapping("/strategies/{ticker}")
    fun getStrategy(@PathVariable ticker: String) =
        redisCacheService.getStrategy(ticker)
            ?: strategyRepository.findTopByTickerOrderByCreatedAtDesc(ticker)

    @GetMapping("/positions")
    fun getOpenPositions() = positionRepository.findByStatus(PositionStatus.OPEN)

    @GetMapping("/positions/all")
    fun getAllPositions() = positionRepository.findAll()

    @GetMapping("/logs")
    fun getLogs(
        @RequestParam(required = false) ticker: String?,
        @RequestParam(required = false) agent: String?,
        @RequestParam(defaultValue = "100") limit: Int
    ) = agentLogRepository.findFiltered(ticker, agent, limit)

    @GetMapping("/risk/daily-pnl")
    fun getDailyPnl() = mapOf("dailyPnl" to riskManagementService.getDailyPnL())

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
    fun backtest(
        @PathVariable ticker: String,
        @RequestParam(defaultValue = "365") days: Int,
        @RequestParam(defaultValue = "false") loadHistory: Boolean
    ): Map<String, Any> {
        meterRegistry.counter("api.backtest", io.micrometer.core.instrument.Tags.of("ticker", ticker)).increment()
        if (loadHistory) {
            runBlocking { historicalDataLoader.loadAndSave(ticker, days) }
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
            "timestamp" to java.time.LocalDateTime.now().toString()
        )
    }

    @GetMapping("/analytics/trade-stats")
    fun getTradeStats(@RequestParam(defaultValue = "14") days: Int): Map<String, TradeStats> {
        meterRegistry.counter("api.analytics.trade-stats").increment()
        return tradeAnalysisService.analyzeLastNDays(days)
    }

    @GetMapping("/analytics/adaptive-params/{ticker}")
    fun getAdaptiveParams(@PathVariable ticker: String): Map<String, Any> {
        meterRegistry.counter("api.analytics.adaptive-params", io.micrometer.core.instrument.Tags.of("ticker", ticker)).increment()
        return mapOf(
            "ticker" to ticker,
            "confidenceThreshold" to adaptiveRiskService.getAdaptiveConfidenceThreshold(ticker),
            "maxPositionRub" to adaptiveRiskService.calculateOptimalPositionSize(ticker),
            "isInRecovery" to adaptiveRiskService.isInDrawdownRecovery(),
            "shouldPause" to adaptiveRiskService.shouldPauseTrading(ticker)
        )
    }

    @GetMapping("/analytics/blind-spots")
    fun getBlindSpots(): List<BlindSpotEntity> {
        meterRegistry.counter("api.analytics.blind-spots").increment()
        return blindSpotRepository.findByIsActiveTrue()
    }

    @GetMapping("/analytics/adjustments")
    fun getAdjustments(@RequestParam(required = false) ticker: String?): List<StrategyAdjustment> {
        meterRegistry.counter("api.analytics.adjustments").increment()
        return if (ticker != null) adjustmentRepository.findByTickerOrderByCreatedAtDesc(ticker)
        else adjustmentRepository.findAll()
    }

    @GetMapping("/analytics/time-pattern/{ticker}")
    fun getTimePattern(
        @PathVariable ticker: String,
        @RequestParam(defaultValue = "30") days: Int
    ): TimePattern {
        meterRegistry.counter("api.analytics.time-pattern", io.micrometer.core.instrument.Tags.of("ticker", ticker)).increment()
        return tradeAnalysisService.timePatternAnalysis(ticker, days)
    }

    @GetMapping("/analytics/health")
    fun getAnalyticsHealth(): Map<String, Any> {
        val stats = tradeAnalysisService.analyzeLastNDays(7)
        val totalTrades = stats.values.sumOf { it.totalTrades }
        val avgWinRate = if (stats.isNotEmpty()) stats.values.map { it.winRate }.average() else 0.0
        return mapOf(
            "totalTickersAnalyzed" to stats.size,
            "totalTradesLast7Days" to totalTrades,
            "averageWinRate" to String.format("%.2f", avgWinRate * 100) + "%",
            "pausedTickers" to stats.filter { it.value.maxConsecutiveLosses >= 4 }.keys,
            "timestamp" to java.time.LocalDateTime.now().toString()
        )
    }
}
