package com.trading.bot.service

import com.trading.bot.event.PositionClosedEvent
import com.trading.bot.model.BlindSpot
import com.trading.bot.model.BlindSpotEntity
import com.trading.bot.model.Position
import com.trading.bot.model.TimePattern
import com.trading.bot.model.TradeStats
import com.trading.bot.repository.BlindSpotRepository
import com.trading.bot.repository.PositionRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Сервис анализа закрытых сделок (мета-анализ для PerformanceFeedbackAgent).
 *
 * - Агрегирует закрытые позиции по тикерам в TradeStats (win rate, PF, SL/TP hit rate)
 * - Считает максимальную серию убытков, лучшее/худшее время входа
 * - Детектирует и персистит «слепые зоны» (BlindSpot): частые SL, убыточные часы
 * - timePatternAnalysis(): почасовая статистика win rate по тикеру
 */
@Service
class TradeAnalysisService(
    private val positionRepo: PositionRepository,
    private val blindSpotRepo: BlindSpotRepository
) {
    private val logger = KotlinLogging.logger {}
    private val analysisMutex = Mutex()
    private val cache = ConcurrentHashMap<Int, CachedAnalysis>()
    private val cacheTtlNanos = java.time.Duration.ofSeconds(30).toNanos()

    private data class CachedAnalysis(
        val createdAtNanos: Long,
        val stats: Map<String, TradeStats>,
    )

    /**
     * Анализирует закрытые позиции за последние N дней и возвращает статистику по тикерам.
     *
     * @param days количество дней для анализа (по умолчанию 14)
     * @return карта тикер -> TradeStats (пустая, если закрытых позиций нет)
     */
    suspend fun analyzeLastNDays(days: Int = 14): Map<String, TradeStats> {
        require(days > 0) { "days must be positive" }
        cached(days)?.let { return it }
        return analysisMutex.withLock {
            cached(days) ?: loadAnalysis(days).also { stats ->
                cache[days] = CachedAnalysis(System.nanoTime(), stats)
            }
        }
    }

    private fun cached(days: Int): Map<String, TradeStats>? =
        cache[days]
            ?.takeIf { System.nanoTime() - it.createdAtNanos < cacheTtlNanos }
            ?.stats

    private suspend fun loadAnalysis(days: Int): Map<String, TradeStats> {
        val since = LocalDateTime.now().minusDays(days.toLong())
        val closed = positionRepo.findClosedSince(since)
        if (closed.isEmpty()) {
            logger.info { "No closed positions in last $days days" }
            return emptyMap()
        }
        return closed.groupBy { it.ticker }.mapValues { (ticker, trades) ->
            buildStats(ticker, trades)
        }
    }

    @EventListener
    fun onPositionClosed(event: PositionClosedEvent) {
        logger.debug { "Invalidating trade analysis cache after closing ${event.ticker}" }
        cache.clear()
    }

    fun invalidateCache() {
        cache.clear()
    }

    private suspend fun buildStats(ticker: String, trades: List<Position>): TradeStats {
        val total = trades.size
        val wins = trades.count { (it.pnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }
        val losses = trades.count { (it.pnl ?: BigDecimal.ZERO) < BigDecimal.ZERO }
        val winRate = if (total > 0) wins.toDouble() / total else 0.0

        val grossProfit = trades
            .mapNotNull { it.pnl }
            .filter { it > BigDecimal.ZERO }
            .fold(BigDecimal.ZERO) { totalProfit, pnl -> totalProfit.add(pnl) }
        val grossLoss = trades
            .mapNotNull { it.pnl }
            .filter { it < BigDecimal.ZERO }
            .fold(BigDecimal.ZERO) { totalLoss, pnl -> totalLoss.add(pnl.abs()) }
        val profitFactor = when {
            grossLoss > BigDecimal.ZERO -> grossProfit.divide(grossLoss, 8, RoundingMode.HALF_UP).toDouble()
            grossProfit > BigDecimal.ZERO -> Double.MAX_VALUE
            else -> 0.0
        }

        val avgWin = if (wins > 0) grossProfit.divide(BigDecimal(wins), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val avgLossVal = if (losses > 0) grossLoss.divide(BigDecimal(losses), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO

        val slHits = trades.count { it.closeReason == "STOP_LOSS" }
        val tpHits = trades.count { it.closeReason == "TAKE_PROFIT" }
        val strategyCloses = trades.count { it.closeReason == "STRATEGY_CLOSE" }
        val slHitRate = if (total > 0) slHits.toDouble() / total else 0.0
        val tpHitRate = if (total > 0) tpHits.toDouble() / total else 0.0
        val strategyCloseRate = if (total > 0) strategyCloses.toDouble() / total else 0.0

        val maxConsecutiveLosses = calculateMaxConsecutiveLosses(trades)

        val holdTimes = trades.mapNotNull { pos ->
            pos.closedAt?.let { closedAt ->
                ChronoUnit.MINUTES.between(pos.openedAt, closedAt)
            }
        }
        val avgHoldTime = holdTimes.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: 0L

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

    private suspend fun detectAndPersistBlindSpots(ticker: String, trades: List<Position>): List<BlindSpot> {
        val losing = trades.filter { (it.pnl ?: BigDecimal.ZERO) < BigDecimal.ZERO }
        if (losing.size < 3) return emptyList()

        val spots = mutableListOf<BlindSpot>()

        val slLosses = losing.count { it.closeReason == "STOP_LOSS" }
        if (slLosses.toDouble() / losing.size > 0.6) {
            val spot = BlindSpot(
                conditionPattern = "Stop-Loss hit rate > 60% for $ticker",
                lossRate = slLosses.toDouble() / losing.size,
                occurrenceCount = slLosses,
                recommendation = "Increase ATR multiplier for stop-loss or review entry points"
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
                    recommendation = "Avoid entries at $hour:00, possibly low liquidity"
                )
                spots.add(spot)
                persistBlindSpot(ticker, spot)
            }
        }

        return spots
    }

    private suspend fun persistBlindSpot(ticker: String, spot: BlindSpot) {
        val existing = blindSpotRepo.findByTickerAndIsActiveTrue(ticker)
            .find { it.conditionPattern == spot.conditionPattern }
        if (existing != null) {
            // Аналитика перечитывает то же окно много раз. Сохраняем фактическое
            // количество наблюдений, а не прибавляем его при каждом GET-запросе.
            blindSpotRepo.save(
                existing.copy(
                    lossRate = spot.lossRate,
                    occurrenceCount = spot.occurrenceCount,
                    recommendation = spot.recommendation,
                ),
            )
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

    /**
     * Почасовая статистика win rate по закрытым сделкам тикера за N дней.
     *
     * @param ticker тикер инструмента
     * @param days количество дней для анализа (по умолчанию 30)
     * @return почасовая карта час -> win rate
     */
    suspend fun timePatternAnalysis(ticker: String, days: Int = 30): TimePattern {
        require(days > 0) { "days must be positive" }
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
