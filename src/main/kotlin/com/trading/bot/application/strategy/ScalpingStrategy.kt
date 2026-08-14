package com.trading.bot.application.strategy

import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.model.StrategyAction
import org.springframework.stereotype.Component
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Скальпинговая стратегия (детерминированная): короткий импульс последнего бара.
 *
 * Вход в сторону движения, если последний бар прошёл не менее половины ATR,
 * направление подтверждено гистограммой MACD и объём выше среднего по окну.
 * Рассчитана на короткий горизонт (вход внутри бара, выход по SL/TP RiskEngine).
 *
 * Решение несёт ТОЛЬКО направление BUY/SELL/HOLD — без размера и стопов
 * (их считают RiskEngine/PositionSizer/OrderBuilder).
 */
@Component
class ScalpingStrategy : Strategy {
    override val id = "SCALPING"

    override suspend fun evaluate(context: StrategyContext): StrategyDecision {
        val price = context.snapshot.currentPrice
        if (context.candles.size < 2) {
            return StrategyDecision.hold(price, "Insufficient candles for momentum")
        }

        val last = context.candles.last()
        val prev = context.candles[context.candles.size - 2]
        val prevPrice = prev.closePrice
        if (prevPrice.signum() <= 0) return StrategyDecision.hold(price, "Invalid previous close")

        val moveFraction = (last.closePrice.subtract(prevPrice)).toDouble() / prevPrice.toDouble()
        val atrPct = (context.indicators?.atr ?: 0.0) / maxOf(price.toDouble(), 1e-9)
        val momentum = if (atrPct > 0.0) moveFraction / atrPct else 0.0
        val macd = context.indicators?.macdHistogram ?: 0.0

        val priorVolumes = context.candles.dropLast(1)
        val avgVolume = priorVolumes.map { it.volume.toDouble() }.average()
        val volumeOk = avgVolume <= 0.0 || last.volume.toDouble() >= avgVolume

        val direction =
            when {
                momentum >= 0.5 && macd > 0.0 && volumeOk -> StrategyAction.BUY
                momentum <= -0.5 && macd < 0.0 && volumeOk -> StrategyAction.SELL
                else -> StrategyAction.HOLD
            }

        if (direction == StrategyAction.HOLD) {
            return StrategyDecision.hold(
                price,
                "No scalp momentum (move=${round(momentum)}xATR, macdHist=${round(macd)}, vol=$volumeOk)",
            )
        }

        val signalStrength = (0.4 + (abs(momentum).coerceIn(0.5, 2.0) - 0.5) / 1.5 * 0.4).coerceIn(0.0, 0.8)
        val reasoning =
            "Momentum=${round(momentum)}xATR, MACD-hist=${round(macd)}, vol confirmed -> $direction"
        return StrategyDecision(direction, price, signalStrength, reasoning)
    }

    private fun round(v: Double): String = v.toBigDecimal().setScale(3, RoundingMode.HALF_UP).toPlainString()
}
