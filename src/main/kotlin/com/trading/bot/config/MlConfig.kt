package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация ML-модуля (prefix = "ml", roadmap v2.4, раздел 13.11).
 *
 * - [enabled] — мастер-флаг модуля (промоушн после валидации на out-of-sample).
 *   При `false` эндпоинты экспорта датасета и скрининга возвращают 404.
 * - [dataset] — настройки экспорта обучающего датасета (positions + candles + agent_logs).
 * - [model] — путь к файлу обученной модели CatBoost (.cbm, см. раздел 13.11.3).
 * - [screening] — настройки ML-скрининга кандидатов (раздел 13.11.4).
 * - [filter] — ML-фильтр входа в торговый цикл (раздел 13.11.5) + опциональный
 *   тренд-гейт (раздел 13.11.7).
 * - [trend] — настройки ML-прогноза удержания тренда (раздел 13.11.7).
 */
@Component
@ConfigurationProperties(prefix = "ml")
class MlConfig {
    var enabled: Boolean = false
    var dataset: Dataset = Dataset()
    var model: Model = Model()
    var screening: Screening = Screening()
    var filter: Filter = Filter()
    var trend: Trend = Trend()

    class Dataset {
        /** Таймфрейм свечей для признаков (должен совпадать с режимом торговли). */
        var timeframe: String = "MINUTE_10"

        /** Число последних свечей до входа, используемых как окно признаков. */
        var lookbackBars: Int = 30

        /** Лимит строк экспорта (самые свежие сделки). */
        var maxRows: Int = 5000
    }

    class Model {
        /**
         * Путь к файлу обученной модели CatBoost (артефакт пайплайна, раздел 13.11.3).
         * При недоступном/битом файле скрининг деградирует в MODEL_UNAVAILABLE (503).
         */
        var path: String = "ml/model.cbm"
    }

    class Screening {
        /** Число лучших тикеров-кандидатов по умолчанию (GET /api/v1/ml/screen). */
        var topN: Int = 5
    }

    class Filter {
        /**
         * Включить ML-фильтр входа в торговый цикл (раздел 13.11.5).
         * При `true` и `ml.enabled=true` вход в позицию (DecisionEngine) требует
         * прогноз модели >= [threshold]; при недоступной модели вход блокируется
         * (fail-closed). Отдельный флаг от [MlConfig.enabled]: включение модуля
         * (например, только для экспорта датасета) само по себе не гейтит входы.
         */
        var enabled: Boolean = false

        /** Минимальная вероятность выигрышного исхода для входа (0..1). */
        var threshold: Double = 0.5

        /**
         * Опциональный тренд-гейт входа (раздел 13.11.7): при `true` вход также
         * требует оценку удержания тренда [MlConfig.trend] >= [trendMinScore].
         * Отдельный флаг от [enabled]: позволяет включить гейт после валидации
         * прогноза тренда, не меняя поведение базового фильтра.
         */
        var trendGateEnabled: Boolean = false

        /** Минимальная оценка удержания тренда для входа при [trendGateEnabled] (0..1). */
        var trendMinScore: Double = 0.5
    }

    class Trend {
        /**
         * Горизонт прогноза в барах (таймфрейм [Dataset.timeframe]): интерпретация
         * оценки удержания тренда. Отдаётся в ответе [com.trading.bot.model.dto.MlTrendResult],
         * на расчёт не влияет (модель обучена на исходах позиций).
         */
        var horizonBars: Int = 6

        /** Число лучших тикеров по умолчанию (GET /api/v1/ml/trend). */
        var topN: Int = 5
    }
}
