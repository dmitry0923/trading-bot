package com.trading.bot.domain.strategy

/**
 * Контракт стратегического этапа.
 *
 * Любая реализация (детерминированные правила или LLM-путь) получает
 * [StrategyContext] и возвращает [StrategyDecision] — чистое направление без
 * риск-параметров. Стратегия не знает о RiskEngine/PositionSizer/OrderBuilder:
 * нижележащий пайплайн общий для всех стратегий.
 */
interface Strategy {
    /** Стабильный идентификатор стратегии (напр. "TREND_FOLLOWING"). */
    val id: String

    suspend fun evaluate(context: StrategyContext): StrategyDecision
}
