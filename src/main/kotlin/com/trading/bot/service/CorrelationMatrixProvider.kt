package com.trading.bot.service

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
     * @param a первый тикер
     * @param b второй тикер
     * @param timeframe таймфрейм свечей
     * @param period глубина расчёта
     * @return корреляция в [-1, 1] или null, если данных недостаточно
     */
    fun correlationOf(
        a: String,
        b: String,
        timeframe: String = "MINUTE_10",
        period: Int = 50,
    ): Double? {
        if (a == b) return 1.0
        val x = candleCache.getRecentCandles(a, timeframe, period).map { it.closePrice.toDouble() }
        val y = candleCache.getRecentCandles(b, timeframe, period).map { it.closePrice.toDouble() }
        if (x.size < correlationMinSamples || y.size < correlationMinSamples) return null
        val n = minOf(x.size, y.size)
        val xs = x.takeLast(n)
        val ys = y.takeLast(n)
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var dx2 = 0.0
        var dy2 = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            num += dx * dy
            dx2 += dx * dx
            dy2 += dy * dy
        }
        if (dx2 == 0.0 || dy2 == 0.0) return null
        return num / sqrt(dx2 * dy2)
    }

    /**
     * Матрица попарных корреляций для списка тикеров (верхний треугольник).
     *
     * @return Map[ticker -> Map[ticker -> корреляция | null]]
     */
    fun correlations(
        tickers: List<String>,
        timeframe: String = "MINUTE_10",
        period: Int = 50,
    ): Map<String, Map<String, Double?>> {
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
}
