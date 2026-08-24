package com.trading.bot.backtest

import com.trading.bot.model.PositionDirection
import com.trading.bot.model.dto.TradeStats
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/**
 * In-memory аналог [com.trading.bot.service.TradeAnalysisService] для бэктеста.
 *
 * Накапливает результаты закрытых сделок по мере прохождения симуляции
 * и предоставляет ту же агрегацию (winRate, avgWin, avgLoss, PF, consecutive losses),
 * что и live TradeAnalysisService, но без БД и без blind spots.
 *
 * Используется [BacktestRiskSimulator] для расчёта Kelly sizing в режиме
 * `bt.live-risk-gates=true`.
 */
class BacktestTradeAnalysisService {
    private val closedTrades = mutableListOf<ClosedTradeRecord>()

    /**
     * Результат одной закрытой сделки в бэктесте.
     */
    data class ClosedTradeRecord(
        val ticker: String,
        val pnl: BigDecimal,
        val entryPrice: BigDecimal,
        val exitPrice: BigDecimal,
        val direction: PositionDirection,
        val entryTime: LocalDateTime,
        val exitTime: LocalDateTime,
        val closeReason: String,
        val commission: BigDecimal,
    )

    /**
     * Записать результат закрытия позиции.
     */
    fun recordClose(record: ClosedTradeRecord) {
        closedTrades.add(record)
    }

    /**
     * Агрегация статистики по тикерам — тот же контракт, что
     * [com.trading.bot.service.TradeAnalysisService.analyzeLastNDays].
     *
     * @param days окно анализа (дни); null = все записи
     * @param currentTime текущее время симуляции (для фильтрации по days)
     * @return карта тикер -> TradeStats
     */
    fun analyze(
        days: Int? = null,
        currentTime: LocalDateTime? = null,
    ): Map<String, TradeStats> {
        val filtered =
            if (days != null && currentTime != null) {
                val since = currentTime.minusDays(days.toLong())
                closedTrades.filter { it.exitTime >= since }
            } else {
                closedTrades
            }

        if (filtered.isEmpty()) return emptyMap()

        return filtered.groupBy { it.ticker }.mapValues { (ticker, trades) ->
            buildStats(ticker, trades)
        }
    }

    /**
     * Текущий дневной P&L (сумма pnl сделок за указанный день).
     */
    fun dailyPnl(
        day: LocalDateTime,
        trades: List<ClosedTradeRecord>? = null,
    ): BigDecimal {
        val source = trades ?: closedTrades
        return source
            .filter { it.exitTime.toLocalDate() == day.toLocalDate() }
            .sumOf { it.pnl }
    }

    /**
     * Скользящий P&L за N дней от текущего момента.
     */
    fun rollingPnl(
        currentTime: LocalDateTime,
        days: Int,
    ): BigDecimal {
        val since = currentTime.minusDays(days.toLong())
        return closedTrades
            .filter { it.exitTime >= since }
            .sumOf { it.pnl }
    }

    /**
     * Максимальная серия убытков подряд (chronological order).
     */
    fun maxConsecutiveLosses(): Int {
        var maxStreak = 0
        var currentStreak = 0
        for (trade in closedTrades.sortedBy { it.exitTime }) {
            if (trade.pnl < BigDecimal.ZERO) {
                currentStreak++
                maxStreak = maxOf(maxStreak, currentStreak)
            } else {
                currentStreak = 0
            }
        }
        return maxStreak
    }

    /**
     * Текущая серия убытков подряд (tail of closed trades).
     */
    fun currentConsecutiveLosses(): Int {
        var streak = 0
        for (trade in closedTrades.sortedByDescending { it.exitTime }) {
            if (trade.pnl < BigDecimal.ZERO) {
                streak++
            } else {
                break
            }
        }
        return streak
    }

    /** Общее количество закрытых сделок. */
    fun totalTrades(): Int = closedTrades.size

    /** Все закрытые сделки (для итерации в BacktestRiskSimulator). */
    fun allTrades(): List<ClosedTradeRecord> = closedTrades.toList()

    private fun buildStats(
        ticker: String,
        trades: List<ClosedTradeRecord>,
    ): TradeStats {
        val total = trades.size
        val wins = trades.count { it.pnl > BigDecimal.ZERO }
        val losses = total - wins
        val winRate = if (total > 0) wins.toDouble() / total else 0.0

        val grossProfit =
            trades
                .filter { it.pnl > BigDecimal.ZERO }
                .sumOf { it.pnl }
        val grossLoss =
            trades
                .filter { it.pnl < BigDecimal.ZERO }
                .sumOf { kotlin.math.abs(it.pnl.toDouble()) }

        val avgWin = if (wins > 0) grossProfit.divide(BigDecimal(wins), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val avgLossVal = if (losses > 0) BigDecimal(grossLoss / losses).setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO

        val slHits = trades.count { it.closeReason == "STOP_LOSS" }
        val tpHits = trades.count { it.closeReason == "TAKE_PROFIT" }
        val strategyCloses = trades.count { it.closeReason == "STRATEGY_CLOSE" || it.closeReason == "REVERSAL" }
        val slHitRate = if (total > 0) slHits.toDouble() / total else 0.0
        val tpHitRate = if (total > 0) tpHits.toDouble() / total else 0.0
        val strategyCloseRate = if (total > 0) strategyCloses.toDouble() / total else 0.0

        val maxConsecutiveLosses = calculateMaxConsecutiveLosses(trades)

        val avgHoldTime =
            if (trades.isNotEmpty()) {
                trades
                    .map {
                        java.time.temporal.ChronoUnit.MINUTES
                            .between(it.entryTime, it.exitTime)
                    }.average()
                    .toLong()
            } else {
                0L
            }

        return TradeStats(
            ticker = ticker,
            totalTrades = total,
            winningTrades = wins,
            losingTrades = losses,
            winRate = winRate,
            avgWin = avgWin,
            avgLoss = avgLossVal,
            profitFactor =
                when {
                    grossLoss > 0 -> grossProfit.toDouble() / grossLoss
                    grossProfit > BigDecimal.ZERO -> Double.POSITIVE_INFINITY
                    else -> 0.0
                },
            maxConsecutiveLosses = maxConsecutiveLosses,
            avgHoldTimeMinutes = avgHoldTime,
            slHitRate = slHitRate,
            tpHitRate = tpHitRate,
            strategyCloseRate = strategyCloseRate,
            bestEntryHour = null,
            worstEntryHour = null,
            blindSpots = emptyList(),
        )
    }

    private fun calculateMaxConsecutiveLosses(trades: List<ClosedTradeRecord>): Int {
        var maxStreak = 0
        var currentStreak = 0
        for (trade in trades.sortedBy { it.exitTime }) {
            if (trade.pnl < BigDecimal.ZERO) {
                currentStreak++
                maxStreak = maxOf(maxStreak, currentStreak)
            } else {
                currentStreak = 0
            }
        }
        return maxStreak
    }
}
