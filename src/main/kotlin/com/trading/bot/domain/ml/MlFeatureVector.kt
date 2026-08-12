package com.trading.bot.domain.ml

import java.math.BigDecimal

/**
 * Полный вектор признаков для ML-инференса (roadmap v2.4, раздел 13.11.4).
 *
 * Порядок признаков СТРОГО совпадает с `NUMERIC_FEATURES` + `CATEGORICAL_FEATURES`
 * в `ml/train.py`: первыми идут 15 числовых, затем 2 категориальных. Несовпадение
 * порядка даёт «мусорные» предсказания без ошибки, поэтому порядок зафиксирован
 * в [numericFeatures]/[categoricalFeatures].
 *
 * На скрининге решение стратега ещё не принято: [strategyAction] = "" и
 * [strategyConfidence] = null (кодируется как NaN) — отдельные категории/пропуск,
 * как в [MlFeatureVector.from].
 */
data class MlFeatureVector(
    val rsi14: Double,
    val atrPercent: Double,
    val macdHistogramPercent: Double,
    val bbPercentB: Double,
    val emaSlopePercent: Double,
    val volatility20Percent: Double,
    val return3: Double,
    val return10: Double,
    val return20: Double,
    val cbrRate: Double,
    val brentPrice: Double,
    val usdRub: Double,
    val strategyConfidence: Double?,
    val inBlindSpotHour: Int,
    val hourOfDay: Int,
    val strategyAction: String,
    val direction: String,
) {
    /** 15 числовых признаков в порядке `ml/train.py` NUMERIC_FEATURES. */
    fun numericFeatures(): FloatArray =
        floatArrayOf(
            rsi14.toFloat(),
            atrPercent.toFloat(),
            macdHistogramPercent.toFloat(),
            bbPercentB.toFloat(),
            emaSlopePercent.toFloat(),
            volatility20Percent.toFloat(),
            return3.toFloat(),
            return10.toFloat(),
            return20.toFloat(),
            cbrRate.toFloat(),
            brentPrice.toFloat(),
            usdRub.toFloat(),
            strategyConfidence?.toFloat() ?: Float.NaN,
            inBlindSpotHour.toFloat(),
            hourOfDay.toFloat(),
        )

    /** 2 категориальных признака в порядке `ml/train.py` CATEGORICAL_FEATURES. */
    fun categoricalFeatures(): Array<String> = arrayOf(strategyAction, direction)

    companion object {
        const val NUMERIC_COUNT = 15
        const val CATEGORICAL_COUNT = 2

        /**
         * Сборка вектора из технических признаков [MlFeatureExtractor.Features] + контекста.
         *
         * [strategyAction]=""/[strategyConfidence]=null соответствуют «решение стратега
         * ещё не принято» (скрининг): категория пустого действия и пропуск confidence.
         */
        fun from(
            features: MlFeatureExtractor.Features,
            cbrRate: BigDecimal,
            brentPrice: BigDecimal,
            usdRub: BigDecimal,
            inBlindSpotHour: Boolean,
            hourOfDay: Int,
            strategyAction: String,
            strategyConfidence: Double?,
            direction: String,
        ): MlFeatureVector =
            MlFeatureVector(
                rsi14 = features.rsi14,
                atrPercent = features.atrPercent,
                macdHistogramPercent = features.macdHistogramPercent,
                bbPercentB = features.bbPercentB,
                emaSlopePercent = features.emaSlopePercent,
                volatility20Percent = features.volatility20Percent,
                return3 = features.return3,
                return10 = features.return10,
                return20 = features.return20,
                cbrRate = cbrRate.toDouble(),
                brentPrice = brentPrice.toDouble(),
                usdRub = usdRub.toDouble(),
                strategyConfidence = strategyConfidence,
                inBlindSpotHour = if (inBlindSpotHour) 1 else 0,
                hourOfDay = hourOfDay,
                strategyAction = strategyAction,
                direction = direction,
            )
    }
}
