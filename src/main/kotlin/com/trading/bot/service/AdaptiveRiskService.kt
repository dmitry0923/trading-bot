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

@Service
class AdaptiveRiskService(
    private val riskConfig: RiskConfig,
    private val tradeAnalysisService: TradeAnalysisService,
    private val positionRepo: PositionRepository,
    private val meterRegistry: MeterRegistry
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Kelly Criterion: f* = (p*b - q) / b
     * half-Kelly для консервативности, максимум 50% от maxPositionRub.
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

    fun isInDrawdownRecovery(): Boolean {
        val recent = positionRepo.findClosedSince(LocalDateTime.now().minusDays(3))
        val consecutiveLosses = recent.reversed().takeWhile {
            (it.pnl ?: BigDecimal.ZERO) < BigDecimal.ZERO
        }.count()
        val result = consecutiveLosses >= 3
        meterRegistry.gauge("adaptive.drawdown_recovery", if (result) 1.0 else 0.0)
        return result
    }

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
