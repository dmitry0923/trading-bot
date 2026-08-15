package com.trading.bot.infrastructure.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Живые (mutable) gauge-метрики через [MutableGauges] (roadmap 13.26.2, F-15):
 * повторный `set` должен обновлять значение метра, а не замораживать первое.
 */
class MutableGaugesTest {
    @Test
    fun `set publishes initial value`() {
        val registry = SimpleMeterRegistry()
        MutableGauges.set(registry, "test.mutable_gauge", 1.0)
        assertEquals(1.0, value(registry, "test.mutable_gauge"))
    }

    @Test
    fun `set updates gauge value instead of freezing first`() {
        val registry = SimpleMeterRegistry()
        MutableGauges.set(registry, "test.mutable_gauge", 1.0)
        MutableGauges.set(registry, "test.mutable_gauge", 2.5)
        MutableGauges.set(registry, "test.mutable_gauge", 0.75)
        assertEquals(0.75, value(registry, "test.mutable_gauge"))
    }

    @Test
    fun `tagged and untagged gauges with same name are independent`() {
        val registry = SimpleMeterRegistry()
        MutableGauges.set(registry, "test.gauge", 3.0)
        MutableGauges.set(registry, "test.gauge", 5.0, Tags.of("account", "1"))
        MutableGauges.set(registry, "test.gauge", 7.0, Tags.of("account", "2"))

        assertEquals(3.0, value(registry, "test.gauge"))
        assertEquals(5.0, value(registry, "test.gauge", Tags.of("account", "1")))
        assertEquals(7.0, value(registry, "test.gauge", Tags.of("account", "2")))
    }

    @Test
    fun `registries are isolated from each other`() {
        val first = SimpleMeterRegistry()
        val second = SimpleMeterRegistry()

        MutableGauges.set(first, "test.gauge", 11.0)
        MutableGauges.set(second, "test.gauge", 22.0)
        MutableGauges.set(first, "test.gauge", 33.0)

        assertEquals(33.0, value(first, "test.gauge"))
        assertEquals(22.0, value(second, "test.gauge"))
    }

    private fun value(
        registry: MeterRegistry,
        name: String,
        tags: Tags = Tags.empty(),
    ): Double {
        val expected = tags.toSet()
        val gauge = registry.find(name).gauges().first { it.id.tags.toSet() == expected }
        return gauge.value()
    }
}
