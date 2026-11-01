package com.trading.bot.infrastructure.llm

import com.trading.bot.config.LlmConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [SemanticCache] — ветви без Redis: generic-fingerprint с null, пустые
 * namespace/versionSeed, бакеты сессии по фиксированным часам, валидация JSON
 * перед записью и промах чтения.
 */
class SemanticCacheUnitBranchesTest {
    private val objectMapper = jacksonObjectMapper()
    private val redis = mock<StringRedisTemplate>()
    private val ops = mock<ValueOperations<String, String>>()
    private val registry = SimpleMeterRegistry()

    private fun cacheAt(hour: Int): SemanticCache {
        val zone = ZoneId.of("Europe/Moscow")
        val instant = LocalDateTime.of(2026, 1, 1, hour, 0).atZone(zone).toInstant()
        val config =
            LlmConfig().apply {
                semanticCacheEnabled = true
                semanticCacheTtlMinutes = 10
            }
        whenever(redis.opsForValue()).thenReturn(ops)
        return SemanticCache(redis, objectMapper, registry, config, Clock.fixed(instant, zone))
    }

    @Test
    fun `generic fingerprint renders null components as NA`() {
        assertEquals("NA:x:NA", cacheAt(14).genericFingerprint(null, "x", null))
    }

    @Test
    fun `blank namespace and version seed match the null form`() {
        val cache = cacheAt(14)
        val blank = cache.key("agent", "SBER", "fp", " ", " ")
        val absent = cache.key("agent", "SBER", "fp", null, null)

        assertEquals(absent, blank)
        assertNotNull(blank)
    }

    @Test
    fun `session bucket follows the Moscow clock`() {
        assertTrue(cacheAt(9).fingerprint(BigDecimal("100"), 50.0, "UP", "LOW").endsWith(":MORNING"))
        assertTrue(cacheAt(14).fingerprint(BigDecimal("100"), 50.0, "UP", "LOW").endsWith(":DAY"))
        assertTrue(cacheAt(18).fingerprint(BigDecimal("100"), 50.0, "UP", "LOW").endsWith(":EVENING"))
    }

    @Test
    fun `non json and array json contents are handled before the write`() {
        val cache = cacheAt(14)

        cache.put("agent", "SBER", "fp", LlmResponse(content = "plain text"))
        assertEquals(1.0, registry.counter("llm.cache.skip", "agent", "agent", "reason", "INVALID_JSON").count())
        assertEquals(0, Mockito.mockingDetails(ops).invocations.count { it.method.name == "set" })

        cache.put("agent", "SBER", "fp", LlmResponse(content = "[1,2]"))
        assertEquals(1, Mockito.mockingDetails(ops).invocations.count { it.method.name == "set" })
    }

    @Test
    fun `miss on an empty redis value increments the miss metric`() {
        val cache = cacheAt(14)
        whenever(ops.get(Mockito.anyString())).thenReturn(null)

        assertNull(cache.get("agent", "SBER", "fp"))
        assertEquals(1.0, registry.counter("llm.cache.miss", "agent", "agent").count())
    }

    @Test
    fun `put stores a json response with the configured ttl`() {
        val cache = cacheAt(14)

        cache.put("agent", "SBER", "fp", LlmResponse(content = """{"ok":true}"""))

        assertEquals(1, Mockito.mockingDetails(ops).invocations.count { it.method.name == "set" })
    }
}
