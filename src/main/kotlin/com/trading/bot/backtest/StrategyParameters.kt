package com.trading.bot.backtest

import com.trading.bot.model.entity.Candle

/**
 * Единый ЗАМОРОЖЕННЫЙ набор параметров стратегии, применённый к независимому
 * holdout-окну (P1-аудит: отсутствие OOS-leakage).
 *
 * В отличие от [GridParams], кроме SL/TP несёт и управляющие параметры
 * (leverage, риск на сделку, лимит контрактов) и adaptive confidence-порог —
 * ни один из них не может быть «подкручен» после просмотра holdout: любой
 * перенастрой требует нового holdout-цикла.
 */
data class StrategyParameters(
    val slPercent: Double = 0.0,
    val tpPercent: Double = 0.0,
    val slPoints: Int? = null,
    val tpPoints: Int? = null,
    val confidenceThreshold: Double? = null,
    val leverage: Double = 1.0,
    val riskPerTradePercent: Double? = null,
    val futuresMaxContractsPerPosition: Int? = null,
)

/**
 * Разделение истории на dev-часть (первые `1 - holdoutFraction`) и независимый
 * holdout (последние `holdoutFraction`). Гарантирует, что ни одна настройка
 * (WFA / Monte Carlo / edge-проверка) не видит holdout-окно.
 */
fun splitDevHoldout(
    candles: List<Candle>,
    holdoutFraction: Double,
): Pair<List<Candle>, List<Candle>> {
    require(holdoutFraction > 0.0 && holdoutFraction < 1.0) { "holdoutFraction must be in (0, 1)" }
    val sorted = candles.sortedBy { it.time }
    val holdoutSize = (sorted.size * holdoutFraction).toInt().coerceAtLeast(1)
    val holdoutStart = sorted.size - holdoutSize
    return sorted.subList(0, holdoutStart) to sorted.subList(holdoutStart, sorted.size)
}
