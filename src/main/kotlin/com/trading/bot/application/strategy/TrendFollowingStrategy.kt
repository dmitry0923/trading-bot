package com.trading.bot.application.strategy

import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.model.StrategyAction
import org.springframework.stereotype.Component
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Стратегия следования за трендом (детерминированная).
 *
 * Входит в направлении тренда EMA12/EMA26 при подтверждении гистограммы MACD и
 * без экстремума RSI (исключаются зоны перекупленности/перепроданности, где
 * тренд может развернуться). Уверенность растёт с силой импульса MACD.
 *
 * Решение несёт ТОЛЬКО направление BUY/SELL/HOLD — без размера и стопов
 * (их считают RiskEngine/PositionSizer/OrderBuilder).
 */
@Component
class TrendFollowingStrategy : Strategy {
    override val id = "TREND_FOLLOWING"

    override suspend fun evaluate(context: StrategyContext): StrategyDecision {
        val indicators =
            context.indicators
                ?: return StrategyDecision.hold(context.snapshot.currentPrice, "Insufficient indicators")
        val price = context.snapshot.currentPrice

        val direction =
            when {
                indicators.trend == "UP" && indicators.macdHistogram > 0.0 && indicators.rsi in 40.0..70.0 -> {
                    StrategyAction.BUY
                }

                indicators.trend == "DOWN" && indicators.macdHistogram < 0.0 && indicators.rsi in 30.0..60.0 -> {
                    StrategyAction.SELL
                }

                else -> {
                    StrategyAction.HOLD
                }
            }

        if (direction == StrategyAction.HOLD) {
            return StrategyDecision.hold(
                price,
                "No trend signal (trend=${indicators.trend}, macdHist=${round(indicators.macdHistogram)}, rsi=${round(indicators.rsi)})",
            )
        }

        val macdFraction = abs(indicators.macdHistogram) / maxOf(price.toDouble() * 0.01, 1e-9)
        val confidence = (0.45 + (macdFraction / 0.5).coerceIn(0.0, 1.0) * 0.45).coerceIn(0.0, 0.9)
        val reasoning =
            "Trend=${indicators.trend}, MACD-hist=${round(indicators.macdHistogram)}, " +
                "RSI=${round(indicators.rsi)} -> $direction"
        return StrategyDecision(direction, price, confidence, reasoning)
    }

    private fun round(v: Double): String = v.toBigDecimal().setScale(3, RoundingMode.HALF_UP).toPlainString()
}
