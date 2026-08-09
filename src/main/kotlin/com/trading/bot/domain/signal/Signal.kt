package com.trading.bot.domain.signal

import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Strategy
import java.math.BigDecimal

/**
 * Решение стратегического этапа пайплайна.
 *
 * Содержит ТОЛЬКО направление (BUY/SELL/HOLD), целевую цену и уверенность.
 * Размер позиции, SL/TP, плечо и прочие параметры заявки вычисляются позже
 * (RiskEngine → PositionSizer → OrderBuilder) и переносятся в
 * [com.trading.bot.domain.order.OrderParams].
 */
data class Signal(
    val ticker: String,
    val action: StrategyAction,
    val targetPrice: BigDecimal,
    val confidence: Double,
    val reasoning: String,
    val timeframe: String,
    val cycleId: String,
    val strategyName: String? = null,
)

/**
 * Стратегия сохраняется в историю (БД) на стратегическом этапе как [Signal].
 * Риск-поля (quantity/SL/TP/trailing) заполняются на этапе OrderBuilder
 * отдельным апдейтом (см. StrategyRepository.updateOrderParams) и остаются
 * в таблице как лог фактической заявки.
 */
fun Strategy.toSignal(): Signal =
    Signal(
        ticker = ticker,
        action = action,
        targetPrice = targetPrice,
        confidence = confidence,
        reasoning = reasoning,
        timeframe = timeframe,
        cycleId = cycleId,
        strategyName = strategyName,
    )
