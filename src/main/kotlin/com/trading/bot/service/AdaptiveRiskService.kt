package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.model.*
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import kotlin.math.sqrt

/**
 * Адаптивный риск-менеджмент на основе статистики сделок (Kelly).
 *
 * - calculateOptimalPositionSize(): размер позиции по критерию Келли (cap 50%, floor 0)
 * - Адаптивные SL/TP: множитель ATR зависит от sl/tp hit rate тикера
 * - Адаптивный порог уверенности арбитра: хуже win rate -> выше порог
 * - shouldPauseTrading()/isInDrawdownRecovery(): пауза при серии убытков
 * - correlationOf()/exceedsCorrelationLimit(): корреляционный фильтр по закрытиям из Redis
 * - Все решения логируются в метрики adaptive.*
 */
@Service
class AdaptiveRiskService(
    private val riskConfig: RiskConfig,
    private val tradeAnalysisService: TradeAnalysisService,
    private val positionRepo: PositionRepository,
    private val candleCache: CandleCacheService,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val correlationThreshold = 0.8
    private val correlationMinSamples = 30

    /**
     * Коэффициент корреляции Пирсона между ценами закрытия двух тикеров
     * за последние [period] свечей из Redis-кэша.
     *
     * @param a первый тикер
     * @param b второй тикер
     * @param timeframe таймфрейм свечей
     * @param period глубина расчёта
     * @return корреляция в [-1, 1] или null, если данных недостаточно
     */
    fun correlationOf(
        a: String,
        b: String,
        timeframe: String = "MINUTE_10",
        period: Int = 50,
    ): Double? {
        if (a == b) return 1.0
        val x = candleCache.getRecentCandles(a, timeframe, period).map { it.closePrice.toDouble() }
        val y = candleCache.getRecentCandles(b, timeframe, period).map { it.closePrice.toDouble() }
        if (x.size < correlationMinSamples || y.size < correlationMinSamples) return null
        val n = minOf(x.size, y.size)
        val xs = x.takeLast(n)
        val ys = y.takeLast(n)
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var dx2 = 0.0
        var dy2 = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            num += dx * dy
            dx2 += dx * dx
            dy2 += dy * dy
        }
        if (dx2 == 0.0 || dy2 == 0.0) return null
        return num / sqrt(dx2 * dy2)
    }

    /**
     * Корреляционный фильтр: запрещает открытие позиции, если корреляция
     * с любой открытой позицией превышает [correlationThreshold].
     *
     * При недостатке данных (менее 30 свечей в кэше) фильтр пропускает сделку.
     *
     * @param candidateTicker тикер входа
     * @param openPositions открытые позиции
     * @return true, если вход запрещён по корреляции
     */
    fun exceedsCorrelationLimit(
        candidateTicker: String,
        openPositions: List<Position>,
    ): Boolean {
        if (candidateTicker == "Si") return false // фьючерсный хедж не фильтруется
        val blocked =
            openPositions.any { pos ->
                if (pos.ticker == candidateTicker || pos.ticker == "Si") return@any false
                (correlationOf(candidateTicker, pos.ticker) ?: 0.0) > correlationThreshold
            }
        if (blocked) {
            meterRegistry.counter("adaptive.correlation.blocked", Tags.of("ticker", candidateTicker)).increment()
        }
        return blocked
    }

    /**
     * Оптимальный размер позиции по критерию Келли для тикера.
     *
     * @param ticker тикер инструмента
     * @return рекомендуемый размер позиции в рублях (0 при невыгодной статистике)
     */
    suspend fun calculateOptimalPositionSize(ticker: String): BigDecimal {
        val stats = tradeAnalysisService.analyzeLastNDays(30)[ticker]
        if (stats == null || stats.totalTrades < 5) {
            meterRegistry.gauge("adaptive.position_size", Tags.of("ticker", ticker), riskConfig.maxPositionRub.toDouble())
            return riskConfig.maxPositionRub
        }

        val w = stats.winRate
        val avgLossAbs = kotlin.math.abs(stats.avgLoss.toDouble()).coerceAtLeast(0.01)
        val r = stats.avgWin.toDouble() / avgLossAbs
        val kelly = (w * r - (1 - w)) / r

        // Классический (Full) Kelly слишком агрессивен: применяем долю
        // riskConfig.kellyFraction (Half/Quarter-Kelly по умолчанию 0.5) и кап 50%.
        val safeKelly = (kelly * riskConfig.kellyFraction).coerceAtMost(0.50).coerceAtLeast(0.0)
        val size =
            if (safeKelly > 0) {
                riskConfig.maxPositionRub.multiply(BigDecimal(safeKelly))
            } else {
                BigDecimal.ZERO
            }

        meterRegistry.gauge("adaptive.position_size", Tags.of("ticker", ticker), size.toDouble())
        logger.info { "Kelly size for $ticker: ${size.toInt()} (kelly=$kelly, safe=$safeKelly)" }
        return size
    }

    /**
     * Адаптивная цена стоп-лосса: ATR * множитель, зависящий от SL hit rate тикера.
     *
     * @param entryPrice цена входа
     * @param direction направление позиции
     * @param ticker тикер инструмента
     * @param atr текущее значение ATR
     * @return цена стоп-лосса (с 2 знаками после запятой)
     */
    suspend fun calculateAdaptiveSL(
        entryPrice: BigDecimal,
        direction: PositionDirection,
        ticker: String,
        atr: BigDecimal,
    ): BigDecimal {
        val stats = tradeAnalysisService.analyzeLastNDays(14)[ticker]
        val baseMultiplier =
            when {
                (stats?.slHitRate ?: 0.0) > 0.65 -> BigDecimal("2.5")
                (stats?.slHitRate ?: 0.0) < 0.30 -> BigDecimal("1.5")
                else -> BigDecimal("2.0")
            }
        val atrBased = atr.multiply(baseMultiplier)
        return when (direction) {
            PositionDirection.LONG -> entryPrice.subtract(atrBased).setScale(2, RoundingMode.HALF_UP)
            PositionDirection.SHORT -> entryPrice.add(atrBased).setScale(2, RoundingMode.HALF_UP)
        }
    }

    /**
     * Адаптивная цена тейк-профита: ATR * множитель, зависящий от TP hit rate тикера.
     *
     * @param entryPrice цена входа
     * @param direction направление позиции
     * @param ticker тикер инструмента
     * @param atr текущее значение ATR
     * @return цена тейк-профита (с 2 знаками после запятой)
     */
    suspend fun calculateAdaptiveTP(
        entryPrice: BigDecimal,
        direction: PositionDirection,
        ticker: String,
        atr: BigDecimal,
    ): BigDecimal {
        val stats = tradeAnalysisService.analyzeLastNDays(14)[ticker]
        val baseMultiplier =
            when {
                (stats?.tpHitRate ?: 0.0) > 0.50 -> BigDecimal("3.0")
                (stats?.tpHitRate ?: 0.0) < 0.20 -> BigDecimal("2.0")
                else -> BigDecimal("2.5")
            }
        val atrBased = atr.multiply(baseMultiplier)
        return when (direction) {
            PositionDirection.LONG -> entryPrice.add(atrBased).setScale(2, RoundingMode.HALF_UP)
            PositionDirection.SHORT -> entryPrice.subtract(atrBased).setScale(2, RoundingMode.HALF_UP)
        }
    }

    /**
     * Адаптивный порог уверенности для арбитра по тикеру.
     *
     * @param ticker тикер инструмента
     * @return порог уверенности от 0.55 (сильная статистика) до 0.80 (слабая)
     */
    suspend fun getAdaptiveConfidenceThreshold(ticker: String): Double {
        val stats = tradeAnalysisService.analyzeLastNDays(14)[ticker]
        return when {
            stats == null -> 0.60
            stats.winRate < 0.35 -> 0.80
            stats.winRate < 0.45 -> 0.70
            stats.winRate > 0.60 -> 0.55
            else -> 0.60
        }
    }

    /**
     * Проверяет, находится ли бот в режиме восстановления после просадки.
     *
     * @return true, если за последние 3 дня было >= 3 убыточных сделок подряд
     */
    suspend fun isInDrawdownRecovery(): Boolean {
        val recent = positionRepo.findClosedSince(LocalDateTime.now().minusDays(3))
        val consecutiveLosses =
            recent
                .reversed()
                .takeWhile {
                    (it.pnl ?: BigDecimal.ZERO) < BigDecimal.ZERO
                }.count()
        val result = consecutiveLosses >= 3
        meterRegistry.gauge("adaptive.drawdown_recovery", if (result) 1.0 else 0.0)
        return result
    }

    /**
     * Проверяет, стоит ли приостановить торговлю по тикеру.
     *
     * @param ticker тикер инструмента
     * @return true при серии >= 4 убытков или очень низком profit factor
     */
    suspend fun shouldPauseTrading(ticker: String): Boolean {
        val stats = tradeAnalysisService.analyzeLastNDays(7)[ticker]
        val result =
            when {
                stats == null -> false
                stats.maxConsecutiveLosses >= 4 -> true
                stats.profitFactor in 0.0..0.5 && stats.totalTrades >= 5 -> true
                else -> false
            }
        meterRegistry.gauge("adaptive.pause", Tags.of("ticker", ticker), if (result) 1.0 else 0.0)
        return result
    }
}
