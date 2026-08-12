package com.trading.bot.service.ml

/**
 * Обёртка над обученной ML-моделью для скрининга кандидатов (roadmap v2.4, 13.11.4).
 *
 * [probability] возвращает вероятность выигрышного исхода (0..1) для вектора
 * признаков [MlFeatureVector]. Модель всегда используется через [MlModelProvider]:
 * если модель недоступна (файл отсутствует/битый) — подставляется [NoopMlModel],
 * а скрининг возвращает 503 вместо падения.
 */
interface MlModel {
    /** Доступна ли модель для инференса. */
    val available: Boolean

    /** Причина недоступности (для логов/диагностики), null когда [available]. */
    val unavailableReason: String?

    /**
     * @param numeric 15 числовых признаков в порядке MlFeatureVector.numericFeatures()
     * @param categorical 2 категориальных признака в порядке MlFeatureVector.categoricalFeatures()
     */
    fun probability(
        numeric: FloatArray,
        categorical: Array<String>,
    ): Double
}

/** Заглушка для graceful degradation: при недоступной модели инференс невозможен. */
class NoopMlModel(
    override val unavailableReason: String,
) : MlModel {
    override val available: Boolean = false

    override fun probability(
        numeric: FloatArray,
        categorical: Array<String>,
    ): Double = throw IllegalStateException("ML model unavailable: $unavailableReason")
}
