package com.trading.bot.domain.ml

import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.model.entity.Candle
import java.math.BigDecimal
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Feature-инжиниринг для ML-обучения (roadmap v2.4, раздел 13.11).
 *
 * Признаки считаются на момент ВХОДА в позицию по свечам, закрытым ДО входа
 * (без lookahead): индикаторы [IndicatorCalculator] + производные признаки.
 * Все признаки нормированы относительно цены/объёма — пригодны для обучения
 * на разных тикерах без дополнительной нормализации.
 *
 * Методы чисто функциональные и потокобезопасные (без состояния).
 */
object MlFeatureExtractor {
    private const val MIN_BARS = 30

    data class Features(
        val rsi14: Double,
        val atrPercent: Double,
        val macdHistogramPercent: Double,
        val bbPercentB: Double,
        val emaSlopePercent: Double,
        val volatility20Percent: Double,
        val return3: Double,
        val return10: Double,
        val return20: Double,
    )

    /**
     * @param candles свечи тикера до момента входа (включительно), по возрастанию времени
     * @param lookbackBars размер окна признаков (количество последних свечей)
     * @return признаки или null, если данных недостаточно (< 30 свечей для индикаторов)
     */
    fun extract(
        candles: List<Candle>,
        lookbackBars: Int,
    ): Features? {
        val window = candles.takeLast(lookbackBars.coerceAtLeast(MIN_BARS))
        val ind = IndicatorCalculator.calculate(window) ?: return null
        val closes = window.map { it.closePrice }
        val close = closes.last().toDouble()
        if (close <= 0.0) return null

        val emaFast = IndicatorCalculator.ema(closes, 12).last()
        val emaSlow = IndicatorCalculator.ema(closes, 26).last()
        val bbRange = ind.bbUpper.toDouble() - ind.bbLower.toDouble()

        return Features(
            rsi14 = ind.rsi,
            atrPercent = if (close > 0.0) ind.atr / close * 100.0 else 0.0,
            macdHistogramPercent = ind.macdHistogram / close * 100.0,
            bbPercentB = if (bbRange > 0.0) (close - ind.bbLower.toDouble()) / bbRange * 100.0 else 50.0,
            emaSlopePercent = (emaFast - emaSlow) / close * 100.0,
            volatility20Percent = volatility(closes) * 100.0,
            return3 = returnOver(closes, 3, close) ?: 0.0,
            return10 = returnOver(closes, 10, close) ?: 0.0,
            return20 = returnOver(closes, 20, close) ?: 0.0,
        )
    }

    private fun returnOver(
        closes: List<BigDecimal>,
        bars: Int,
        close: Double,
    ): Double? {
        if (closes.size <= bars) return null
        val base = closes[closes.size - 1 - bars].toDouble()
        if (base <= 0.0) return null
        return (close / base - 1.0) * 100.0
    }

    /** СКО лог-доходностей последних 20 закрытий (в долях, *100 для %). */
    private fun volatility(closes: List<BigDecimal>): Double {
        val returns =
            closes.takeLast(21).zipWithNext { a, b ->
                val from = a.toDouble()
                val to = b.toDouble()
                if (from > 0.0 && to > 0.0) ln(to / from) else 0.0
            }
        if (returns.size < 2) return 0.0
        val mean = returns.average()
        val variance = returns.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }
}
