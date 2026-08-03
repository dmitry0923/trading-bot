package com.trading.bot.controller

import com.trading.bot.model.*
import com.trading.bot.repository.*
import com.trading.bot.service.*
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = ["*"])
class ApiController(
    private val strategyRepository: StrategyRepository,
    private val positionRepository: PositionRepository,
    private val agentLogRepository: AgentLogRepository,
    private val redisCacheService: RedisCacheService,
    private val riskManagementService: RiskManagementService,
    private val strategyService: StrategyService,
    private val tradingBotService: TradingBotService,
    private val settingsService: SettingsService
) {

    @GetMapping("/settings")
    fun getSettings(): BotSettings = settingsService.getSettings()

    @PostMapping("/settings")
    fun updateSettings(@RequestBody settings: BotSettings): BotSettings {
        settingsService.updateSettings(settings)
        return settings
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
    fun getLogs() = agentLogRepository.findTop100ByOrderByCreatedAtDesc()

    @GetMapping("/risk/daily-pnl")
    fun getDailyPnl() = mapOf("dailyPnl" to riskManagementService.getDailyPnL())

    @PostMapping("/strategy/trigger")
    fun triggerStrategy() { strategyService.runStrategyCycle() }

    @PostMapping("/bot/trigger")
    fun triggerBot() { tradingBotService.runBotCycle() }
}
