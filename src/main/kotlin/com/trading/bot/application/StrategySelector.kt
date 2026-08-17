package com.trading.bot.application

import com.trading.bot.domain.risk.PerTickerRegime
import com.trading.bot.domain.risk.RegimeDirection
import com.trading.bot.domain.risk.RegimeVolatility
import org.springframework.stereotype.Component

/**
 * Strategy Selector: переводит per-ticker рыночный режим ([PerTickerRegime])
 * в допустимый набор стратегий (жёсткий фильтр) и веса уверенности (мягкое
 * взвешивание).
 *
 * Логика:
 *   - Crash/Pump/низкая ликвидность/экстремальная волатильность
 *     ([PerTickerRegime.blocksEntry]) → допустимое множество пусто (входы
 *     блокируются на стратегическом этапе);
 *   - направление определяет базовую совместимость: тренд → трендовые/пробойные,
 *     боковик → диапазонные/контртрендовые;
 *   - повышенная волатильность дополнительно ограничивает набор (только
 *     импульсные/дискреционные) и понижает веса.
 *
 * Выбор победителя по-прежнему делает [com.trading.bot.application.StrategyRunner],
 * но только среди [eligibleStrategyIds], умножая signalStrength на [fitScore].
 */
@Component
class StrategySelector {
    /**
     * Базовая совместимость стратегии с направлением режима.
     * [trend] — вес в тренде (TREND_UP/TREND_DOWN), [range] — в боковике (RANGE).
     */
    private data class DirectionFit(
        val trend: Double,
        val range: Double,
    )

    private val fits: Map<String, DirectionFit> =
        mapOf(
            "TREND_FOLLOWING" to DirectionFit(trend = 1.0, range = 0.0),
            "BREAKOUT" to DirectionFit(trend = 0.8, range = 0.3),
            "SCALPING" to DirectionFit(trend = 0.7, range = 0.4),
            "DISCRETIONARY" to DirectionFit(trend = 0.8, range = 0.7),
            "ARBITRAGE" to DirectionFit(trend = 0.5, range = 0.8),
            "GRID" to DirectionFit(trend = 0.0, range = 1.0),
            "MEAN_REVERSION" to DirectionFit(trend = 0.0, range = 1.0),
            "CNYRUB_TOM" to DirectionFit(trend = 0.3, range = 1.0),
        )

    private val volatilityAllowed: Map<RegimeVolatility, Set<String>> =
        mapOf(
            RegimeVolatility.LOW to fits.keys,
            RegimeVolatility.NORMAL to fits.keys,
            RegimeVolatility.HIGH to setOf("SCALPING", "DISCRETIONARY", "ARBITRAGE", "CNYRUB_TOM"),
            RegimeVolatility.EXTREME to emptySet(),
        )

    /**
     * Множитель уверенности по режиму (0..1). 0 — стратегия несовместима с режимом.
     *
     * @param strategyId стабильный id стратегии (см. [com.trading.bot.domain.strategy.Strategy.id])
     * @param regime per-ticker режим рынка
     * @return вес 0..1
     */
    fun fitScore(
        strategyId: String,
        regime: PerTickerRegime,
    ): Double {
        if (regime.blocksEntry) return 0.0
        val fit = fits[strategyId] ?: return 0.0
        if (strategyId !in volatilityAllowed.getValue(regime.volatility)) return 0.0

        val directionScore =
            when (regime.direction) {
                RegimeDirection.TREND_UP, RegimeDirection.TREND_DOWN -> fit.trend
                RegimeDirection.RANGE -> fit.range
            }
        if (directionScore <= 0.0) return 0.0

        val volatilityFactor =
            if (regime.volatility == RegimeVolatility.HIGH) {
                HIGH_VOLATILITY_FACTOR
            } else {
                1.0
            }
        return (directionScore * volatilityFactor).coerceIn(0.0, 1.0)
    }

    /**
     * Допустимые стратегии для режима (жёсткий фильтр).
     *
     * @param regime per-ticker режим рынка
     * @return множество id стратегий с положительным [fitScore]
     */
    fun eligibleStrategyIds(regime: PerTickerRegime): Set<String> = fits.keys.filterTo(linkedSetOf()) { fitScore(it, regime) > 0.0 }

    private companion object {
        /** Множитель веса при повышенной волатильности (HIGH). */
        const val HIGH_VOLATILITY_FACTOR = 0.7
    }
}
