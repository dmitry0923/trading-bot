package com.trading.bot.domain.risk

import com.trading.bot.model.entity.Candle
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Чистая математика ATR (без Spring/кэша) — единый источник истины для live
 * ([com.trading.bot.service.CandleCacheService.calculateAtr]) и backtest
 * ([com.trading.bot.backtest.BacktestEngine]).
 *
 * TR(i) = max(high-low, |high-prevClose|, |low-prevClose|); ATR = среднее TR по
 * последним `period` диапазонам. Требуется минимум `period + 1` свечей.
 */
object Atr {
    fun calculate(
        candles: List<Candle>,
        period: Int = 14,
    ): BigDecimal? {
        if (period < 1 || candles.size < period + 1) return null
        var sum = BigDecimal.ZERO
        for (i in candles.size - period until candles.size) {
            val c = candles[i]
            val prevClose = candles[i - 1].closePrice
            val range = c.highPrice.subtract(c.lowPrice)
            val highGap = c.highPrice.subtract(prevClose).abs()
            val lowGap = c.lowPrice.subtract(prevClose).abs()
            val tr = listOf(range, highGap, lowGap).maxByOrNull { it } ?: BigDecimal.ZERO
            sum = sum.add(tr)
        }
        return sum.divide(BigDecimal(period), 4, RoundingMode.HALF_UP)
    }

    /**
     * Дистанция стоп-лосса в пунктах для фьючерса: ATR × multiplier, где ATR
     * переводится из цены в пункты (atr / priceStep). Результат ограничивается
     * [minPoints]..[maxPoints]; null — если данные не позволяют расчёт (тогда
     * вызывающий использует фиксированный дефолтный стоп).
     */
    fun stopPoints(
        atr: BigDecimal,
        priceStep: BigDecimal,
        multiplier: Double,
        minPoints: Int,
        maxPoints: Int,
    ): Int? {
        if (atr <= BigDecimal.ZERO || priceStep <= BigDecimal.ZERO || multiplier <= 0.0) return null
        val points = atr.divide(priceStep, 0, RoundingMode.HALF_UP).toInt() * multiplier
        return points.toInt().coerceIn(minPoints, maxPoints)
    }
}
