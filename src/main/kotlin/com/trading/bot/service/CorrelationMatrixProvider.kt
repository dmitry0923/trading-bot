package com.trading.bot.service

import com.trading.bot.domain.risk.PortfolioDataQuality
import com.trading.bot.domain.risk.ResolvedCorrelationMatrix
import com.trading.bot.model.entity.Candle
import org.springframework.stereotype.Service
import kotlin.math.sqrt

/**
 * Общий источник корреляционных данных для риск-систем.
 *
 * Единая реализация коэффициента корреляции Пирсона между ценами закрытия
 * двух тикеров из Redis-кэша свечей. Используется:
 * - [AdaptiveRiskService] (корреляционные фильтры входа);
 * - [com.trading.bot.application.risk.PortfolioRiskEngineImpl] (агрегированный
 *   портфельный риск: дисперсия Markowitz, effectiveN, VaR).
 *
 * Стейтлесс: все данные — из [CandleCacheService].
 */
@Service
class CorrelationMatrixProvider(
    private val candleCache: CandleCacheService,
) {
    /** Минимальное количество свечей для расчёта корреляции. */
    val correlationMinSamples: Int = 30

    /**
     * Коэффициент корреляции Пирсона между ценами закрытия двух тикеров
     * за последние [period] свечей из Redis-кэша.
     *
     * Ряды выравниваются по [com.trading.bot.model.entity.Candle.time] (inner join):
     * при пропущенных барах сравнивать «i-ю с i-й» нельзя — это корреляция разных
     * моментов, а не совместного движения (вход риск-гейтов был бы искажён).
     *
     * @param a первый тикер
     * @param b второй тикер
     * @param timeframe таймфрейм свечей
     * @param period глубина расчёта
     * @return корреляция в [-1, 1] или null, если общих наблюдений меньше
     *   [correlationMinSamples]
     * @throws IllegalArgumentException если [period] меньше [correlationMinSamples]:
     *   без такого периода корреляция заведомо null, и молчаливая нулевая матрица
     *   выдавалась бы за «нет корреляции»
     */
    fun correlationOf(
        a: String,
        b: String,
        timeframe: String = "MINUTE_10",
        period: Int = 50,
    ): Double? {
        requirePeriod(period)
        if (a == b) return 1.0
        val candlesA = candleCache.getRecentCandles(a, timeframe, period)
        val candlesB = candleCache.getRecentCandles(b, timeframe, period)
        return pearson(alignedCloses(candlesA, candlesB))
    }

    /**
     * Пары `(closeA, closeB)` на общих метках времени (inner join по [Candle.time]).
     * Порядок — как в [a]; на корреляцию Пирсона он не влияет.
     */
    internal fun alignedCloses(
        a: List<Candle>,
        b: List<Candle>,
    ): List<Pair<Double, Double>> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val bByTime = b.associate { it.time to it.closePrice.toDouble() }
        return a.mapNotNull { candle ->
            bByTime[candle.time]?.let { closeB -> candle.closePrice.toDouble() to closeB }
        }
    }

    private fun pearson(pairs: List<Pair<Double, Double>>): Double? {
        if (pairs.size < correlationMinSamples) return null
        val n = pairs.size
        val mx = pairs.sumOf { it.first } / n
        val my = pairs.sumOf { it.second } / n
        var num = 0.0
        var dx2 = 0.0
        var dy2 = 0.0
        for ((x, y) in pairs) {
            val dx = x - mx
            val dy = y - my
            num += dx * dy
            dx2 += dx * dx
            dy2 += dy * dy
        }
        if (dx2 == 0.0 || dy2 == 0.0) return null
        return num / sqrt(dx2 * dy2)
    }

    private fun requirePeriod(period: Int) {
        require(period >= correlationMinSamples) {
            "correlation period=$period is below correlationMinSamples=$correlationMinSamples"
        }
    }

    /**
     * Матрица попарных корреляций для списка тикеров (верхний треугольник).
     *
     * @return Map[ticker -> Map[ticker -> корреляция | null]]
     * @throws IllegalArgumentException если [period] меньше [correlationMinSamples]
     *   (иначе матрица была бы целиком из null, а HTTP-ответ — 200)
     */
    fun correlations(
        tickers: List<String>,
        timeframe: String = "MINUTE_10",
        period: Int = 50,
    ): Map<String, Map<String, Double?>> {
        requirePeriod(period)
        val distinct = tickers.distinct()
        return distinct.associateWith { a ->
            distinct.associateWith { b ->
                if (a == b) 1.0 else correlationOf(a, b, timeframe, period)
            }
        }
    }

    /**
     * Разрешённая матрица корреляций для списка тикеров (индекс = позиция в списке)
     * с консервативным fallback: отсутствующая пара заменяется максимальной
     * наблюдаемой корреляцией (без данных — 0). Дубли тикеров сохраняются
     * (диагональ = 1.0).
     *
     * @return матрица размером tickers.size × tickers.size
     */
    fun resolved(
        tickers: List<String>,
        timeframe: String = "MINUTE_10",
        period: Int = 50,
    ): List<List<Double>> {
        val distinct = tickers.distinct()
        val raw = correlations(distinct, timeframe, period)
        val observed = distinct.flatMap { a -> distinct.map { b -> raw[a]?.get(b) } }.filterNotNull()
        val fallback = observed.maxOrNull() ?: 0.0
        return tickers.map { a ->
            tickers.map { b -> if (a == b) 1.0 else raw[a]?.get(b) ?: fallback }
        }
    }

    /**
     * Разрешённая матрица корреляций с качеством данных.
     *
     * Аналог [resolved], но дополнительно сообщает, есть ли в матрице пары без
     * данных: [PortfolioDataQuality.INSUFFICIENT] — как минимум одна пара
     * заменена консервативным fallback, [PortfolioDataQuality.KNOWN] — все пары
     * рассчитаны по данным. Используется портфельным риск-движком для
     * масштабирования размера при неполных данных.
     */
    fun resolvedWithQuality(
        tickers: List<String>,
        timeframe: String = "MINUTE_10",
        period: Int = 50,
    ): ResolvedCorrelationMatrix {
        val distinct = tickers.distinct()
        val raw = correlations(distinct, timeframe, period)
        val missingPair = distinct.any { a -> distinct.any { b -> a != b && raw[a]?.get(b) == null } }
        val observed = distinct.flatMap { a -> distinct.map { b -> raw[a]?.get(b) } }.filterNotNull()
        val fallback = observed.maxOrNull() ?: 0.0
        val matrix =
            tickers.map { a ->
                tickers.map { b -> if (a == b) 1.0 else raw[a]?.get(b) ?: fallback }
            }
        val quality =
            if (missingPair) PortfolioDataQuality.INSUFFICIENT else PortfolioDataQuality.KNOWN
        return ResolvedCorrelationMatrix(matrix, quality)
    }
}
