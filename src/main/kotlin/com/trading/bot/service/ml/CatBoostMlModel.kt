package com.trading.bot.service.ml

import ai.catboost.CatBoostModel
import kotlin.math.exp

/**
 * Инференс через CatBoost Java (артефакт `ai.catboost:catboost-prediction`, 1.2.8).
 *
 * `CatBoostModel.predict` возвращает raw score (лог-ит, не вероятностная калибровка),
 * поэтому применяется сигмоида — как в `CatBoost4jPredictionTutorial` и в `ml/train.py`.
 * Порядок признаков должен совпадать с обучением (см. [MlFeatureVector]).
 */
class CatBoostMlModel(
    private val model: CatBoostModel,
) : MlModel {
    override val available: Boolean = true
    override val unavailableReason: String? = null

    override fun probability(
        numeric: FloatArray,
        categorical: Array<String>,
    ): Double {
        val raw = model.predict(numeric, categorical)
        return sigmoid(raw.get(0, 0))
    }

    companion object {
        fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
    }
}
