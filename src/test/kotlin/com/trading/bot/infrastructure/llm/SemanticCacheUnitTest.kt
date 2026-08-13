package com.trading.bot.infrastructure.llm

import com.trading.bot.config.LlmConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Duration

/**
 * Модульные тесты SemanticCache с моканным Redis (без контейнера):
 * degenerate-ветки get/put, namespace-изоляция ключей, бакеты fingerprint.
 * Happy-path с реальным Redis покрыт в SemanticCacheTest.
 */
class SemanticCacheUnitTest {
    private inline fun <reified T : Any> mock(): T = Mockito.mock(T::class.java)

    private fun cache(
        enabled: Boolean,
        redisTemplate: StringRedisTemplate,
        registry: SimpleMeterRegistry,
    ): SemanticCache =
        SemanticCache(
            redisTemplate,
            jacksonObjectMapper(),
            registry,
            LlmConfig().apply {
                semanticCacheEnabled = enabled
                semanticCacheTtlMinutes = 10
            },
        )

    @Test
    fun `get returns null and records miss when cache disabled`() {
        val redisTemplate: StringRedisTemplate = mock()
        val registry = SimpleMeterRegistry()
        val cache = cache(enabled = false, redisTemplate = redisTemplate, registry = registry)

        assertNull(cache.get("technical", "SBER", "fp"))

        Mockito.verify(redisTemplate, Mockito.never()).opsForValue()
        assertEquals(
            1.0,
            registry
                .find("llm.cache.miss")
                .tag("agent", "technical")
                .counter()!!
                .count(),
        )
    }

    @Test
    fun `put is a no-op when cache disabled`() {
        val redisTemplate: StringRedisTemplate = mock()
        val valueOps: ValueOperations<String, String> = mock()
        Mockito.`when`(redisTemplate.opsForValue()).thenReturn(valueOps)
        val cache = cache(enabled = false, redisTemplate = redisTemplate, registry = SimpleMeterRegistry())

        cache.put("technical", "SBER", "fp", LlmResponse(content = "{}"))

        Mockito
            .verify(valueOps, Mockito.never())
            .set(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any<Duration>())
    }

    @Test
    fun `put skips fallback responses`() {
        val redisTemplate: StringRedisTemplate = mock()
        val valueOps: ValueOperations<String, String> = mock()
        Mockito.`when`(redisTemplate.opsForValue()).thenReturn(valueOps)
        val cache = cache(enabled = true, redisTemplate = redisTemplate, registry = SimpleMeterRegistry())

        cache.put("technical", "SBER", "fp", LlmResponse.fallback("NO_API_KEY"))

        Mockito
            .verify(valueOps, Mockito.never())
            .set(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any<Duration>())
    }

    @Test
    fun `get returns cached response flagged fromCache and records hit`() {
        val redisTemplate: StringRedisTemplate = mock()
        val valueOps: ValueOperations<String, String> = mock()
        Mockito.`when`(redisTemplate.opsForValue()).thenReturn(valueOps)
        Mockito
            .`when`(valueOps.get(ArgumentMatchers.anyString()))
            .thenReturn("""{"content":"{\"conclusion\":\"BULLISH\"}","tokensUsed":7,"latencyMs":9,"model":"kimi"}""")
        val registry = SimpleMeterRegistry()
        val cache = cache(enabled = true, redisTemplate = redisTemplate, registry = registry)

        val cached = cache.get("technical", "SBER", "fp")

        assertEquals("{\"conclusion\":\"BULLISH\"}", cached!!.content)
        assertEquals(7, cached.tokensUsed)
        assertTrue(cached.fromCache)
        assertEquals(
            1.0,
            registry
                .find("llm.cache.hit")
                .tag("agent", "technical")
                .counter()!!
                .count(),
        )
    }

    @Test
    fun `corrupted json on get returns null and records error`() {
        val redisTemplate: StringRedisTemplate = mock()
        val valueOps: ValueOperations<String, String> = mock()
        Mockito.`when`(redisTemplate.opsForValue()).thenReturn(valueOps)
        Mockito.`when`(valueOps.get(ArgumentMatchers.anyString())).thenReturn("not-json{")
        val registry = SimpleMeterRegistry()
        val cache = cache(enabled = true, redisTemplate = redisTemplate, registry = registry)

        assertNull(cache.get("technical", "SBER", "fp"))

        assertEquals(
            1.0,
            registry
                .find("llm.cache.error")
                .tag("agent", "technical")
                .counter()!!
                .count(),
        )
    }

