package com.trading.bot.backtest

import kotlin.random.Random

/**
 * Результат оценки статистической значимости edge'а стратегии по сделкам.
 *
 * Отвечает на вопрос «прибыль статистически значима или это шум?» через
 * bootstrap-ресемплинг P&L сделок. При малом числе сделок (типично для
 * калибровок 10..50) даже положительная средняя сделка может быть чистой
 * случайностью — этот анализ даёт 95% доверительный интервал среднего
 * P&L и вероятность, что истинный edge неположительный (no-edge).
 */
data class TradeSignificanceResult(
    /** Число сделок, по которым считалась статистика. */
    val tradeCount: Int,
    /** Фактическая средняя P&L на сделку. */
    val meanTrade: Double,
    /** Нижняя граница 95% доверительного интервала среднего P&L (2.5 перцентиль bootstrap). */
    val ci95Low: Double,
    /** Верхняя граница 95% доверительного интервала среднего P&L (97.5 перцентиль). */
    val ci95High: Double,
    /** Доля bootstrap-средних с P&L <= 0 (0..1) — аналог одностороннего p-value. */
    val probabilityOfNoEdge: Double,
    /** true, если edge статистически значим: probabilityOfNoEdge <= 0.05 (95%). */
    val edgeStatisticallySignificant: Boolean,
    /** Число bootstrap-итераций. */
    val simulations: Int,
) {
    companion object {
        /** Пустой результат при недостатке сделок (< 2) — edge не оценим. */
        val empty =
            TradeSignificanceResult(
                tradeCount = 0,
                meanTrade = 0.0,
                ci95Low = 0.0,
                ci95High = 0.0,
                probabilityOfNoEdge = 1.0,
                edgeStatisticallySignificant = false,
                simulations = 0,
            )
    }
}

/**
 * Чистая математика статистической значимости (без Spring — unit-тестируется).
 *
 * Bootstrap с возвращением по P&L сделок: каждая итерация тянет `n` случайных
 * сделок (с повторениями) и усредняет их; распределение этих средних аппроксимирует
 * распределение истинного среднего P&L. 95% CI — перцентильный метод;
 * `probabilityOfNoEdge` — доля bootstrap-средних, не превышающих ноль.
 */
object TradeSignificance {
    fun bootstrap(
        tradeReturns: List<Double>,
        simulations: Int = 2000,
        seed: Long = 42,
    ): TradeSignificanceResult {
        if (tradeReturns.size < 2 || simulations <= 0) return TradeSignificanceResult.empty

        val n = tradeReturns.size
        val rnd = Random(seed)
        val bsMeans = DoubleArray(simulations)
        for (s in 0 until simulations) {
            var sum = 0.0
            for (i in 0 until n) {
                sum += tradeReturns[rnd.nextInt(n)]
            }
            bsMeans[s] = sum / n
        }
        bsMeans.sort()

        fun percentile(q: Double): Double {
            val idx = (q * simulations).toInt().coerceIn(0, simulations - 1)
            return bsMeans[idx]
        }

        val meanActual = tradeReturns.average()
        val ciLow = percentile(0.025)
        val ciHigh = percentile(0.975)
        val probabilityOfNoEdge = bsMeans.count { it <= 0.0 }.toDouble() / simulations

        return TradeSignificanceResult(
            tradeCount = n,
            meanTrade = meanActual,
            ci95Low = ciLow,
            ci95High = ciHigh,
            probabilityOfNoEdge = probabilityOfNoEdge,
            edgeStatisticallySignificant = probabilityOfNoEdge <= 0.05,
            simulations = simulations,
        )
    }
}
