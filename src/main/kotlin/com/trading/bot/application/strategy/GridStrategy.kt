package com.trading.bot.application.strategy

import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.model.StrategyAction
import org.springframework.stereotype.Component
import java.math.RoundingMode

/**
 * Диапазонная (grid) стратегия (детерминированная).
 *
 * Определяет торговый диапазон по максимумам/минимумам окна и входит у его границ:
 * BUY в нижней полосе (нижняя граница + 20% диапазона), SELL в верхней полосе.
 * Внутри диапазона — HOLD. Уверенность растёт с близостью к границе.
 *
 * Решение несёт ТОЛЬКО направление BUY/SELL/HOLD — без размера и стопов
 * (их считают RiskEngine/PositionSizer/OrderBuilder).
 */
@Component
class GridStrategy : Strategy {
    override val id = "GRID"

    override suspend fun evaluate(context: StrategyContext): StrategyDecision {
        val price = context.snapshot.currentPrice
        if (context.candles.size < WINDOW) {
            return StrategyDecision.hold(price, "Not enough candles for range (need $WINDOW)")
        }

        val window = context.candles.takeLast(WINDOW)
        val upper = window.maxOf { it.highPrice }
        val lower = window.minOf { it.lowPrice }
        val range = upper.subtract(lower)
        if (range.signum() <= 0) return StrategyDecision.hold(price, "Flat range (upper=$upper, lower=$lower)")

        val bandWidth = range.multiply(BAND_FRACTION)
        val lowerBand = lower.add(bandWidth)
        val upperBand = upper.subtract(bandWidth)

        val direction =
            when {
                price <= lowerBand -> StrategyAction.BUY
                price >= upperBand -> StrategyAction.SELL
                else -> StrategyAction.HOLD
            }

        if (direction == StrategyAction.HOLD) {
            return StrategyDecision.hold(
                price,
                "Price inside range (lower=$lower, upper=$upper, band=$bandWidth)",
            )
        }

        val proximity =
            if (direction == StrategyAction.BUY) {
                (lowerBand.subtract(price)).divide(range, 4, RoundingMode.HALF_UP).toDouble()
            } else {
                (price.subtract(upperBand)).divide(range, 4, RoundingMode.HALF_UP).toDouble()
            }
        val signalStrength = (0.5 + proximity.coerceIn(0.0, 1.0) * 0.35).coerceIn(0.0, 0.85)
        val reasoning =
            "Price in ${if (direction == StrategyAction.BUY) "lower" else "upper"} band " +
                "of [$lower..$upper] (range=$range) -> $direction"
        return StrategyDecision(direction, price, signalStrength, reasoning)
    }

    private companion object {
        const val WINDOW = 40

        /** Доля диапазона, определяющая «полосу входа» у границ. */
        val BAND_FRACTION = java.math.BigDecimal("0.2")
    }
}
