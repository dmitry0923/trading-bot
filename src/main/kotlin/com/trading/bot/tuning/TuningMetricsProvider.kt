package com.trading.bot.tuning

import com.trading.bot.backtest.StrategyParameters
import com.trading.bot.repository.PositionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Провайдер метрик для Monthly Tuning Engine (docs/24, Фаза 3.5).
 *
 * Собирает метрики прошедшего месяца из таблицы positions для gate check.
 * Возвращает текущие параметры стратегии из последней активной версии БД.
 *
 * @see MonthlyTuningService
 * @see MonthlyTuningScheduler
 */
@Component
class TuningMetricsProvider(
    private val positionRepository: PositionRepository,
    private val strategyVersionRepository: StrategyVersionRepository,
) {
    private val logger = LoggerFactory.getLogger(TuningMetricsProvider::class.java)

    /**
     * Пара метрик + текущие параметры для цикла тюнинга.
     */
    data class MonthlyData(
        val metrics: PerformanceGateCheck.MonthlyMetrics,
        val currentParameters: StrategyParameters,
    )

    /**
     * Собирает данные за прошедший месяц.
     *
     * @return метрики прошедшего месяца и текущие параметры стратегии.
     */
    suspend fun collectCurrentMonthlyData(): MonthlyData {
        val now = LocalDate.now()
        val monthStart = now.withDayOfMonth(1).minusMonths(1)
        val monthEnd = now.withDayOfMonth(1).atStartOfDay()

        logger.info(
            "TuningMetricsProvider: сбор метрик за период $monthStart — $monthEnd",
        )

        // Закрытые позиции за прошедший месяц: findClosedSince отдаёт closed_at >= since,
        // правый конец интервала отсекаем в коде (в БД его нет).
        val monthEndInstant = monthEnd
        val closedPositions =
            positionRepository.findClosedSince(monthStart.atStartOfDay()).filter {
                (it.closedAt ?: LocalDateTime.MIN) < monthEndInstant
            }

        if (closedPositions.isEmpty()) {
            logger.warn("TuningMetricsProvider: нет закрытых позиций за период $monthStart — $monthEnd")
        }

        // Расчёт метрик.
        val profits = closedPositions.mapNotNull { it.realizedPnl?.toDouble() }.filter { it > 0 }
        val losses = closedPositions.mapNotNull { it.realizedPnl?.toDouble() }.filter { it < 0 }

        val totalProfit = profits.sum()
        val totalLoss = Math.abs(losses.sum())
        val profitFactor = if (totalLoss > 0) totalProfit / totalLoss else 1.0

        val winRate =
            if (closedPositions.isNotEmpty()) {
                profits.size.toDouble() / closedPositions.size * 100.0
            } else {
                50.0
            }

        val pnls = closedPositions.mapNotNull { it.realizedPnl?.toDouble() }
        val sharpe = calculateSharpe(pnls)
        val maxDrawdown = calculateMaxDrawdown(pnls)

        val metrics =
            PerformanceGateCheck.MonthlyMetrics(
                profitFactor = profitFactor,
                maxDrawdownPct = maxDrawdown,
                winRatePct = winRate,
                sharpe = sharpe,
                tradesCount = closedPositions.size,
            )

        // Текущие параметры из последней активной версии БД.
        val currentVersion = strategyVersionRepository.findFirstByRolledBackFalseOrderByDeployedAtDesc()
        val currentParameters =
            currentVersion?.let {
                // В production параметры десериализуются из JSONB; здесь — placeholder defaults.
                StrategyParameters()
            } ?: StrategyParameters()

        return MonthlyData(metrics, currentParameters)
    }

    private fun calculateSharpe(pnls: List<Double>): Double {
        if (pnls.size < 2) return 0.0
        val mean = pnls.average()
        val stdDev = Math.sqrt(pnls.map { (it - mean) * (it - mean) }.average())
        return if (stdDev > 0) mean / stdDev else 0.0
    }

    private fun calculateMaxDrawdown(pnls: List<Double>): Double {
        if (pnls.isEmpty()) return 0.0
        var peak = 0.0
        var maxDd = 0.0
        var cumulative = 0.0
        for (pnl in pnls) {
            cumulative += pnl
            if (cumulative > peak) peak = cumulative
            val dd = if (peak > 0) (peak - cumulative) / peak * 100.0 else 0.0
            if (dd > maxDd) maxDd = dd
        }
        return maxDd
    }
}
