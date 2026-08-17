package com.trading.bot.application.strategy

import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.model.StrategyAction
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Детерминированная стратегия для CNY/RUB (MOEX кросс-курс юань/рубль).
 *
 * Мультифакторный mean-reversion с микроструктурной фильтрацией:
 * - Основной сигнал: RSI + Bollinger Bands (возврат к среднему).
 * - Микроструктурное подтверждение: microprice deviation + OBI направление.
 * - Вход разрешён только при совпадении ТЕХНИЧЕСКОГО и МИКРОСТРУКТУРНОГО сигналов.
 *
 * CNY/RUB — низковолатильный инструмент (дневной диапазон 0.3-0.5%),
 * поэтому пороги мягче, чем у Si/акций:
 * - RSI: BUY < 35, SELL > 65 (вместо 30/70)
 * - BB: цена на/за пределами 1sigma полосы
 * - OBI: |obi| > 0.1 для подтверждения (мягкий порог)
 * - Microprice: deviation != 0 для подтверждения направления
 *
 * При отсутствии микроструктурных данных (bidSize/askSize == null)
 * стратегия работает как классический mean-reversion (fallback).
 *
 * Решение несёт ТОЛЬКО направление BUY/SELL/HOLD — без размера и стопов.
 */
@Component
class CnyRubStrategy : Strategy {
    override val id = "CNYRUB_TOM"

    override suspend fun evaluate(context: StrategyContext): StrategyDecision {
        val tickerUpper = context.ticker.uppercase()
        if (tickerUpper != "CNYRUB_TOM" && tickerUpper != "CNY_RUB") {
            return StrategyDecision.hold(context.snapshot.currentPrice, "Not CNYRUB_TOM ticker")
        }

        val indicators = context.indicators
            ?: return StrategyDecision.hold(context.snapshot.currentPrice, "Insufficient indicators")
        val price = context.snapshot.currentPrice
        val snapshot = context.snapshot

        val technicalDirection = evaluateTechnical(price, indicators)
        if (technicalDirection == StrategyAction.HOLD) {
            return StrategyDecision.hold(
                price,
                "No mean-reversion signal (rsi=${round(indicators.rsi)}, " +
                    "bb=[${indicators.bbLower}..${indicators.bbUpper}])",
            )
        }

        val microstructureConfirm = evaluateMicrostructure(technicalDirection, snapshot)
        val fallbackMode = snapshot.bidSize == null && snapshot.askSize == null

        val direction = if (fallbackMode || microstructureConfirm) {
            technicalDirection
        } else {
            StrategyAction.HOLD
        }

        if (direction == StrategyAction.HOLD) {
            val reason = "Microstructure contradicts $technicalDirection " +
                "(obi=${snapshot.obi}, microprice=${snapshot.microprice})"
            return StrategyDecision.hold(price, reason)
        }

        val signalStrength = computeStrength(indicators.rsi, price, indicators, snapshot)
        val reasoning = buildReasoning(direction, indicators.rsi, snapshot)
        return StrategyDecision(direction, price, signalStrength, reasoning)
    }

    /**
     * Технический сигнал: RSI + Bollinger Bands для currency mean-reversion.
     */
    private fun evaluateTechnical(
        price: BigDecimal,
        indicators: com.trading.bot.domain.technical.IndicatorCalculator.Indicators,
    ): StrategyAction {
        val nearLowerBand = price <= indicators.bbLower
        val nearUpperBand = price >= indicators.bbUpper

        return when {
            indicators.rsi < RSI_BUY_THRESHOLD && nearLowerBand -> StrategyAction.BUY
            indicators.rsi > RSI_SELL_THRESHOLD && nearUpperBand -> StrategyAction.SELL
            else -> StrategyAction.HOLD
        }
    }

    /**
     * Микроструктурное подтверждение: OBI + microprice deviation.
     *
     * BUY подтверждается: obi > 0 (bid pressure) и microprice >= mid
     * SELL подтверждается: obi < 0 (ask pressure) и microprice <= mid
     */
    private fun evaluateMicrostructure(
        direction: StrategyAction,
        snapshot: com.trading.bot.model.dto.MarketSnapshot,
    ): Boolean {
        val obi = snapshot.obi
        val microprice = snapshot.microprice
        val bid = snapshot.bid
        val ask = snapshot.ask

        if (obi == null && microprice == null) return true

        if (obi != null) {
            val obiThreshold = BigDecimal("0.1")
            when (direction) {
                StrategyAction.BUY -> if (obi < obiThreshold.negate()) return false
                StrategyAction.SELL -> if (obi > obiThreshold) return false
                else -> {}
            }
        }

        if (microprice != null && bid != null && ask != null) {
            val mid = bid.add(ask).divide(BigDecimal(2), 8, RoundingMode.HALF_UP)
            when (direction) {
                StrategyAction.BUY -> if (microprice < mid) return false
                StrategyAction.SELL -> if (microprice > mid) return false
                else -> {}
            }
        }

        return true
    }

    private fun computeStrength(
        rsi: Double,
        price: BigDecimal,
        indicators: com.trading.bot.domain.technical.IndicatorCalculator.Indicators,
        snapshot: com.trading.bot.model.dto.MarketSnapshot,
    ): Double {
        val rsiExtremity = if (rsi < 50.0) {
            (RSI_BUY_THRESHOLD - rsi) / RSI_BUY_THRESHOLD
        } else {
            (rsi - RSI_SELL_THRESHOLD) / (100.0 - RSI_SELL_THRESHOLD)
        }

        val bbScore = computeBbScore(price, indicators)

        val obiScore = snapshot.obi?.abs()?.coerceIn(BigDecimal("0"), BigDecimal("1"))?.toDouble() ?: 0.0

        val raw = (0.35 + rsiExtremity.coerceIn(0.0, 1.0) * 0.25 + bbScore * 0.2 + obiScore * 0.2)
        return raw.coerceIn(0.0, 0.9)
    }

    private fun computeBbScore(
        price: BigDecimal,
        indicators: com.trading.bot.domain.technical.IndicatorCalculator.Indicators,
    ): Double {
        val lower = indicators.bbLower
        val upper = indicators.bbUpper
        val middle = indicators.bbMiddle
        val range = upper.subtract(lower)
        if (range.signum() <= 0) return 0.0
        val distFromMid = price.subtract(middle).abs().toDouble() / range.toDouble()
        return distFromMid.coerceIn(0.0, 1.0)
    }

    private fun buildReasoning(
        direction: StrategyAction,
        rsi: Double,
        snapshot: com.trading.bot.model.dto.MarketSnapshot,
    ): String {
        val parts = mutableListOf("CNY/RUB mean-reversion")
        parts.add("RSI=${round(rsi)}")
        snapshot.obi?.let { parts.add("OBI=${round(it.toDouble())}") }
        snapshot.microprice?.let { parts.add("microprice=$it") }
        parts.add("-> $direction")
        return parts.joinToString(", ")
    }

    private fun round(v: Double): String =
        v.toBigDecimal().setScale(3, RoundingMode.HALF_UP).toPlainString()

    private companion object {
        const val RSI_BUY_THRESHOLD = 35.0
        const val RSI_SELL_THRESHOLD = 65.0
    }
}