    @Test
    fun `redis read exception on get is swallowed`() {
        val redisTemplate: StringRedisTemplate = mock()
        val valueOps: ValueOperations<String, String> = mock()
        Mockito.`when`(redisTemplate.opsForValue()).thenReturn(valueOps)
        Mockito.`when`(valueOps.get(ArgumentMatchers.anyString())).thenThrow(RuntimeException("redis down"))
        val registry = SimpleMeterRegistry()
        val cache = cache(enabled = true, redisTemplate = redisTemplate, registry = registry)

        assertNull(cache.get("technical", "SBER", "fp"))

        assertEquals(
            1.0,
            registry
                .find("llm.cache.error")
                .tag("agent", "technical")
                .counter()!!
                .count(),
        )
    }

    @Test
    fun `redis write exception on put is swallowed and records error`() {
        val redisTemplate: StringRedisTemplate = mock()
        val valueOps: ValueOperations<String, String> = mock()
        Mockito.`when`(redisTemplate.opsForValue()).thenReturn(valueOps)
        Mockito
            .`when`(valueOps.set(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any<Duration>()))
            .thenThrow(RuntimeException("redis down"))
        val registry = SimpleMeterRegistry()
        val cache = cache(enabled = true, redisTemplate = redisTemplate, registry = registry)

        cache.put("technical", "SBER", "fp", LlmResponse(content = "{}"))

        assertEquals(
            1.0,
            registry
                .find("llm.cache.error")
                .tag("agent", "technical")
                .counter()!!
                .count(),
        )
    }

    @Test
    fun `put writes with ttl and hashed key`() {
        val redisTemplate: StringRedisTemplate = mock()
        val valueOps: ValueOperations<String, String> = mock()
        Mockito.`when`(redisTemplate.opsForValue()).thenReturn(valueOps)
        val cache = cache(enabled = true, redisTemplate = redisTemplate, registry = SimpleMeterRegistry())
        val expectedKey = cache.key("technical", "SBER", "fp", "backtest")

        cache.put("technical", "SBER", "fp", LlmResponse(content = "{}"), namespace = "backtest")

        Mockito
            .verify(valueOps)
            .set(ArgumentMatchers.eq(expectedKey), Mockito.anyString(), ArgumentMatchers.eq(Duration.ofMinutes(10)))
    }

    @Test
    fun `key isolates namespaces`() {
        val redisTemplate: StringRedisTemplate = mock()
        val cache = cache(enabled = true, redisTemplate = redisTemplate, registry = SimpleMeterRegistry())

        val live = cache.key("technical", "SBER", "280.5:6:UP:LOW")
        val backtest = cache.key("technical", "SBER", "280.5:6:UP:LOW", namespace = "backtest")

        assertNotEquals(live, backtest)
        assertTrue(live.startsWith("llm:semantic:"))
        assertTrue(backtest.startsWith("llm:semantic:"))
    }

    @Test
    fun `fingerprint buckets atr percentile`() {
        val redisTemplate: StringRedisTemplate = mock()
        val cache = cache(enabled = true, redisTemplate = redisTemplate, registry = SimpleMeterRegistry())
        val base = BigDecimal("1")
        assertEquals("1.0:1:UP:LOW:MNA:AN:MORNING", cache.fingerprint(base, 10.0, "UP", "LOW", atrPercentile = -1, session = "MORNING"))
        assertEquals("1.0:1:UP:LOW:MNA:AL:MORNING", cache.fingerprint(base, 10.0, "UP", "LOW", atrPercentile = 25, session = "MORNING"))
        assertEquals("1.0:1:UP:LOW:MNA:AM:MORNING", cache.fingerprint(base, 10.0, "UP", "LOW", atrPercentile = 75, session = "MORNING"))
        assertEquals("1.0:1:UP:LOW:MNA:AH:MORNING", cache.fingerprint(base, 10.0, "UP", "LOW", atrPercentile = 76, session = "MORNING"))
    }

    @Test
    fun `fingerprint coerces rsi into zero to ten bucket`() {
        val redisTemplate: StringRedisTemplate = mock()
        val cache = cache(enabled = true, redisTemplate = redisTemplate, registry = SimpleMeterRegistry())
        val base = BigDecimal("1")
        val below = cache.fingerprint(base, -5.0, "UP", "LOW", session = "MORNING")
        val above = cache.fingerprint(base, 105.0, "UP", "LOW", session = "MORNING")
        val exactHundred = cache.fingerprint(base, 100.0, "UP", "LOW", session = "MORNING")
        assertEquals("1.0:0:UP:LOW:MNA:AN:MORNING", below)
        assertEquals("1.0:10:UP:LOW:MNA:AN:MORNING", above)
        assertEquals("1.0:10:UP:LOW:MNA:AN:MORNING", exactHundred)
    }

    @Test
    fun `fingerprint marks zero macd histogram as M0`() {
        val redisTemplate: StringRedisTemplate = mock()
        val cache = cache(enabled = true, redisTemplate = redisTemplate, registry = SimpleMeterRegistry())
        val fp = cache.fingerprint(BigDecimal("1"), 10.0, "UP", "LOW", macdHistogram = 0.0, session = "MORNING")
        assertTrue(fp.contains(":M0:"))
    }
}
