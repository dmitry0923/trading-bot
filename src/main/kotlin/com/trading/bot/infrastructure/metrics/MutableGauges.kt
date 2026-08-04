package com.trading.bot.infrastructure.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Хранит сильную mutable-ссылку для Micrometer Gauge и обновляет уже
 * зарегистрированный meter. Регистрация gauge на immutable Double оставляла
 * навсегда первое значение, а последующие вызовы игнорировались Micrometer.
 */
class MutableGauges(
    private val meterRegistry: MeterRegistry,
) {
    private data class Key(
        val name: String,
        val tags: List<Tag>,
    )

    private val values = ConcurrentHashMap<Key, AtomicReference<Double>>()

    fun set(name: String, value: Number, tags: Tags = Tags.empty()) {
        val key = Key(name, tags.sortedBy { it.key }.toList())
        values.computeIfAbsent(key) {
            val reference = AtomicReference(0.0)
            meterRegistry.gauge(name, Tags.of(key.tags), reference) { it.get() }
            reference
        }.set(value.toDouble())
    }
}
