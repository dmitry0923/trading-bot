package com.trading.bot.domain.strategy

import com.trading.bot.model.StrategyAction
import java.math.BigDecimal

/**
 * Решение стратегического этапа.
 *
 * Несёт ТОЛЬКО направление (BUY/SELL/HOLD), целевую цену, силу сигнала и
 * обоснование. Размер позиции, SL/TP и прочие параметры заявки вычисляются
 * ниже по пайплайну (RiskEngine → PositionSizer → OrderBuilder).
 */
data class StrategyDecision(
    val action: StrategyAction,
    val targetPrice: BigDecimal,
    val signalStrength: Double,
    val reasoning: String,
) {
    /** HOLD-решение с нулевой силой сигнала (нет сигнала от стратегии). */
    companion object {
        fun hold(
            marketPrice: BigDecimal,
            reason: String,
        ): StrategyDecision = StrategyDecision(StrategyAction.HOLD, marketPrice, 0.0, reason)
    }
}
