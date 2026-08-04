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

/**
 * Адаптивный риск-менеджмент на основе статистики сделок (Kelly).
 *
 * - calculateOptimalPositionSize(): размер позиции по критерию Келли (cap 50%, floor 0)
 * - Адаптивные SL/TP: множитель ATR зависит от sl/tp hit rate тикера
 * - Адаптивный порог уверенности арбитра: хуже win rate -> выше порог
 * - shouldPauseTrading()/isInDrawdownRecovery(): пауза при серии убытков
 * - Все решения логируются в метрики adaptive.*
 */
@Service
class AdaptiveRiskService(
    private val riskConfig: RiskConfig,
    private val tradeAnalysisService: TradeAnalysisService,
    private val positionRepo: PositionRepository,
    private val meterRegistry: MeterRegistry
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Оптимальный размер позиции по критерию Келли для тикера.
     *
     * @param ticker тикер инструмента
     * @return рекомендуемый размер позиции в рублях (0 при невыгодной статистике)
     */
    fun calculateOptimalPositionSize(ticker: String): BigDecimal {
        val stats = tradeAnalysisService.analyzeLastNDays(30)[ticker]
        if (stats == null || stats.totalTrades < 5) {
            meterRegistry.gauge("adaptive.position_size", Tags.of("ticker", ticker), riskConfig.maxPositionRub.toDouble())
            return riskConfig.maxPositionRub
        }

        val w = stats.winRate
        val avgLossAbs = kotlin.math.abs(stats.avgLoss.toDouble()).coerceAtLeast(0.01)
        val r = stats.avgWin.toDouble() / avgLossAbs
        val kelly = (w * r - (1 - w)) / r

        val safeKelly = kelly.coerceAtMost(0.50).coerceAtLeast(0.0)
        val size = if (safeKelly > 0) {
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
    fun calculateAdaptiveSL(
        entryPrice: BigDecimal,
        direction: PositionDirection,
        ticker: String,
        atr: BigDecimal
    ): BigDecimal {
        val stats = tradeAnalysisService.analyzeLastNDays(14)[ticker]
        val baseMultiplier = when {
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
    fun calculateAdaptiveTP(
        entryPrice: BigDecimal,
        direction: PositionDirection,
        ticker: String,
        atr: BigDecimal
    ): BigDecimal {
        val stats = tradeAnalysisService.analyzeLastNDays(14)[ticker]
        val baseMultiplier = when {
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
    fun getAdaptiveConfidenceThreshold(ticker: String): Double {
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
    fun isInDrawdownRecovery(): Boolean {
        val recent = positionRepo.findClosedSince(LocalDateTime.now().minusDays(3))
        val consecutiveLosses = recent.reversed().takeWhile {
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
    fun shouldPauseTrading(ticker: String): Boolean {
        val stats = tradeAnalysisService.analyzeLastNDays(7)[ticker]
        val result = when {
            stats == null -> false
            stats.maxConsecutiveLosses >= 4 -> true
            stats.profitFactor in 0.0..0.5 && stats.totalTrades >= 5 -> true
            else -> false
        }
        meterRegistry.gauge("adaptive.pause", Tags.of("ticker", ticker), if (result) 1.0 else 0.0)
        return result
    }
}
