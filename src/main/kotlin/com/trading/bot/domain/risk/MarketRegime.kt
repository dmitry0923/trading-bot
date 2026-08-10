package com.trading.bot.domain.risk

/**
 * Режим волатильности рынка (Volatility Engine 2.0).
 *
 * Классификация строится на перцентильном ранге текущей волатильности
 * относительно её же исторического распределения:
 *   < p40  → LOW      (спокойный рынок)
 *   < p70  → NORMAL
 *   < p90  → VOLATILE (повышенная волатильность — размер позиции урезается)
 *   >= p90 → STRESS   (стрессовый рынок — новые входы запрещены)
 *
 * Источником распределения и текущего значения служит индекс волатильности
 * MOEX (RVI); фьючерсная подразумеваемая волатильность (Si) используется как
 * диагностика и fallback текущего значения.
 */
enum class MarketRegime { LOW, NORMAL, VOLATILE, STRESS }

/**
 * Классификатор режима по перцентильному рангу текущей волатильности.
 *
 * Перцентильный ранг — доля исторических наблюдений, строго меньших текущего
 * значения (0..100). Робастен к выбросам и не требует интерполяции квантилей.
 */
object MarketRegimeClassifier {
    /**
     * Классифицирует текущую волатильность по историческому распределению.
     *
     * @param history исторические значения волатильности
     * @param current текущее значение волатильности
     * @param pLow перцентиль перехода LOW → NORMAL (по умолчанию 40)
     * @param pNormal перцентиль перехода NORMAL → VOLATILE (по умолчанию 70)
     * @param pVolatile перцентиль перехода VOLATILE → STRESS (по умолчанию 90)
     * @return режим или null, если история пуста (классифицировать нельзя)
     */
    fun classify(
        history: List<Double>,
        current: Double,
        pLow: Double = 40.0,
        pNormal: Double = 70.0,
        pVolatile: Double = 90.0,
    ): MarketRegime? {
        if (history.isEmpty()) return null
        val rank = percentileRank(history, current)
        return when {
            rank < pLow -> MarketRegime.LOW
            rank < pNormal -> MarketRegime.NORMAL
            rank < pVolatile -> MarketRegime.VOLATILE
            else -> MarketRegime.STRESS
        }
    }

    /**
     * Перцентильный ранг значения: доля наблюдений строго меньших [value] (0..100).
     */
    fun percentileRank(
        history: List<Double>,
        value: Double,
    ): Double {
        if (history.isEmpty()) return 0.0
        val below = history.count { it < value }
        return below * 100.0 / history.size
    }
}
