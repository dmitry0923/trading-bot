package com.trading.bot.domain.risk

import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Детерминированные правила выхода (SL/TP/trailing) — чистые функции без
 * сервисов и конфигураций. Используются мониторингом позиций (акции и фьючерсы).
 */
object ExitRules {
    fun shouldCloseBySL(
        pos: Position,
        price: BigDecimal,
    ): Boolean =
        when (pos.direction) {
            PositionDirection.LONG -> pos.stopLoss != null && price <= pos.stopLoss
            PositionDirection.SHORT -> pos.stopLoss != null && price >= pos.stopLoss
        }

    fun shouldCloseByTP(
        pos: Position,
        price: BigDecimal,
    ): Boolean =
        when (pos.direction) {
            PositionDirection.LONG -> pos.takeProfit != null && price >= pos.takeProfit
            PositionDirection.SHORT -> pos.takeProfit != null && price <= pos.takeProfit
        }

    fun shouldCloseByTrailing(
        pos: Position,
        price: BigDecimal,
    ): Boolean {
        val trailing = pos.trailingStopPrice ?: return false
        return when (pos.direction) {
            PositionDirection.LONG -> price <= trailing
            PositionDirection.SHORT -> price >= trailing
        }
    }

    /**
     * Эффективный уровень биржевого стоп-лосса: жёсткий [Position.stopLoss] либо
     * трейлинг-стоп [Position.trailingStopPrice], если он «строже» (для LONG — выше,
     * для SHORT — ниже). Стоп-заявка на бирже выставляется именно на этот уровень.
     */
    fun effectiveSl(pos: Position): BigDecimal? {
        val hard = pos.stopLoss ?: return null
        val trailing = pos.trailingStopPrice ?: return hard
        return when (pos.direction) {
            PositionDirection.LONG -> if (trailing > hard) trailing else hard
            PositionDirection.SHORT -> if (trailing < hard) trailing else hard
        }
    }

    /**
     * Покрывает ли биржевая стоп-заявка [effectiveSl] (уровень заявки актуален).
     * Если да — локальный мониторинг НЕ закрывает позицию по SL/trailing
     * сам (биржа сделает это при пересечении), иначе — двойное закрытие.
     */
    fun exchangeSlCovers(pos: Position): Boolean {
        if (pos.slOrderId == null) return false
        val level = pos.slOrderPrice ?: return false
        val effSl = effectiveSl(pos) ?: return false
        return level.compareTo(effSl) == 0
    }

    /**
     * Покрывает ли биржевая тейк-заявка [Position.takeProfit] (уровень актуален).
     * Если да — локальный мониторинг НЕ закрывает по TP сам.
     */
    fun exchangeTpCovers(pos: Position): Boolean {
        if (pos.tpOrderId == null) return false
        val level = pos.tpOrderPrice ?: return false
        val tp = pos.takeProfit ?: return false
        return level.compareTo(tp) == 0
    }

    /**
     * Цена стоп-лосса по проценту от цены входа (акции).
     */
    fun calcSL(
        entryPrice: BigDecimal,
        direction: PositionDirection,
        percent: Double,
    ): BigDecimal {
        val p = BigDecimal(percent.toString()).divide(BigDecimal("100"))
        return when (direction) {
            PositionDirection.LONG -> entryPrice.multiply(BigDecimal.ONE.subtract(p)).setScale(2, RoundingMode.HALF_UP)
            PositionDirection.SHORT -> entryPrice.multiply(BigDecimal.ONE.add(p)).setScale(2, RoundingMode.HALF_UP)
        }
    }

    /**
     * Цена тейк-профита по проценту от цены входа (акции).
     */
    fun calcTP(
        entryPrice: BigDecimal,
        direction: PositionDirection,
        percent: Double,
    ): BigDecimal {
        val p = BigDecimal(percent.toString()).divide(BigDecimal("100"))
        return when (direction) {
            PositionDirection.LONG -> entryPrice.multiply(BigDecimal.ONE.add(p)).setScale(2, RoundingMode.HALF_UP)
            PositionDirection.SHORT -> entryPrice.multiply(BigDecimal.ONE.subtract(p)).setScale(2, RoundingMode.HALF_UP)
        }
    }

    /**
     * Подтягивание трейлинг-стопа по текущей цене (акции).
     */
    fun updateTrailingStop(
        pos: Position,
        price: BigDecimal,
        percent: Double,
    ) {
        val p = BigDecimal(percent.toString()).divide(BigDecimal("100"))
        val newStop =
            when (pos.direction) {
                PositionDirection.LONG -> price.multiply(BigDecimal.ONE.subtract(p))
                PositionDirection.SHORT -> price.multiply(BigDecimal.ONE.add(p))
            }
        pos.trailingStopPrice = newStop.setScale(2, RoundingMode.HALF_UP)
    }

    /**
     * Подтягивание трейлинг-стопа для фьючерсов с учётом вариационной маржи.
     *
     * - Считает variationMargin: (price - entry) * qty * pointValue (LONG), знак
     *   обратный для SHORT.
     * - Двигает trailing stop только в прибыль (vm > 0) и только в «улучшающую» сторону.
     * - Никогда не ослабляет ниже жёсткого stopLoss.
     */
    fun updateFuturesTrailingStop(
        pos: Position,
        price: BigDecimal,
        percent: Double,
        pointValue: BigDecimal,
    ) {
        val qty = BigDecimal(pos.quantity)
        val variationMargin =
            when (pos.direction) {
                PositionDirection.LONG -> {
                    price.subtract(pos.entryPrice).multiply(pointValue).multiply(qty)
                }

                PositionDirection.SHORT -> {
                    pos.entryPrice
                        .subtract(price)
                        .multiply(pointValue)
                        .multiply(qty)
                }
            }
        pos.variationMargin = variationMargin
        if (variationMargin <= BigDecimal.ZERO) return

        val p = BigDecimal(percent.toString()).divide(BigDecimal("100"))
        var candidate =
            when (pos.direction) {
                PositionDirection.LONG -> price.multiply(BigDecimal.ONE.subtract(p))
                PositionDirection.SHORT -> price.multiply(BigDecimal.ONE.add(p))
            }

        // Не ослабляем ниже жёсткого стопа
        pos.stopLoss?.let { hardStop ->
            candidate =
                when (pos.direction) {
                    PositionDirection.LONG -> if (candidate < hardStop) hardStop else candidate
                    PositionDirection.SHORT -> if (candidate > hardStop) hardStop else candidate
                }
        }

        val currentStop = pos.trailingStopPrice
        val improved =
            when (pos.direction) {
                PositionDirection.LONG -> currentStop == null || candidate > currentStop
                PositionDirection.SHORT -> currentStop == null || candidate < currentStop
            }
        if (improved) {
            pos.trailingStopPrice = candidate.setScale(4, RoundingMode.HALF_UP)
        }
    }
}
