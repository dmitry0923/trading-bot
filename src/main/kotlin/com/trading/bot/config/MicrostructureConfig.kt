package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация сбора микроструктуры стакана (prefix = "microstructure").
 *
 * Назначение — forward-исследование: сохранять OBI/microprice/spread из live
 * L1-котировок Alor в БД, чтобы проверить, добавляют ли признаки формы
 * стакана к уже измеренной детерминированной стратегии. Исторических L1-данных
 * не существует, поэтому данные появляются только с момента включения.
 *
 * Сбор **не влияет на торговые решения**: это чисто наблюдательная запись,
 * медленный flush идёт отдельной корутиной и не блокирует hot-path котировок.
 *
 * @property enabled включение сбора (по умолчанию выключено — объём хранения)
 * @property bucketMs размер бакета агрегации, мс (по умолчанию 1000 = 1с)
 * @property flushIntervalMs период сброса закрытых бакетов в БД, мс
 * @property maxQueueSize максимум бакетов в очереди записи; при переполнении
 *   дропается самый старый (fail-soft: лучше потерять наблюдение, чем тормозить торговлю)
 */
@Component
@ConfigurationProperties(prefix = "microstructure")
class MicrostructureConfig {
    var enabled: Boolean = false
    var bucketMs: Long = 1_000L
    var flushIntervalMs: Long = 5_000L
    var maxQueueSize: Int = 20_000
}
