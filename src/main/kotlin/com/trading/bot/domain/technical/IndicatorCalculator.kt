package com.trading.bot.domain.technical

import com.trading.bot.model.entity.Candle
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
    private const val MACD_SIGNAL_EMA_PERIOD = 9

    /**
     * Минимально значимая σ в % от VWAP: ниже этого значения сессия считается
     * безвольной (flat). Нужен из-за float-шума: на полностью плоской сессии
     * σ ≈ 1e-14 вместо 0, и деление на неё даёт ложные отклонения в тысячи σ.
     */
    const val MIN_MEANINGFUL_VWAP_SIGMA_PERCENT = 1e-6

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
        val conclusion: String,
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
        val trend =
            when {
                emaFast > emaSlow -> "UP"
                emaFast < emaSlow -> "DOWN"
                else -> "SIDEWAYS"
            }
        val conclusion =
            when {
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
            conclusion = conclusion,
        )
    }

    /**
     * Индекс относительной силы (RSI) по ценам закрытия.
     *
     * @param closes цены закрытия
     * @param period период RSI (по умолчанию 14)
     * @return RSI от 0 до 100 (50 при недостатке данных)
     */
    fun rsi(
        closes: List<BigDecimal>,
        period: Int,
    ): Double {
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
    fun atr(
        candles: List<Candle>,
        period: Int,
    ): Double {
        if (candles.size < period + 1) return 0.0
        val trueRanges =
            (1 until candles.size).map { i ->
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
    fun ema(
        values: List<BigDecimal>,
        period: Int,
    ): List<Double> {
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

    private fun emaFromDoubles(values: List<Double>): List<Double> {
        if (values.isEmpty()) return emptyList()
        val k = 2.0 / (MACD_SIGNAL_EMA_PERIOD + 1)
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
        val signal = emaFromDoubles(macdLine)
        return Triple(macdLine.last(), signal.last(), macdLine.last() - signal.last())
    }

    /**
     * VWAP (Volume Weighted Average Price) с сессионным сбросом.
     *
     * Типичный (anchored) VWAP торговой сессии: накопление объёмов и
     * (typical price × объём) сбрасывается при смене даты свечи. Для
     * фьючерсного контракта сессия = один торговый день MOEX.
     *
     * @param candles исторические свечи (ожидаются в хронологическом порядке)
     * @return значение VWAP последней свечи или null, если свечей нет
     */
    fun vwap(candles: List<Candle>): Double? {
        if (candles.isEmpty()) return null
        var cumVolume = 0.0
        var cumPv = 0.0
        var currentDay: java.time.LocalDate? = null
        var last = 0.0
        for (c in candles) {
            val day = c.time.toLocalDate()
            if (currentDay != day) {
                currentDay = day
                cumVolume = 0.0
                cumPv = 0.0
            }
            val typical =
                (c.highPrice.toDouble() + c.lowPrice.toDouble() + c.closePrice.toDouble()) / 3.0
            val vol = c.volume.toDouble()
            cumVolume += vol
            cumPv += typical * vol
            last = if (cumVolume > 0.0) cumPv / cumVolume else typical
        }
        return last
    }

    /**
     * Стандартное отклонение (σ) typical price, накопленного в текущей сессии,
     * в процентах от VWAP. Используется как «ширина» коридора mean reversion:
     * вход по отклонению ≥ [deviationSigma]σ, цель — возврат к VWAP.
     *
     * @param candles исторические свечи текущей сессии
     * @return σ в % от VWAP либо null при недостатке данных (< 2 свечей)
     */
    fun vwapStdDevPercent(candles: List<Candle>): Double? {
        if (candles.size < 2) return null
        val typicals =
            candles.map {
                (it.highPrice.toDouble() + it.lowPrice.toDouble() + it.closePrice.toDouble()) / 3.0
            }
        val mean = typicals.average()
        val variance = typicals.map { (it - mean) * (it - mean) }.average()
        val sd = sqrt(variance)
        return if (mean > 0.0) sd / mean * 100.0 else null
    }

    /**
     * ADX (Average Directional Index) по Уайлдеру — трендовый фильтр для
     * контрттрендовых (mean reversion) входов: вход разрешён при ADX < порога.
     *
     * @param candles исторические свечи
     * @param period период (по умолчанию 14)
     * @return ADX; 0 при недостатке данных (нет тренда → фильтр не блокирует)
     */
    fun adx(
        candles: List<Candle>,
        period: Int = 14,
    ): Double {
        if (candles.size < period * 2) return 0.0
        val tr = ArrayList<Double>()
        val plusDm = ArrayList<Double>()
        val minusDm = ArrayList<Double>()
        for (i in 1 until candles.size) {
            val h = candles[i].highPrice.toDouble()
            val l = candles[i].lowPrice.toDouble()
            val prevH = candles[i - 1].highPrice.toDouble()
            val prevL = candles[i - 1].lowPrice.toDouble()
            val prevC = candles[i - 1].closePrice.toDouble()
            tr.add(maxOf(h - l, kotlin.math.abs(h - prevC), kotlin.math.abs(l - prevC)))
            val upMove = h - prevH
            val downMove = prevL - l
            plusDm.add(if (upMove > downMove && upMove > 0) upMove else 0.0)
            minusDm.add(if (downMove > upMove && downMove > 0) downMove else 0.0)
        }

        fun wilder(values: List<Double>): List<Double> {
            var acc = values.take(period).sum()
            val out = ArrayList<Double>()
            out.add(acc)
            for (i in period until values.size) {
                acc = acc - acc / period + values[i]
                out.add(acc)
            }
            return out
        }
        val trS = wilder(tr)
        val plusS = wilder(plusDm)
        val minusS = wilder(minusDm)
        val dx = ArrayList<Double>()
        for (i in 0 until minOf(trS.size, plusS.size, minusS.size)) {
            if (trS[i] <= 0.0) {
                dx.add(0.0)
            } else {
                val pdi = 100.0 * plusS[i] / trS[i]
                val mdi = 100.0 * minusS[i] / trS[i]
                val sum = pdi + mdi
                dx.add(if (sum > 0.0) 100.0 * kotlin.math.abs(pdi - mdi) / sum else 0.0)
            }
        }
        if (dx.size < period) return 0.0
        var adx = dx.take(period).average()
        for (i in period until dx.size) {
            adx = (adx * (period - 1) + dx[i]) / period
        }
        return adx
    }

    /**
     * Полосы Боллинджера по ценам закрытия.
     *
     * @param closes цены закрытия
     * @param period период окна (по умолчанию 20)
     * @param mult количество стандартных отклонений (по умолчанию 2.0)
     * @return Triple(средняя, верхняя, нижняя полоса)
     */
    fun bollinger(
        closes: List<BigDecimal>,
        period: Int,
        mult: Double,
    ): Triple<BigDecimal, BigDecimal, BigDecimal> {
        val window = closes.takeLast(period).map { it.toDouble() }
        val mid = window.average()
        val variance = window.map { (it - mid) * (it - mid) }.average()
        val sd = sqrt(variance)
        return Triple(
            BigDecimal(mid).setScale(4, RoundingMode.HALF_UP),
            BigDecimal(mid + mult * sd).setScale(4, RoundingMode.HALF_UP),
            BigDecimal(mid - mult * sd).setScale(4, RoundingMode.HALF_UP),
        )
    }

    /**
     * Канал Кельтнера: EMA(period) ± mult·ATR(atrPeriod).
     *
     * Отличие от Боллинджера: ширина задаётся волатильностью (ATR), а не
     * дисперсией цены — отсюда «сжатие Боллинджера внутрь Кельтнера»
     * ([isSqueeze]) как признак низкой волатильности перед импульсом.
     *
     * @return null при нехватке данных (нужно ≥ atrPeriod + 1 баров)
     */
    fun keltner(
        candles: List<Candle>,
        emaPeriod: Int = KELTNER_EMA_PERIOD,
        atrPeriod: Int = KELTNER_ATR_PERIOD,
        mult: Double = KELTNER_MULT,
    ): KeltnerChannel? {
        if (candles.size < maxOf(emaPeriod, atrPeriod + 1)) return null
        val closes = candles.map { it.closePrice }
        val middle = ema(closes, emaPeriod).last()
        val width = mult * atr(candles, atrPeriod)
        if (!middle.isFinite() || !width.isFinite() || middle <= 0.0 || width <= 0.0) return null
        return KeltnerChannel(middle = middle, upper = middle + width, lower = middle - width)
    }

    /**
     * Сжатие волатильности (squeeze): обе полосы Боллинджера лежат ВНУТРИ канала
     * Кельтнера. Фильтр входа стратегии №8 («Bollinger Squeeze Breakout»):
     * вход разрешается на пробое из сжатия, а не внутри него.
     *
     * @return true — сжатие, false — сжатия нет, null — данных не хватает
     *   (fail-closed на стороне фильтра)
     */
    fun isSqueeze(
        candles: List<Candle>,
        bbPeriod: Int = BOLLINGER_SQUEEZE_PERIOD,
        bbMult: Double = BOLLINGER_SQUEEZE_MULT,
        kcEmaPeriod: Int = KELTNER_EMA_PERIOD,
        kcAtrPeriod: Int = KELTNER_ATR_PERIOD,
        kcMult: Double = KELTNER_MULT,
    ): Boolean? {
        if (candles.size < maxOf(bbPeriod, kcAtrPeriod + 1)) return null
        val closes = candles.map { it.closePrice }
        val (_, bbUpper, bbLower) = bollinger(closes, bbPeriod, bbMult)
        val kc = keltner(candles, kcEmaPeriod, kcAtrPeriod, kcMult) ?: return null
        return bbUpper.toDouble() < kc.upper && bbLower.toDouble() > kc.lower
    }

    /** Канал Кельтнера (средняя, верхняя, нижняя границы в цене). */
    data class KeltnerChannel(
        val middle: Double,
        val upper: Double,
        val lower: Double,
    )

    /** Периоды и множители по умолчанию для канала Кельтнера. */
    const val KELTNER_EMA_PERIOD = 20

    /** Период ATR для канала Кельтнера. */
    const val KELTNER_ATR_PERIOD = 10

    /** Ширина канала Кельтнера в ATR. */
    const val KELTNER_MULT = 1.5

    /** Окно Боллинджера для детекции сжатия. */
    const val BOLLINGER_SQUEEZE_PERIOD = 20

    /** Множитель σ Боллинджера для детекции сжатия. */
    const val BOLLINGER_SQUEEZE_MULT = 2.0
}
