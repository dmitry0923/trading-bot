package com.trading.bot.infrastructure.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Gauge-хранилище на mutable-референсах.
 *
 * `meterRegistry.gauge(name, tags, constant)` замораживает первое значение на всё
 * время жизни метра: повторный вызов с новым значением возвращает уже
 * зарегистрированный meter и не обновляет его. Чтобы метрика всегда отдавала
 * актуальное значение, значение держим в [AtomicReference], а в Micrometer
 * регистрируем функцию-читатель этого референса.
 *
 * Хранилище сегментировано по [MeterRegistry]: в юнит-тестах каждый
 * `SimpleMeterRegistry` изолирован, в проде — один registry на JVM.
 * Ключ — (имя, упорядоченный список тегов), поэтому одна и та же метрика с
 * разными наборами тегов (например per-account `risk.daily.pnl`) не конфликтует.
 */
object MutableGauges {
    private data class MetricKey(
        val name: String,
        val tags: List<Pair<String, String>>,
    )

    private val registries = ConcurrentHashMap<MeterRegistry, ConcurrentHashMap<MetricKey, AtomicReference<Double>>>()

    /**
     * Публикует gauge-значение: регистрирует meter при первом вызове для
     * (registry, name, tags) и обновляет хранимый [AtomicReference] при каждом.
     */
    fun set(
        meterRegistry: MeterRegistry,
        name: String,
        value: Double,
        tags: Tags = Tags.empty(),
    ) {
        val key =
            MetricKey(
                name = name,
                tags = tags.map { it.key to it.value }.sortedBy { it.first },
            )
        val ref =
            registries
                .computeIfAbsent(meterRegistry) { ConcurrentHashMap() }
                .computeIfAbsent(key) { _ ->
                    AtomicReference<Double>(0.0).also { r ->
                        meterRegistry.gauge(name, tags, r) { it.get() }
                    }
                }
        ref.set(value)
    }
}
