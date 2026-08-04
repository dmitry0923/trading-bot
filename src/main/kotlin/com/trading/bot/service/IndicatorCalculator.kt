package com.trading.bot.service

import com.trading.bot.model.Candle
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sqrt

/**
 * Утилита расчёта технических индикаторов.
 *
 * - RSI(14), ATR(14), MACD(12,26,9), Bollinger Bands(20, 2σ), EMA
 * - trend: сравнение EMA12 и EMA26 (UP/DOWN/SIDEWAYS)
 * - conclusion: комбинация RSI/BB (перекупленность/перепроданность) + гистограмма MACD
 * - Все методы чисто функциональные и потокобезопасные (без состояния)
 */
object IndicatorCalculator {

    data class Indicators(
        val rsi: Double,
        val atr: Double,
        val macdLine: Double,
        val macdSignal: Double,
        val macdHistogram: Double,
        val bbUpper: BigDecimal,
        val bbMiddle: BigDecimal,
        val bbLower: BigDecimal,
        val trend: String,
        val conclusion: String
    )

    /**
     * Рассчитывает полный набор индикаторов по свечам.
     *
     * @param candles исторические свечи
     * @return Indicators или null, если свечей меньше 30
     */
    fun calculate(candles: List<Candle>): Indicators? {
        if (candles.size < 30) return null
        val closes = candles.map { it.closePrice }
        val rsi = rsi(closes, 14)
        val atr = atr(candles, 14)
        val (macdLine, macdSignal, macdHist) = macd(closes)
        val (bbMiddle, bbUpper, bbLower) = bollinger(closes, 20, 2.0)

        val emaFast = ema(closes, 12).last()
        val emaSlow = ema(closes, 26).last()
        val trend = when {
            emaFast > emaSlow -> "UP"
            emaFast < emaSlow -> "DOWN"
            else -> "SIDEWAYS"
        }
        val conclusion = when {
            rsi < 30 && closes.last() <= bbLower -> "BULLISH"
            rsi > 70 && closes.last() >= bbUpper -> "BEARISH"
            macdHist > 0 -> "BULLISH"
            macdHist < 0 -> "BEARISH"
            else -> "NEUTRAL"
        }

        return Indicators(
            rsi = rsi,
            atr = atr,
            macdLine = macdLine,
            macdSignal = macdSignal,
            macdHistogram = macdHist,
            bbUpper = bbUpper,
            bbMiddle = bbMiddle,
            bbLower = bbLower,
            trend = trend,
            conclusion = conclusion
        )
    }

    /**
     * Индекс относительной силы (RSI) по ценам закрытия.
     *
     * @param closes цены закрытия
     * @param period период RSI (по умолчанию 14)
     * @return RSI от 0 до 100 (50 при недостатке данных)
     */
    fun rsi(closes: List<BigDecimal>, period: Int): Double {
        if (closes.size < period + 1) return 50.0
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val diff = closes[i].toDouble() - closes[i - 1].toDouble()
            if (diff >= 0) gain += diff else loss -= diff
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        for (i in period + 1 until closes.size) {
            val diff = closes[i].toDouble() - closes[i - 1].toDouble()
            val g = if (diff > 0) diff else 0.0
            val l = if (diff < 0) -diff else 0.0
            avgGain = (avgGain * (period - 1) + g) / period
            avgLoss = (avgLoss * (period - 1) + l) / period
        }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    /**
     * Средний истинный диапазон (ATR) по свечам.
     *
     * @param candles исторические свечи
     * @param period период ATR (по умолчанию 14)
     * @return ATR в денежных единицах (0 при недостатке данных)
     */
    fun atr(candles: List<Candle>, period: Int): Double {
        if (candles.size < period + 1) return 0.0
        val trueRanges = (1 until candles.size).map { i ->
            val h = candles[i].highPrice.toDouble()
            val l = candles[i].lowPrice.toDouble()
            val prevC = candles[i - 1].closePrice.toDouble()
            maxOf(h - l, kotlin.math.abs(h - prevC), kotlin.math.abs(l - prevC))
        }
        var a = trueRanges.take(period).average()
        for (i in period until trueRanges.size) {
            a = (a * (period - 1) + trueRanges[i]) / period
        }
        return a
    }

    /**
     * Перцентиль последнего внутрисвечного диапазона (high-low) относительно
     * всех свечей окна. 0 — самый узкий диапазон, 100 — самый широкий.
     * Используется для ATR-бакета семантического кэша LLM.
     */
    fun atrPercentile(candles: List<Candle>): Int {
        if (candles.size < 2) return -1
        val ranges = candles.map { it.highPrice.subtract(it.lowPrice).toDouble() }
        val sorted = ranges.sorted()
        val lastIndex = sorted.indexOf(ranges.last())
        return (lastIndex * 100 / sorted.size).coerceIn(0, 100)
    }

    /**
     * Экспоненциальное скользящее среднее (EMA) по значениям.
     *
     * @param values входной ряд значений
     * @param period период EMA
     * @return список EMA той же длины, что и входной ряд
     */
    fun ema(values: List<BigDecimal>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1)
        val result = ArrayList<Double>()
        var prev = values.first().toDouble()
        result.add(prev)
        for (i in 1 until values.size) {
            prev = values[i].toDouble() * k + prev * (1 - k)
            result.add(prev)
        }
        return result
    }

    private fun emaFromDoubles(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1)
        val result = ArrayList<Double>()
        var prev = values.first()
        result.add(prev)
        for (i in 1 until values.size) {
            prev = values[i] * k + prev * (1 - k)
            result.add(prev)
        }
        return result
    }

    /**
     * MACD (12, 26, 9): линия, сигнал и гистограмма.
     *
     * @param closes цены закрытия
     * @return Triple(macdLine, macdSignal, macdHistogram)
     */
    fun macd(closes: List<BigDecimal>): Triple<Double, Double, Double> {
        val e12 = ema(closes, 12)
        val e26 = ema(closes, 26)
        val macdLine = e12.zip(e26).map { it.first - it.second }
        val signal = emaFromDoubles(macdLine, 9)
        return Triple(macdLine.last(), signal.last(), macdLine.last() - signal.last())
    }

    /**
     * Полосы Боллинджера по ценам закрытия.
     *
     * @param closes цены закрытия
     * @param period период окна (по умолчанию 20)
     * @param mult количество стандартных отклонений (по умолчанию 2.0)
     * @return Triple(средняя, верхняя, нижняя полоса)
     */
    fun bollinger(closes: List<BigDecimal>, period: Int, mult: Double): Triple<BigDecimal, BigDecimal, BigDecimal> {
        val window = closes.takeLast(period).map { it.toDouble() }
        val mid = window.average()
        val variance = window.map { (it - mid) * (it - mid) }.average()
        val sd = sqrt(variance)
        return Triple(
            BigDecimal(mid).setScale(4, RoundingMode.HALF_UP),
            BigDecimal(mid + mult * sd).setScale(4, RoundingMode.HALF_UP),
            BigDecimal(mid - mult * sd).setScale(4, RoundingMode.HALF_UP)
        )
    }
}
