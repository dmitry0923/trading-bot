package com.trading.bot.backtest

import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Candle
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Симуляция исполнения ордеров в бэктесте.
 *
 * - Комиссия: 0.05% от ОБОРОТА (entry + exit), оборот = цена × количество.
 *   Позиция может иметь quantity > 1, поэтому комиссия считается как
 *   price × quantity × rate (ранее quantity игнорировалось — systematic bias).
 * - Проскальзывание: 0.1% для market-ордеров (акции), в ТИКАХ (пунктах) для
 *   фьючерсов ([FUTURES_SLIPPAGE_TICKS]) — процентное проскальзывание от цены
 *   фьючерса (0.1% Si ≈ 92 пункта) непропорционально велико относительно стопа
 *   в [com.trading.bot.config.RiskConfig.defaultStopLossPoints] = 50 пунктов.
 * - Лотность: округление вниз до целого лота
 * - Исполнение по цене открытия следующей свечи (консервативно)
 */
object SimulatedExecution {
    val COMMISSION_RATE = BigDecimal("0.0005")
    val MARKET_SLIPPAGE_RATE = BigDecimal("0.001")

    /** Проскальзывание фьючерсного market-ордера в тиках (пунктах): 1 тик = priceStep. */
    val FUTURES_SLIPPAGE_TICKS = 1

    data class Fill(
        val price: BigDecimal,
    )

    /**
     * Цена исполнения market-ордера с проскальзыванием 0.1%.
     *
     * @param slippageRate ставка проскальзывания (по умолчанию 0.1%); стресс-прогоны
     *   (roadmap 13.7.8) передают умноженную ставку для оценки чувствительности.
     */
    fun marketFill(
        reference: BigDecimal,
        isBuy: Boolean,
        slippageRate: BigDecimal = MARKET_SLIPPAGE_RATE,
    ): Fill {
        val slip = reference.multiply(slippageRate)
        val price = if (isBuy) reference.add(slip) else reference.subtract(slip)
        return Fill(price)
    }

    /**
     * Цена исполнения с проскальзыванием в ТИКАХ (пунктах) — для фьючерсов:
     * slip = ticks × tickSize (например 1 тик × 0.01 = 0.01 ₽ для Si).
     */
    fun tickFill(
        reference: BigDecimal,
        isBuy: Boolean,
        ticks: Int,
        tickSize: BigDecimal,
    ): Fill {
        val slip = tickSize.multiply(BigDecimal(ticks))
        val price = if (isBuy) reference.add(slip) else reference.subtract(slip)
        return Fill(price)
    }

    /**
     * Комиссия за полный оборот (0.05% от price × quantity).
     *
     * @param quantity количество контрактов/акций (оборот = price × quantity)
     * @param commissionRate ставка комиссии (по умолчанию 0.05%); стресс-прогоны
     *   передают умноженную ставку.
     */
    fun commissionOn(
        price: BigDecimal,
        quantity: Int = 1,
        commissionRate: BigDecimal = COMMISSION_RATE,
    ): BigDecimal =
        price
            .multiply(BigDecimal.valueOf(quantity.toLong()))
            .multiply(commissionRate)
            .setScale(4, RoundingMode.HALF_UP)

    /**
     * Фиксированная комиссия за лот (per-instrument, например CNY_RUB = 10 RUB/лот).
     * В отличие от [commissionOn], не зависит от оборота — реалистична для MOEX
     * с фиксированными тарифами на валютные пары.
     *
     * @param commissionPerLot комиссия за 1 лот в рублях (round-trip)
     * @param lots количество лотов (= quantity / lotSize)
     */
    fun commissionFixed(
        commissionPerLot: BigDecimal,
        lots: Int,
    ): BigDecimal =
        commissionPerLot
            .multiply(BigDecimal(lots))
            .setScale(4, RoundingMode.HALF_UP)

    /**
     * Округление до целого лота (вниз) по лотности инструмента.
     * Если результат меньше 1 лота — 0 (позиция не открывается).
     * При lotSize <= 0 (инструмент не найден) лотность игнорируется.
     */
    fun lotRounded(
        quantity: Int,
        lotSize: Int,
    ): Int {
        if (lotSize <= 0) return quantity
        val lots = quantity / lotSize
        return lots * lotSize
    }

    /**
     * Проверка достижения SL/TP внутри диапазона свечи (intraday high/low).
     *
     * Направление ВАЖНО: для LONG стоп ниже входа, таргет выше (low <= sl / high >= tp);
     * для SHORT наоборот — стоп ВЫШЕ входа, таргет ниже (high >= sl / low <= tp).
     * Раньше функция была зашита под LONG и для шортов `low <= sl` срабатывало почти
     * на каждой свече → все короткие позиции стоп-аутились на первой же свече.
     *
     * Если внутри свечи достигнуты и стоп, и таргет — считаем консервативно (стоп):
     * без внутрисвечной траектории нельзя знать, что сработало раньше.
     */
    fun hitStopOrTarget(
        candle: Candle,
        sl: BigDecimal,
        tp: BigDecimal,
        direction: PositionDirection,
    ): StopTpHit? {
        val high = candle.highPrice
        val low = candle.lowPrice
        return when (direction) {
            PositionDirection.LONG -> {
                when {
                    low <= sl -> StopTpHit.STOP
                    high >= tp -> StopTpHit.TARGET
                    else -> null
                }
            }

            PositionDirection.SHORT -> {
                when {
                    high >= sl -> StopTpHit.STOP
                    low <= tp -> StopTpHit.TARGET
                    else -> null
                }
            }
        }
    }

    enum class StopTpHit { STOP, TARGET }
}
