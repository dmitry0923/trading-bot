package com.trading.bot.domain.risk

/**
 * Онлайн-калибровка порога уверенности по исходам сделок (roadmap v2.4, раздел 13.11.8).
 *
 * Замена правил в [com.trading.bot.service.AdaptiveRiskService.getAdaptiveConfidenceThreshold]
 * на обучение по накопленным исходам: для тикера берутся закрытые позиции с уверенностью
 * стратега на входе и меткой выигрыша (pnl > 0). Ищется НИЖНЯЯ граница уверенности c,
 * при которой выборка сделок `confidence >= c` имеет win rate >= целевого при достаточном
 * размере выборки. Это «онлайн»-режим: порог пересчитывается на каждом вызове из актуальных
 * исходов и автоматически ужесточается/смягчается по мере накопления статистики.
 *
 * Чистый объект без зависимостей — легко тестируется и переиспользуется.
 */
object ConfidenceCalibrator {
    /**
     * Результат калибровки.
     *
     * @param threshold выбранная нижняя граница уверенности
     * @param sampleSize размер выборки сделок `confidence >= threshold`
     * @param winRate фактический win rate выборки
     */
    data class Calibration(
        val threshold: Double,
        val sampleSize: Int,
        val winRate: Double,
    )

    /**
     * Ищет самую низкую границу c в диапазоне [minThreshold, maxThreshold] шагом [step],
     * при которой сделки с `confidence >= c` дают win rate >= [targetWinRate] и выборка
     * >= [minTrades]. Возвращает null, если ни одна граница не удовлетворяет условиям
     * (мало данных или исходы не дотягивают до целевого win rate).
     *
     * @param outcomes пары (confidence на входе, win) закрытых сделок тикера
     */
    fun calibrate(
        outcomes: List<Pair<Double, Boolean>>,
        targetWinRate: Double,
        minTrades: Int,
        minThreshold: Double,
        maxThreshold: Double,
        step: Double,
    ): Calibration? {
        if (outcomes.size < minTrades) return null
        var best: Calibration? = null
        var c = maxThreshold
        while (c >= minThreshold - 1e-9) {
            val sample = outcomes.filter { it.first >= c }
            if (sample.size >= minTrades) {
                val wins = sample.count { it.second }
                val winRate = wins.toDouble() / sample.size
                if (winRate >= targetWinRate) {
                    // Цикл идёт сверху вниз: последняя запись — самый низкий удовлетворяющий порог.
                    best = Calibration(round2(c), sample.size, round2(winRate))
                }
            }
            c -= step
        }
        return best
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
}
