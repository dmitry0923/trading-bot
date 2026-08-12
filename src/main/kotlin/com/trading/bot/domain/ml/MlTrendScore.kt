package com.trading.bot.domain.ml

/**
 * Оценка вероятности удержания тренда (roadmap v2.4, раздел 13.11.7).
 *
 * Скоринг из двух компонент:
 * - модель (P(win) = P(продолжение тренда) для направления вектора) — вес 60%;
 * - детерминированная сила тренда по индикаторам признакового вектора (EMA-наклон,
 *   return20, MACD-гистограмма, отклонение %B от средней полосы) — вес 40%.
 *
 * Чистая функция без зависимостей — используется и standalone-прогнозом
 * ([MlTrendForecastService]), и опциональным тренд-гейтом входа ([MlEntryFilter],
 * `ml.filter.trend-gate-enabled`).
 */
class MlTrendScore private constructor() {
    companion object {
        const val MODEL_WEIGHT = 0.6
        const val INDICATOR_WEIGHT = 0.4

        /**
         * Итоговая оценка удержания тренда в диапазоне [0, 1].
         *
         * @param vector признаковый вектор (направление берётся из [MlFeatureVector.direction])
         * @param probability прогноз модели (P win для направления вектора)
         */
        fun score(
            vector: MlFeatureVector,
            probability: Double,
        ): Double {
            val indicatorStrength = indicatorStrength(vector)
            val p = probability.coerceIn(0.0, 1.0)
            return (MODEL_WEIGHT * p + INDICATOR_WEIGHT * indicatorStrength).coerceIn(0.0, 1.0)
        }

        /**
         * Сила тренда по индикаторам (0..1): 0.5 — нейтрально, > 0.5 — индикаторы
         * согласованы с направлением вектора, < 0.5 — против.
         */
        fun indicatorStrength(vector: MlFeatureVector): Double {
            val raw =
                when (vector.direction) {
                    "LONG" -> {
                        signed(vector.emaSlopePercent) + signed(vector.return20) + signed(vector.macdHistogramPercent) +
                            signed(vector.bbPercentB - 50.0)
                    }

                    "SHORT" -> {
                        -signed(vector.emaSlopePercent) - signed(vector.return20) - signed(vector.macdHistogramPercent) -
                            signed(vector.bbPercentB - 50.0)
                    }

                    else -> {
                        0.0
                    }
                }
            return (0.5 + 0.5 * raw / INDICATOR_COUNT).coerceIn(0.0, 1.0)
        }

        private fun signed(value: Double): Double =
            when {
                value > 0.0 -> 1.0
                value < 0.0 -> -1.0
                else -> 0.0
            }

        private const val INDICATOR_COUNT = 4
    }
}
