package com.trading.bot.domain.ml

import kotlin.math.abs

/**
 * Онлайн-логистическая регрессия (SGD + L2) для исследовательской ML-стратегии
 * направления ([com.trading.bot.application.strategy.OnlineMlDirectionStrategy]).
 *
 * Обучение инкрементальное: каждый вызов [update] делает ОДИН шаг стохастического
 * градиента на одном примере. Предсказание [predict] — вероятность P(y=1) по
 * сигмоиде w·x + b. Всё состояние — веса; при их обнулении через [reset] модель
 * детерминирована (одинаковая последовательность примеров → одинаковые веса).
 *
 * research-инструмент: обучение происходит прямо внутри бэктеста на расширяющемся
 * окне (без обучения батчами на всём датасете), паритет «как в live, только
 * инкрементально». Целевые метки формирует стратегия БЕЗ lookahead (пример
 * обучается только когда его горизонт закрылся).
 */
class OnlineLogisticRegression(
    val featureCount: Int,
    val learningRate: Double,
    val l2: Double,
) {
    private val weights = DoubleArray(featureCount)
    private var bias = 0.0

    /** Число выполненных шагов обучения. */
    var trainedSamples: Int = 0
        private set

    /** Обучить на одном примере (features размера [featureCount], label 0/1). */
    fun update(
        features: DoubleArray,
        label: Double,
    ) {
        require(features.size == featureCount) { "expected $featureCount features, got ${features.size}" }
        require(label == 0.0 || label == 1.0) { "label must be 0 or 1, got $label" }
        val error = predict(features) - label
        for (i in features.indices) {
            weights[i] -= learningRate * (error * features[i] + l2 * weights[i])
        }
        bias -= learningRate * error
        trainedSamples++
    }

    /** Вероятность P(y=1) для вектора признаков. */
    fun predict(features: DoubleArray): Double {
        require(features.size == featureCount) { "expected $featureCount features, got ${features.size}" }
        var z = bias
        for (i in features.indices) {
            z += weights[i] * features[i]
        }
        val p = 1.0 / (1.0 + kotlin.math.exp(-z.coerceIn(-40.0, 40.0)))
        return p.coerceIn(0.0, 1.0)
    }

    /** Сброс весов к нулю (используется при старте новой симуляции). */
    fun reset() {
        weights.fill(0.0)
        bias = 0.0
        trainedSamples = 0
    }

    /** Текущая норма весов (для отладки/метрик исследовательских прогонов). */
    fun weightNorm(): Double {
        var sum = bias * bias
        for (w in weights) sum += w * w
        return abs(sum)
    }
}
