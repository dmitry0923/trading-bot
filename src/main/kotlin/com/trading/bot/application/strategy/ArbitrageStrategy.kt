package com.trading.bot.application.strategy

import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.model.StrategyAction
import org.springframework.stereotype.Component
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Арбитражная стратегия — конвергенция к связанному инструменту (детерминированная).
 *
 * Сравнивает цену инструмента с ценой связанного инструмента ([StrategyContext.relatedQuote],
 * например Si против USDRUB). При отклонении базиса выше порога (с учётом ATR) —
 * сигнал на возврат к паритету: цена завышена -> SELL, занижена -> BUY.
 *
 * Без настроенного relatedQuote стратегия всегда HOLD. Решение несёт ТОЛЬКО
 * направление BUY/SELL/HOLD — без размера и стопов (их считают
 * RiskEngine/PositionSizer/OrderBuilder).
 */
@Component
class ArbitrageStrategy : Strategy {
    override val id = "ARBITRAGE"

    override suspend fun evaluate(context: StrategyContext): StrategyDecision {
        val price = context.snapshot.currentPrice
        val related =
            context.relatedQuote
                ?: return StrategyDecision.hold(price, "No related quote configured")
        if (price.signum() <= 0 || related.signum() <= 0) {
            return StrategyDecision.hold(price, "Invalid price ($price) or related quote ($related)")
        }

        val basis = price.subtract(related).toDouble() / related.toDouble()
        val atrPct = (context.indicators?.atr ?: 0.0) / maxOf(price.toDouble(), 1e-9)
        val threshold = maxOf(MIN_BASIS_PCT, atrPct * ATR_MULTIPLIER)

        val direction =
            when {
                basis >= threshold -> StrategyAction.SELL
                basis <= -threshold -> StrategyAction.BUY
                else -> StrategyAction.HOLD
            }

        if (direction == StrategyAction.HOLD) {
            return StrategyDecision.hold(price, "Basis ${round(basis)} within threshold $threshold")
        }

        val excess = abs(basis) / threshold
        val signalStrength = (0.5 + excess.coerceIn(0.0, 1.0) * 0.4).coerceIn(0.0, 0.9)
        val reasoning =
            "Basis=${round(basis)} vs related=$related (threshold=$threshold) -> $direction"
        return StrategyDecision(direction, price, signalStrength, reasoning)
    }

    private fun round(v: Double): String = v.toBigDecimal().setScale(4, RoundingMode.HALF_UP).toPlainString()

    private companion object {
        /** Минимальный базис (доля цены) для сигнала без данных о волатильности. */
        const val MIN_BASIS_PCT = 0.003

        /** Порог базиса = ATR% × множитель: адаптация к волатильности инструмента. */
        const val ATR_MULTIPLIER = 0.5
    }
}
