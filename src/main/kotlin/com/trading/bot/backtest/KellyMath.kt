package com.trading.bot.backtest

import kotlin.math.sqrt

/**
 * Чистая математика Kelly criterion — без Spring, без БД, без метрик.
 *
 * Извлечена из [com.trading.bot.service.AdaptiveRiskService] для переиспользования
 * в бэктесте (BacktestRiskSimulator) и потенциально в unit-тестах live-кода.
 *
 * Все функции — stateless, детерминированы по входным параметрам.
 */
object KellyMath {
    /**
     * Wilson lower bound для win rate — консервативный шринкейдж при малой выборке.
     *
     * p_lower = (p + z²/2n - z*sqrt((p(1-p) + z²/4n)/n)) / (1 + z²/n)
     *
     * Защита от «галлюцинирующего» Kelly: win rate из 5-15 сделок завышен,
     * нижняя граница интервала приближает его к 50% при n -> мал.
     *
     * @param p сырой win rate (0..1)
     * @param n количество сделок
     * @param z z-score (1.0 = ~84% односторонний интервал)
     * @return нижняя граница Wilson-интервала (0..1)
     */
    fun wilsonLowerBound(
        p: Double,
        n: Int,
        z: Double,
    ): Double {
        if (n <= 0) return 0.0
        val pNorm = p.coerceIn(0.0, 1.0)
        val z2 = z * z
        val center = pNorm + z2 / (2 * n)
        val margin = z * sqrt((pNorm * (1 - pNorm) + z2 / (4 * n)) / n)
        return (center - margin) / (1 + z2 / n)
    }

    /**
     * Множитель Kelly fraction по размеру выборки (стадии вместо бинарного порога).
     *
     * 0-4 сделки:   ×0.10 (минимальный exploration)
     * 5-14 сделок:  ×0.25 (осторожный рост)
     * 15-29 сделок: ×0.50 (пол-Kelly)
     * 30-59 сделок: ×0.70
     * 60-99 сделок: ×0.85
     * 100+ сделок:  ×1.00 (полный Kelly)
     *
     * @param totalTrades количество закрытых сделок
     * @param tiers отсортированный по возрастанию список (порог → множитель)
     * @return множитель (0..1)
     */
    fun sampleSizeMultiplier(
        totalTrades: Int,
        tiers: List<Pair<Int, Double>>,
    ): Double {
        var multiplier = 0.10
        for ((threshold, mult) in tiers) {
            if (totalTrades >= threshold) multiplier = mult
        }
        return multiplier
    }

    /**
     * Чистый Kelly fraction без staging и без волатильности.
     *
     * kelly = (w × r - (1 - w)) / r
     *
     * @param wilsonWr нижняя граница Wilson win rate
     * @param avgWin средний выигрыш (абсолютное значение, > 0)
     * @param avgLoss средний проигрыш (абсолютное значение, > 0)
     * @return raw Kelly fraction (может быть < 0 = нет edge)
     */
    fun rawKellyFraction(
        wilsonWr: Double,
        avgWin: Double,
        avgLoss: Double,
    ): Double {
        val safeAvgLoss = avgLoss.coerceAtLeast(0.01)
        val r = avgWin / safeAvgLoss
        return (wilsonWr * r - (1 - wilsonWr)) / r
    }

    /**
     * Volatility targeting: множитель = targetVol / actualVol.
     *
     * @param actualVolPercent дневная волатильность в % (realized vol или ATR%)
     * @param targetVolPercent целевая волатильность из конфига
     * @param minMult нижняя граница множителя
     * @param maxMult верхняя граница множителя
     * @return множитель размера (minMult..maxMult)
     */
    fun volatilityMultiplier(
        actualVolPercent: Double,
        targetVolPercent: Double,
        minMult: Double,
        maxMult: Double,
    ): Double {
        if (actualVolPercent <= 0.0) return 1.0
        return (targetVolPercent / actualVolPercent).coerceIn(minMult, maxMult)
    }

    /**
     * Confidence-aware sizing: линейная интерполяция между minFactor и maxFactor.
     *
     * @param signalStrength сила сигнала (0..1)
     * @param threshold адаптивный порог уверенности
     * @param ceiling potолок для полного размера
     * @param minFactor множитель на пороге (минимальный размер)
     * @param maxFactor множитель на ceiling (полный размер)
     * @return множитель размера (minFactor..maxFactor)
     */
    fun confidenceFactor(
        signalStrength: Double,
        threshold: Double,
        ceiling: Double,
        minFactor: Double,
        maxFactor: Double,
    ): Double {
        val span = ceiling - threshold
        if (span <= 1e-9) return maxFactor
        val t = ((signalStrength - threshold) / span).coerceIn(0.0, 1.0)
        return (minFactor + (maxFactor - minFactor) * t).coerceIn(0.0, 1.0)
    }

    /**
     * Drawdown degradation: множитель по глубине просадки от пика AUM.
     *
     * @param drawdownPercent просадка в % от пика (0..100)
     * @param tiers отсортированный список (порог% → множитель)
     * @return множитель (0..1)
     */
    fun drawdownScaleMultiplier(
        drawdownPercent: Double,
        tiers: Map<Double, Double>,
    ): Double {
        var factor = 1.0
        for ((tier, scale) in tiers) {
            if (drawdownPercent >= tier) factor = scale
        }
        return factor.coerceIn(0.0, 1.0)
    }

    /**
     * Дневная волатильность из ATR — масштабирование к дневному горизонту.
     *
     * @param atrPercent ATR в % от цены (MINUTE_10)
     * @param candlesPerDay количество 10-мин свечей в дне
     * @return дневной эквивалент ATR%
     */
    fun dailyVolFromAtr(
        atrPercent: Double,
        candlesPerDay: Int,
    ): Double = atrPercent * sqrt(candlesPerDay.toDouble())
}
