package com.trading.bot.service

import com.trading.bot.config.TradingConfig
import com.trading.bot.model.PositionStatus
import com.trading.bot.repository.PositionRepository
import com.trading.bot.repository.StrategyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Multi-account (roadmap v2.2): `accountId` фильтрует снимок по аккаунту —
 * открытые/закрытые сегодня позиции и дневной P&L. `null` (без фильтра) =
 * агрегированный вид по всем позициям (legacy single-account режим).
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
    private val adaptiveRiskService: AdaptiveRiskService,
    private val riskManagementService: RiskManagementService,
) {
    /**
     * Строит снимок дашборда: открытые позиции с live P&L, дневная статистика,
     * paused-тикеры и режим торговли.
     *
     * @param accountId фильтр по аккаунту; null = агрегированный вид по всем позициям
     * @return сериализуемая карта данных дашборда
     */
    suspend fun build(accountId: Long? = null): Map<String, Any?> {
        val todayStart = LocalDate.now().atStartOfDay()
        val (openPositions, closedToday, dailyPnl) =
            if (accountId == null) {
                Triple(
                    positionRepository.findByStatus(PositionStatus.OPEN),
                    positionRepository.findClosedSince(todayStart),
                    riskManagementService.getDailyPnL(),
                )
            } else {
                Triple(
                    positionRepository.findOpenByAccount(accountId),
                    positionRepository.findClosedByAccountSince(accountId, todayStart),
                    // Per-account daily P&L читает daily_risk_snapshot блокирующим
                    // .block() (синхронный риск state machine) — offload на IO,
                    // suspend-вызов выполняется на event loop.
                    withContext(Dispatchers.IO) { riskManagementService.getDailyPnL(accountId) },
                )
            }
        val openPnl = openPositions.sumOf { it.pnl?.toDouble() ?: 0.0 }
        val realizedPnlToday = closedToday.sumOf { it.pnl?.toDouble() ?: 0.0 }
        val strategiesToday =
            strategyRepository
                .findTop50ByOrderByCreatedAtDesc()
                .count { it.createdAt.isAfter(todayStart) }
        val stats = tradeAnalysisService.analyzeLastNDays(7)
        val pausedTickers = stats.filter { it.value.maxConsecutiveLosses >= 4 }.keys
        val adaptivePaused = tradingConfig.tickers.filter { adaptiveRiskService.shouldPauseTrading(it) }

        return mapOf(
            "accountId" to accountId,
            "tradingMode" to tradingConfig.mode,
            "tickers" to tradingConfig.tickers,
            "dailyPnl" to dailyPnl,
            "openPnl" to BigDecimal(openPnl),
            "realizedPnlToday" to BigDecimal(realizedPnlToday),
            "closedTodayCount" to closedToday.size,
            "strategiesToday" to strategiesToday,
            "openPositionsCount" to openPositions.size,
            "openPositions" to openPositions,
            "pausedTickers" to (pausedTickers + adaptivePaused).toSet(),
            "timestamp" to LocalDateTime.now().toString(),
        )
    }
}
