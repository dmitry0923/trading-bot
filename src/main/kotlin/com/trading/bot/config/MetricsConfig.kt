package com.trading.bot.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Конфигурация Micrometer: кастомные histogram buckets для критичных таймеров.
 *
 * Использует [MeterRegistry] напрямую (без MeterRegistryCustomizer из actuator-autoconfigure).
 */
@Configuration
class MetricsConfig {
    /**
     * Регистрирует SLA-гистограммы для критичных таймеров.
     * Бакеты в секундах: 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, 30, 60.
     */
    @Bean
    fun slaHistograms(registry: MeterRegistry): Boolean {
        listOf("alor.api.latency", "llm.latency", "bot.latency").forEach { name ->
            Timer
                .builder(name)
                .publishPercentileHistogram()
                .register(registry)
        }
        return true
    }
}
