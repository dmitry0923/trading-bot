package com.trading.bot.backtest

import com.trading.bot.model.Candle
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

    /** Цена исполнения market-ордера с проскальзыванием 0.1%. */
    fun marketFill(
        reference: BigDecimal,
        isBuy: Boolean,
    ): Fill {
        val slip = reference.multiply(MARKET_SLIPPAGE_RATE)
        val price = if (isBuy) reference.add(slip) else reference.subtract(slip)
        return Fill(price, commissionOn(price))
    }

    fun commissionOn(price: BigDecimal): BigDecimal = price.multiply(COMMISSION_RATE).setScale(4, RoundingMode.HALF_UP)

    /**
     * Округление до целого лота (вниз). Если меньше 1 лота — 0 (позиция не открывается).
     */
    fun lotRounded(quantity: Int): Int = if (quantity < 1) 0 else quantity

    /** Проверка достижения SL/TP внутри диапазона свечи (intraday high/low). */
    fun hitStopOrTarget(
        candle: Candle,
        sl: BigDecimal,
        tp: BigDecimal,
    ): StopTpHit? {
        val high = candle.highPrice
        val low = candle.lowPrice
        return when {
            low <= sl -> StopTpHit.STOP
            high >= tp -> StopTpHit.TARGET
            else -> null
        }
    }

    enum class StopTpHit { STOP, TARGET }
}
