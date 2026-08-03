package com.trading.bot.service

import com.trading.bot.model.*
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.BlindSpotRepository
import com.trading.bot.repository.PositionRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Service
class TradeAnalysisService(
    private val positionRepo: PositionRepository,
    private val agentLogRepo: AgentLogRepository,
    private val blindSpotRepo: BlindSpotRepository
) {
    private val logger = KotlinLogging.logger {}

    @Transactional(readOnly = true)
    fun analyzeLastNDays(days: Int = 14): Map<String, TradeStats> {
        val since = LocalDateTime.now().minusDays(days.toLong())
        val closed = positionRepo.findClosedSince(since)

        if (closed.isEmpty()) {
            logger.info { "No closed positions in last $days days" }
            return emptyMap()
        }

        return closed.groupBy { it.ticker }.mapValues { (ticker, trades) ->
            buildStats(ticker, trades, days)
        }
    }

    private fun buildStats(ticker: String, trades: List<Position>, days: Int): TradeStats {
        val total = trades.size
        val wins = trades.count { (it.pnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }
        val losses = total - wins
        val winRate = if (total > 0) wins.toDouble() / total else 0.0

        val grossProfit = trades.filter { (it.pnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }
            .sumOf { it.pnl ?: BigDecimal.ZERO }
        val grossLoss = trades.filter { (it.pnl ?: BigDecimal.ZERO) < BigDecimal.ZERO }
            .sumOf { kotlin.math.abs((it.pnl ?: BigDecimal.ZERO).toDouble()) }
        val profitFactor = if (grossLoss > 0) grossProfit.toDouble() / grossLoss else 0.0

        val avgWin = if (wins > 0) grossProfit.divide(BigDecimal(wins), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val avgLossVal = if (losses > 0) BigDecimal(grossLoss / losses).setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO

        val slHits = trades.count { it.closeReason == "STOP_LOSS" }
        val tpHits = trades.count { it.closeReason == "TAKE_PROFIT" }
        val strategyCloses = trades.count { it.closeReason == "STRATEGY_CLOSE" }
        val slHitRate = if (total > 0) slHits.toDouble() / total else 0.0
        val tpHitRate = if (total > 0) tpHits.toDouble() / total else 0.0
        val strategyCloseRate = if (total > 0) strategyCloses.toDouble() / total else 0.0

        val maxConsecutiveLosses = calculateMaxConsecutiveLosses(trades)

        val avgHoldTime = if (trades.isNotEmpty()) {
            trades.mapNotNull { pos ->
                if (pos.closedAt != null && pos.openedAt != null)
                    ChronoUnit.MINUTES.between(pos.openedAt, pos.closedAt)
                else null
            }.average().toLong()
        } else 0L

        val hourly = trades.groupBy { it.openedAt.hour }
            .mapValues { (_, list) ->
                val w = list.count { (it.pnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }
                if (list.isNotEmpty()) w.toDouble() / list.size else 0.0
            }
        val bestEntryHour = hourly.filter { it.value > 0 }.maxByOrNull { it.value }?.key
        val worstEntryHour = hourly.filter { it.value < 1.0 }.minByOrNull { it.value }?.key

        val blindSpots = detectAndPersistBlindSpots(ticker, trades)

        return TradeStats(
            ticker = ticker,
            totalTrades = total,
            winningTrades = wins,
            losingTrades = losses,
            winRate = winRate,
            avgWin = avgWin,
            avgLoss = avgLossVal,
            profitFactor = profitFactor,
            maxConsecutiveLosses = maxConsecutiveLosses,
            avgHoldTimeMinutes = avgHoldTime,
            slHitRate = slHitRate,
            tpHitRate = tpHitRate,
            strategyCloseRate = strategyCloseRate,
            bestEntryHour = bestEntryHour,
            worstEntryHour = worstEntryHour,
            blindSpots = blindSpots
        )
    }

    private fun calculateMaxConsecutiveLosses(trades: List<Position>): Int {
        var maxStreak = 0
        var currentStreak = 0
        for (trade in trades.sortedBy { it.closedAt }) {
            if ((trade.pnl ?: BigDecimal.ZERO) < BigDecimal.ZERO) {
                currentStreak++
                maxStreak = maxOf(maxStreak, currentStreak)
            } else {
                currentStreak = 0
            }
        }
        return maxStreak
    }

    @Transactional
    private fun detectAndPersistBlindSpots(ticker: String, trades: List<Position>): List<BlindSpot> {
        val losing = trades.filter { (it.pnl ?: BigDecimal.ZERO) < BigDecimal.ZERO }
        if (losing.size < 3) return emptyList()

        val spots = mutableListOf<BlindSpot>()

        val slLosses = losing.count { it.closeReason == "STOP_LOSS" }
        if (slLosses.toDouble() / losing.size > 0.6) {
            val spot = BlindSpot(
                conditionPattern = "Stop-Loss hit rate > 60% for $ticker",
                lossRate = slLosses.toDouble() / losing.size,
                occurrenceCount = slLosses,
                recommendation = "Увеличить ATR-множитель стоп-лосса или пересмотреть точки входа"
            )
            spots.add(spot)
            persistBlindSpot(ticker, spot)
        }

        val hourlyLosses = losing.groupBy { it.openedAt.hour }
        hourlyLosses.forEach { (hour, list) ->
            if (list.size >= 3) {
                val spot = BlindSpot(
                    conditionPattern = "Entry at hour $hour for $ticker",
                    lossRate = list.size.toDouble() / losing.size,
                    occurrenceCount = list.size,
                    recommendation = "Избегать входов в $hour:00, возможно низкая ликвидность"
                )
                spots.add(spot)
                persistBlindSpot(ticker, spot)
            }
        }

        return spots
    }

    private fun persistBlindSpot(ticker: String, spot: BlindSpot) {
        val existing = blindSpotRepo.findByTickerAndIsActiveTrue(ticker)
            .find { it.conditionPattern == spot.conditionPattern }
        if (existing != null) {
            existing.occurrenceCount += spot.occurrenceCount
            blindSpotRepo.save(existing)
        } else {
            blindSpotRepo.save(
                BlindSpotEntity(
                    ticker = ticker,
                    conditionPattern = spot.conditionPattern,
                    lossRate = spot.lossRate,
                    occurrenceCount = spot.occurrenceCount,
                    recommendation = spot.recommendation
                )
            )
        }
    }

    fun timePatternAnalysis(ticker: String, days: Int = 30): TimePattern {
        val since = LocalDateTime.now().minusDays(days.toLong())
        val trades = positionRepo.findClosedByTickerSince(ticker, since)
        val hourly = trades.groupBy { it.openedAt.hour }
            .mapValues { (_, list) ->
                val w = list.count { (it.pnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }
                if (list.isNotEmpty()) w.toDouble() / list.size else 0.0
            }
        return TimePattern(ticker, hourly)
    }
}
