package com.trading.bot.model

/**
 * Риск-контекст на момент принятия решения агентом.
 * Формируется TradingPipeline/StrategyService из RiskEngine и AdaptiveRiskService.
 */
data class RiskContext(
    val shouldPause: Boolean = false,
    val dailyLossLimitReached: Boolean = false,
    val drawdownRecovery: Boolean = false,
    val openPositionsCount: Int = 0,
    val maxOpenPositions: Int = 5
) {
    fun blocking(): Boolean = shouldPause || dailyLossLimitReached
}
