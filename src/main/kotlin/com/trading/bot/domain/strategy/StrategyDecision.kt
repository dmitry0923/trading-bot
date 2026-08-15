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

        /**
         * Адаптивный порог уверенности (13.11.8) как ГЕЙТ входа: BUY/SELL с силой
         * сигнала ниже порога ИЛИ с non-finite силой (NaN из LLM-советника) ->
         * HOLD. HOLD-решения не трогает (нет входа — нечего гейтить).
         *
         * Раньше StrategyService «раздувал» слабые сигналы до порога
         * (`coerceAtLeast`): порог как гейт не работал, а сила сигнала в истории
         * и Kelly-сайзинге была фальшивой. Поведение приведено к дизайну 13.11.8
         * (guardrail LOW_CONFIDENCE / deterministic-override Арбитра): ниже
         * порога -> HOLD.
         */
        fun gatedByConfidence(
            decision: StrategyDecision,
            marketPrice: BigDecimal,
            adaptiveConfidence: Double,
        ): StrategyDecision =
            if (decision.action == StrategyAction.HOLD) {
                decision
            } else if (!decision.signalStrength.isFinite() || decision.signalStrength < adaptiveConfidence) {
                hold(
                    marketPrice,
                    "signal strength ${decision.signalStrength} below adaptive confidence threshold $adaptiveConfidence",
                )
            } else {
                decision
            }
    }
}
