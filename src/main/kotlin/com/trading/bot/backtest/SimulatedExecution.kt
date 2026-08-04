package com.trading.bot.backtest

import com.trading.bot.model.StrategyAction
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Симуляция исполнения ордеров в бэктесте.
 *
 * - Комиссия: 0.05% от оборота (entry + exit)
 * - Проскальзывание: 0.1% для market-ордеров, 0 для limit
 * - Лотность: округление вниз до целого лота
 * - Исполнение по цене открытия следующей свечи (консервативно)
 */
object SimulatedExecution {
    val COMMISSION_RATE = BigDecimal("0.0005")
    val MARKET_SLIPPAGE_RATE = BigDecimal("0.001")

    data class Fill(
        val price: BigDecimal,
        val commission: BigDecimal,
    )

    /** Цена исполнения limit-ордера с учётом лимита (исполнение ровно по лимиту или лучше). */
    fun limitFill(
        limitPrice: BigDecimal,
        nextOpen: BigDecimal,
        isBuy: Boolean,
    ): Fill {
        val price = if (isBuy) nextOpen.min(limitPrice) else nextOpen.max(limitPrice)
        return Fill(price, commissionOn(price))
    }

    /** Цена исполнения market-ордера с проскальзыванием 0.1%. */
    fun marketFill(
        reference: BigDecimal,
        isBuy: Boolean,
    ): Fill {
        val slip = reference.multiply(MARKET_SLIPPAGE_RATE)
        val price = if (isBuy) reference.add(slip) else reference.subtract(slip)
        return Fill(price, commissionOn(price))
    }

    fun commissionOn(
        price: BigDecimal,
        quantity: Int = 1,
    ): BigDecimal {
        require(quantity >= 0) { "quantity must not be negative" }
        return price
            .multiply(quantity.toBigDecimal())
            .multiply(COMMISSION_RATE)
            .setScale(4, RoundingMode.HALF_UP)
    }

    /**
     * Округление до целого лота (вниз). Если меньше 1 лота — 0 (позиция не открывается).
     */
    fun lotRounded(quantity: Int): Int = if (quantity < 1) 0 else quantity

    /**
     * Проверка достижения SL/TP внутри диапазона свечи.
     *
     * Если одна свеча задела обе границы, порядок сделок внутри OHLC неизвестен,
     * поэтому используется консервативный вариант: сначала срабатывает стоп.
     */
    fun hitStopOrTarget(
        candle: com.trading.bot.model.Candle,
        direction: com.trading.bot.model.PositionDirection,
        sl: BigDecimal,
        tp: BigDecimal,
    ): StopTpHit? {
        val stopHit =
            when (direction) {
                com.trading.bot.model.PositionDirection.LONG -> candle.lowPrice <= sl
                com.trading.bot.model.PositionDirection.SHORT -> candle.highPrice >= sl
            }
        val targetHit =
            when (direction) {
                com.trading.bot.model.PositionDirection.LONG -> candle.highPrice >= tp
                com.trading.bot.model.PositionDirection.SHORT -> candle.lowPrice <= tp
            }
        return when {
            stopHit -> StopTpHit.STOP
            targetHit -> StopTpHit.TARGET
            else -> null
        }
    }

    enum class StopTpHit { STOP, TARGET }
}
