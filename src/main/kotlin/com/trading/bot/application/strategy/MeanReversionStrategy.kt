package com.trading.bot.application.strategy

import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.model.StrategyAction
import org.springframework.stereotype.Component
import java.math.RoundingMode

/**
 * Контртрендовая стратегия возврата к среднему (детерминированная).
 *
 * BUY при перепроданности (RSI < 30 и цена на/ниже нижней полосы Боллинджера),
 * SELL при перекупленности (RSI > 70 и цена на/выше верхней полосы). Уверенность
 * растёт с отклонением RSI от нейтральной зоны.
 *
 * Решение несёт ТОЛЬКО направление BUY/SELL/HOLD — без размера и стопов
 * (их считают RiskEngine/PositionSizer/OrderBuilder).
 */
@Component
class MeanReversionStrategy : Strategy {
    override val id = "MEAN_REVERSION"

    override suspend fun evaluate(context: StrategyContext): StrategyDecision {
        val indicators =
            context.indicators
                ?: return StrategyDecision.hold(context.snapshot.currentPrice, "Insufficient indicators")
        val price = context.snapshot.currentPrice
        val close = context.candles.lastOrNull()?.closePrice ?: price

        val direction =
            when {
                indicators.rsi < 30.0 && close <= indicators.bbLower -> StrategyAction.BUY
                indicators.rsi > 70.0 && close >= indicators.bbUpper -> StrategyAction.SELL
                else -> StrategyAction.HOLD
            }

        if (direction == StrategyAction.HOLD) {
            return StrategyDecision.hold(
                price,
                "No mean-reversion signal (rsi=${round(indicators.rsi)}, bb=[${indicators.bbLower}..${indicators.bbUpper}])",
            )
        }

        val extremity = if (direction == StrategyAction.BUY) (30.0 - indicators.rsi) / 30.0 else (indicators.rsi - 70.0) / 30.0
        val signalStrength = (0.5 + extremity.coerceIn(0.0, 1.0) * 0.4).coerceIn(0.0, 0.9)
        val reasoning =
            "RSI=${round(indicators.rsi)} at ${if (direction == StrategyAction.BUY) "lower" else "upper"} Bollinger band -> $direction"
        return StrategyDecision(direction, price, signalStrength, reasoning)
    }

    private fun round(v: Double): String = v.toBigDecimal().setScale(3, RoundingMode.HALF_UP).toPlainString()
}
