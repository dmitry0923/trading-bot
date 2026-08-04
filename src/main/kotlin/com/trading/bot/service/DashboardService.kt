package com.trading.bot.service

import com.trading.bot.config.TradingConfig
import com.trading.bot.model.PositionStatus
import com.trading.bot.repository.PositionRepository
import com.trading.bot.repository.StrategyRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Собирает агрегированный дашборд для React Dashboard.
 *
 * Используется как REST-эндпоинтом `/api/v1/dashboard`, так и SSE-рассылкой
 * [DashboardSseService] (`/api/v1/dashboard/stream`) для real-time обновлений.
 *
 * @property tradingConfig конфигурация торгового ядра
 * @property positionRepository репозиторий позиций
 * @property strategyRepository репозиторий стратегий
 * @property tradeAnalysisService аналитика сделок
 * @property adaptiveRiskService адаптивный риск-менеджмент
 * @property riskManagementService риск-менеджмент (дневной P&L)
 */
@Service
class DashboardService(
    private val tradingConfig: TradingConfig,
    private val positionRepository: PositionRepository,
    private val strategyRepository: StrategyRepository,
    private val tradeAnalysisService: TradeAnalysisService,
    private val riskManagementService: RiskManagementService,
) {

    /**
     * Строит снимок дашборда: открытые позиции с live P&L, дневная статистика,
     * paused-тикеры и режим торговли.
     *
     * @return сериализуемая карта данных дашборда
     */
    suspend fun build(): Map<String, Any> {
        val openPositions = positionRepository.findByStatus(PositionStatus.OPEN)
        val openPnl = openPositions.sumOf { it.pnl ?: BigDecimal.ZERO }
        val todayStart = LocalDate.now().atStartOfDay()
        val closedToday = positionRepository.findClosedSince(todayStart)
        val realizedPnlToday = closedToday.sumOf { it.pnl ?: BigDecimal.ZERO }
        val strategiesToday = strategyRepository.findTop50ByOrderByCreatedAtDesc()
            .count { it.createdAt.isAfter(todayStart) }
        val stats = tradeAnalysisService.analyzeLastNDays(7)
        val pausedTickers = stats.filter { (_, value) ->
            value.maxConsecutiveLosses >= 4 ||
                (value.profitFactor in 0.0..0.5 && value.totalTrades >= 5)
        }.keys

        return mapOf(
            "tradingMode" to tradingConfig.mode,
            "tickers" to tradingConfig.tickers,
            "dailyPnl" to riskManagementService.getDailyPnL(),
            "openPnl" to openPnl,
            "realizedPnlToday" to realizedPnlToday,
            "closedTodayCount" to closedToday.size,
            "strategiesToday" to strategiesToday,
            "openPositionsCount" to openPositions.size,
            "openPositions" to openPositions,
            "pausedTickers" to pausedTickers,
            "timestamp" to LocalDateTime.now().toString()
        )
    }
}
