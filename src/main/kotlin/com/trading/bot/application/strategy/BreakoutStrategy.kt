package com.trading.bot.application.strategy

import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.model.StrategyAction
import org.springframework.stereotype.Component
import java.math.RoundingMode

/**
 * Стратегия пробоя диапазона (детерминированная).
 *
 * BUY при закрытии выше сопротивления (максимум за окно перед последним баром),
 * SELL при закрытии ниже поддержки (минимум за то же окно). Уверенность растёт
 * с силой пробоя относительно ATR.
 *
 * Решение несёт ТОЛЬКО направление BUY/SELL/HOLD — без размера и стопов
 * (их считают RiskEngine/PositionSizer/OrderBuilder).
 */
@Component
class BreakoutStrategy : Strategy {
    override val id = "BREAKOUT"

    override suspend fun evaluate(context: StrategyContext): StrategyDecision {
        val price = context.snapshot.currentPrice
        if (context.candles.size < WINDOW + 2) {
            return StrategyDecision.hold(price, "Not enough candles for range (need ${WINDOW + 2})")
        }

        // Диапазон из WINDOW баров ПЕРЕД последним: пробой считается относительно
        // сформированного диапазона, а не текущего (ещё незакрытого) бара.
        val prior = context.candles.takeLast(WINDOW + 1).dropLast(1)
        val resistance = prior.maxOf { it.highPrice }
        val support = prior.minOf { it.lowPrice }
        val close = context.candles.last().closePrice
        val atr = context.indicators?.atr ?: 0.0

        val direction =
            when {
                close > resistance -> StrategyAction.BUY
                close < support -> StrategyAction.SELL
                else -> StrategyAction.HOLD
            }

        if (direction == StrategyAction.HOLD) {
            return StrategyDecision.hold(
                price,
                "No breakout (support=$support, resistance=$resistance, close=$close)",
            )
        }

        val breakSize = if (direction == StrategyAction.BUY) close.subtract(resistance) else support.subtract(close)
        val strength = if (atr > 0.0) breakSize.toDouble() / atr else 1.0
        val signalStrength = (0.45 + strength.coerceIn(0.0, 1.0) * 0.45).coerceIn(0.0, 0.9)
        val reasoning =
            "Breakout ${if (direction == StrategyAction.BUY) "above $resistance" else "below $support"} " +
                "by ${round(breakSize)} (${round(strength)}xATR) -> $direction"
        return StrategyDecision(direction, price, signalStrength, reasoning)
    }

    private fun round(v: Double): String = v.toBigDecimal().setScale(3, RoundingMode.HALF_UP).toPlainString()

    private fun round(v: java.math.BigDecimal): String = v.setScale(3, RoundingMode.HALF_UP).toPlainString()

    private companion object {
        const val WINDOW = 20
    }
}
