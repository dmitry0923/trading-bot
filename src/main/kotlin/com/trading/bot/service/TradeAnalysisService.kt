package com.trading.bot.service

import com.trading.bot.model.CloseReason
import com.trading.bot.model.dto.BlindSpot
import com.trading.bot.model.dto.TimePattern
import com.trading.bot.model.dto.TradeStats
import com.trading.bot.model.entity.BlindSpotEntity
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.BlindSpotRepository
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Сервис анализа закрытых сделок (мета-анализ для PerformanceFeedbackAgent).
 *
 * - Агрегирует закрытые позиции по тикерам в TradeStats (win rate, PF, SL/TP hit rate)
 * - Считает максимальную серию убытков, лучшее/худшее время входа
 * - Детектирует и персистит «слепые зоны» (BlindSpot): частые SL, убыточные часы
 * - timePatternAnalysis(): почасовая статистика win rate по тикеру
 */
private val moscowZone = ZoneId.of("Europe/Moscow")

@Service
class TradeAnalysisService(
    private val positionRepo: PositionRepository,
    private val blindSpotRepo: BlindSpotRepository,
    private val clock: Clock = Clock.system(moscowZone),
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Анализирует закрытые позиции за последние N дней и возвращает статистику по тикерам.
     *
     * @param days количество дней для анализа (по умолчанию 14)
     * @param accountId ID аккаунта.
     *   null = все аккаунты (включая legacy без привязки).
     *   non-null = только указанный аккаунт.
     * @return карта тикер -> TradeStats (пустая, если закрытых позиций нет)
     */
    suspend fun analyzeLastNDays(days: Int = 14, accountId: Long? = null): Map<String, TradeStats> {
        val since = LocalDateTime.now(clock).minusDays(days.toLong())
        val closed = if (accountId != null) {
            positionRepo.findClosedByAccountSince(accountId, since)
        } else {
            positionRepo.findClosedSince(since)
        }

        if (closed.isEmpty()) {
            logger.info { "No closed positions in last $days days" }
            return emptyMap()
        }

        return closed.groupBy { it.ticker }.mapValues { (ticker, trades) ->
            buildStats(ticker, trades)
        }
    }

    private suspend fun buildStats(
        ticker: String,
        trades: List<Position>,
    ): TradeStats {
        val total = trades.size
        val wins = trades.count { (it.pnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }
        val losses = total - wins
        val winRate = if (total > 0) wins.toDouble() / total else 0.0

        val grossProfit =
            trades
                .filter { (it.pnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }
                .sumOf { it.pnl ?: BigDecimal.ZERO }
        val grossLoss =
            trades
                .filter { (it.pnl ?: BigDecimal.ZERO) < BigDecimal.ZERO }
                .sumOf { kotlin.math.abs((it.pnl ?: BigDecimal.ZERO).toDouble()) }
        // PF = grossProfit / grossLoss. НЕТ проигрышей (grossLoss == 0) при наличии
        // прибыли -> +Infinity (не 0.0!): иначе shouldPauseTrading ставит 100%-прибыльный
        // тикер на паузу (profitFactor 0.0 попадает в диапазон 0.0..0.5). Конвенция
        // Infinity уже принята в BacktestResult.
        val profitFactor =
            when {
                grossLoss > 0 -> grossProfit.toDouble() / grossLoss
                grossProfit > BigDecimal.ZERO -> Double.POSITIVE_INFINITY
                else -> 0.0
            }

        val avgWin = if (wins > 0) grossProfit.divide(BigDecimal(wins), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val avgLossVal = if (losses > 0) BigDecimal(grossLoss / losses).setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO

        val slHits = trades.count { it.closeReason == CloseReason.STOP_LOSS }
        val tpHits = trades.count { it.closeReason == CloseReason.TAKE_PROFIT }
        val strategyCloses = trades.count { it.closeReason == CloseReason.STRATEGY_CLOSE }
        val slHitRate = if (total > 0) slHits.toDouble() / total else 0.0
        val tpHitRate = if (total > 0) tpHits.toDouble() / total else 0.0
        val strategyCloseRate = if (total > 0) strategyCloses.toDouble() / total else 0.0

        val maxConsecutiveLosses = calculateMaxConsecutiveLosses(trades)

        val avgHoldTime =
            if (trades.isNotEmpty()) {
                trades
                    .mapNotNull { pos ->
                        if (pos.closedAt != null) {
                            ChronoUnit.MINUTES.between(pos.openedAt, pos.closedAt)
                        } else {
                            null
                        }
                    }.average()
                    .toLong()
            } else {
                0L
            }

        val hourly = hourlyWinRate(trades)
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
            blindSpots = blindSpots,
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

    private suspend fun detectAndPersistBlindSpots(
        ticker: String,
        trades: List<Position>,
    ): List<BlindSpot> {
        val losing = trades.filter { (it.pnl ?: BigDecimal.ZERO) < BigDecimal.ZERO }
        if (losing.size < 3) return emptyList()

        val spots = mutableListOf<BlindSpot>()

        val slLosses = losing.count { it.closeReason == CloseReason.STOP_LOSS }
        if (slLosses.toDouble() / losing.size > 0.6) {
            val spot =
                BlindSpot(
                    conditionPattern = "Stop-Loss hit rate > 60% for $ticker",
                    lossRate = slLosses.toDouble() / losing.size,
                    occurrenceCount = slLosses,
                    recommendation = "Increase ATR multiplier for stop-loss or review entry points",
                )
            spots.add(spot)
            persistBlindSpot(ticker, spot)
        }

        val hourlyLosses = losing.groupBy { it.openedAt.hour }
        hourlyLosses.forEach { (hour, list) ->
            if (list.size >= 3) {
                val spot =
                    BlindSpot(
                        conditionPattern = "Entry at hour $hour for $ticker",
                        lossRate = list.size.toDouble() / losing.size,
                        occurrenceCount = list.size,
                        recommendation = "Avoid entries at $hour:00, possibly low liquidity",
                    )
                spots.add(spot)
                persistBlindSpot(ticker, spot)
            }
        }

        return spots
    }

    private suspend fun persistBlindSpot(
        ticker: String,
        spot: BlindSpot,
    ) {
        val existing =
            blindSpotRepo
                .findByTickerAndIsActiveTrue(ticker)
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
                    recommendation = spot.recommendation,
                ),
            )
        }
    }

    /**
     * Почасовая статистика win rate по закрытым сделкам тикера за N дней.
     *
     * @param ticker тикер инструмента
     * @param days количество дней для анализа (по умолчанию 30)
     * @param accountId ID аккаунта. null = все аккаунты, non-null = только указанный.
     * @return почасовая карта час -> win rate
     */
    suspend fun timePatternAnalysis(
        ticker: String,
        days: Int = 30,
        accountId: Long? = null,
    ): TimePattern {
        val since = LocalDateTime.now(clock).minusDays(days.toLong())
        val trades = if (accountId != null) {
            positionRepo.findClosedByTickerAndAccountSince(ticker, accountId, since)
        } else {
            positionRepo.findClosedByTickerSince(ticker, since)
        }
        val hourly = hourlyWinRate(trades)
        return TimePattern(ticker, hourly)
    }

    private fun hourlyWinRate(trades: List<Position>): Map<Int, Double> =
        trades
            .groupBy { it.openedAt.hour }
            .mapValues { (_, list) ->
                val w = list.count { (it.pnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }
                if (list.isNotEmpty()) w.toDouble() / list.size else 0.0
            }
}
