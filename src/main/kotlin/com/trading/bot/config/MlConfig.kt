package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация ML-модуля (prefix = "ml", roadmap v2.4, раздел 13.11).
 *
 * - [enabled] — мастер-флаг модуля (промоушн после валидации на out-of-sample).
 *   При `false` эндпоинты экспорта датасета возвращают 404.
 * - [dataset] — настройки экспорта обучающего датасета (positions + candles + agent_logs).
 */
@Component
@ConfigurationProperties(prefix = "ml")
class MlConfig {
    var enabled: Boolean = false
    var dataset: Dataset = Dataset()

    class Dataset {
        /** Таймфрейм свечей для признаков (должен совпадать с режимом торговли). */
        var timeframe: String = "MINUTE_10"

        /** Число последних свечей до входа, используемых как окно признаков. */
        var lookbackBars: Int = 30

        /** Лимит строк экспорта (самые свежие сделки). */
        var maxRows: Int = 5000
    }
}
