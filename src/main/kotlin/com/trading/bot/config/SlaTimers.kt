package com.trading.bot.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

/**
 * Персистентные таймеры критичных операций: LLM-вызовы, REST-ордера, тик позиции.
 *
 * Зачем отдельная точка записи, а не `meterRegistry.timer(name, tags)` на сайтах:
 * **одно prometheus-имя должно давать ровно одну snapshot-семью.**
 *
 * Предыдущая реализация предрегистрировала эти таймеры в `MetricsConfig` без тегов
 * с `publishPercentileHistogram()`, а писали их четыре компонента **с тегами**
 * (`llm.latency{agent=…}` в `ResilientLlmClient`, `alor.api.latency{operation=…}` в
 * `AlorClient`/`RestOrderTransport`, `bot.latency{ticker=…}` в `StockPositionMonitor`).
 * Тегированный meter — это другой meter с тем же именем, поэтому Micrometer
 * формировал для одного имени две семьи, `SUMMARY` и `HISTOGRAM`, а
 * `PrometheusTextFormatWriter` строит карту по имени и при записи summary приводит
 * элемент к типу summary:
 * `ClassCastException: HistogramDataPointSnapshot cannot be cast to
 * SummaryDataPointSnapshot` → HTTP 500 на `/actuator/prometheus` (2026-10-05).
 * Следствие было не только «нечитаемый скрейп»: недоступны все research-счётчики
 * `bt_llm_veto_*`, `bt_funding_veto_*` и т.п.
 *
 * Теперь percentile-гистограмма включается **в точке создания каждого** таймера из
 * [HISTOGRAM_TIMERS], поэтому все meters одного имени — гистограммы, и prometheus
 * отдаёт одну согласованную семью.
 *
 * Бакеты — дефолтные Micrometer (`publishPercentileHistogram` без явных
 * `serviceLevelObjectives`): прежний KDoc `MetricsConfig` обещал несуществующий
 * набор 0.1…60 с, он никогда не был задан.
 */
object SlaTimers {
    /** Таймеры, для которых обязательна percentile-гистограмма (значения в секундах). */
    private val HISTOGRAM_TIMERS =
        setOf(
            "alor.api.latency",
            "llm.latency",
            "bot.latency",
        )

    /**
     * Записать замер SLA-таймера; для таймеров из [HISTOGRAM_TIMERS] включается
     * percentile-гистограмма.
     *
     * @param registry реестр метрик.
     * @param name имя таймера.
     * @param tags теги набора метрик.
     * @param amount длительность в [unit].
     * @param unit единица длительности.
     */
    fun record(
        registry: MeterRegistry,
        name: String,
        tags: Tags,
        amount: Long,
        unit: TimeUnit,
    ) {
        val builder = Timer.builder(name).tags(tags)
        if (name in HISTOGRAM_TIMERS) {
            builder.publishPercentileHistogram()
        }
        builder.register(registry).record(amount, unit)
    }
}
