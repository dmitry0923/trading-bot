package com.trading.bot.config

import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Регресс-тест на 500 `/actuator/prometheus` (2026-10-05, `ClassCastException`
 * в `PrometheusTextFormatWriter.writeSummary`).
 *
 * Причина: прежний `MetricsConfig` предрегистрировал SLA-таймеры **без тегов** с
 * `publishPercentileHistogram()`, а писали их четыре компонента **с тегами**
 * (`llm.latency{agent=…}`, `alor.api.latency{operation=…}`, `bot.latency{ticker=…}`).
 * Micrometer формирует для одного prometheus-имени две семьи — `SUMMARY` и
 * `HISTOGRAM`, — а writer схлопывает их по имени и падает при приведении элемента к
 * типу summary. Итог: нечитаемы все research-счётчики (`bt_llm_veto_*`,
 * `bt_funding_veto_*` и т.п.).
 *
 * Тест фиксирует обе стороны: старый способ действительно ломает скрейп (иначе
 * диагноз был бы неполным), а [SlaTimers] даёт согласованные гистограммы при любом
 * наборе тегов.
 */
class SlaTimersTest {
    private fun registry() = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    private fun recordAllSla(registry: PrometheusMeterRegistry) {
        // Наборы тегов из реального hot-path.
        SlaTimers.record(registry, "llm.latency", Tags.of("agent", "arbitrator"), 1_200, TimeUnit.MILLISECONDS)
        SlaTimers.record(registry, "llm.latency", Tags.of("agent", "strategist"), 800, TimeUnit.MILLISECONDS)
        SlaTimers.record(registry, "bot.latency", Tags.of("ticker", "CNYRUBF"), 400, TimeUnit.MILLISECONDS)
        SlaTimers.record(registry, "alor.api.latency", Tags.of("operation", "sendOrder"), 700, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `old untagged preregistration mixed with tagged timers breaks the scrape`() {
        val registry = registry()
        Timer.builder("llm.latency").publishPercentileHistogram().register(registry)
        registry.timer("llm.latency", Tags.of("agent", "arbitrator")).record(1_200, TimeUnit.MILLISECONDS)

        assertThrows(ClassCastException::class.java) { registry.scrape() }
    }

    @Test
    fun `SLA timers are scraped as histograms for every tag set`() {
        val registry = registry()

        recordAllSla(registry)

        val scrape = registry.scrape()

        assertTrue(scrape.contains("llm_latency_seconds_bucket"), "SLA-таймер обязан экспортироваться гистограммой")
        assertTrue(scrape.contains("bot_latency_seconds_bucket"))
        assertTrue(scrape.contains("alor_api_latency_seconds_bucket"))
        assertFalse(scrape.contains("llm_latency_seconds{quantile="), "SUMMARY-семья недопустима: только гистограмма")
        assertFalse(scrape.contains("bot_latency_seconds{quantile="))
        assertFalse(scrape.contains("alor_api_latency_seconds{quantile="))
        // У гистограммы нет линии с голым именем (есть _bucket/_count/_sum), поэтому
        // различимость наборов тегов проверяем по _count — у summary такой линии с
        // метками quantile не бывает вовсе.
        assertTrue(scrape.contains("""llm_latency_seconds_count{agent="arbitrator"}"""), "оба набора тегов остаются различимыми")
        assertTrue(scrape.contains("""llm_latency_seconds_count{agent="strategist"}"""))
        assertTrue(scrape.contains("""bot_latency_seconds_count{ticker="CNYRUBF"}"""))
    }

    @Test
    fun `timers outside the SLA list keep the default summary form`() {
        val registry = registry()

        registry.timer("order.submit.latency").record(200, TimeUnit.MILLISECONDS)

        val scrape = registry.scrape()

        assertTrue(scrape.contains("order_submit_latency_seconds_count"), "не-SLA таймер остаётся summary")
        assertFalse(scrape.contains("order_submit_latency_seconds_bucket"), "гистограмма включается только для SLA-таймеров")
    }

    @Test
    fun `repeated recordings accumulate into the same histogram`() {
        val registry = registry()

        SlaTimers.record(registry, "llm.latency", Tags.of("agent", "arbitrator"), 100, TimeUnit.MILLISECONDS)
        SlaTimers.record(registry, "llm.latency", Tags.of("agent", "arbitrator"), 300, TimeUnit.MILLISECONDS)

        val timer = registry.timer("llm.latency", Tags.of("agent", "arbitrator"))

        assertEquals(2L, timer.count())
        assertEquals(0.4, timer.totalTime(TimeUnit.SECONDS), 1e-9)
    }
}
