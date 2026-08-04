package com.trading.bot.infrastructure.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.config.LlmConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal

@Testcontainers
class SemanticCacheTest {
    companion object {
        @Container
        @JvmStatic
        val redis = GenericContainer("redis:7-alpine").withExposedPorts(6379)
    }

    private lateinit var cache: SemanticCache

    @BeforeEach
    fun setup() {
        val factory =
            LettuceConnectionFactory(
                RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379)),
            )
        factory.afterPropertiesSet()
        val template = StringRedisTemplate(factory)
        template.afterPropertiesSet()
        template.connectionFactory!!
            .connection
            .serverCommands()
            .flushAll()

        val config =
            LlmConfig().apply {
                semanticCacheEnabled = true
                semanticCacheTtlMinutes = 10
            }
        cache = SemanticCache(template, jacksonObjectMapper(), SimpleMeterRegistry(), config)
    }

    @Test
    fun `fingerprint is stable for identical inputs`() {
        val a = cache.fingerprint(BigDecimal("280.50"), 62.4, "UP", "LOW_VOLATILITY", session = "MORNING")
        val b = cache.fingerprint(BigDecimal("280.50"), 62.4, "UP", "LOW_VOLATILITY", session = "MORNING")
        assertEquals(a, b)
    }

    @Test
    fun `fingerprint changes when market situation changes`() {
        val a = cache.fingerprint(BigDecimal("280.50"), 62.4, "UP", "LOW_VOLATILITY", session = "MORNING")
        val b = cache.fingerprint(BigDecimal("280.50"), 30.1, "UP", "LOW_VOLATILITY", session = "MORNING")
        val c = cache.fingerprint(BigDecimal("290.00"), 62.4, "UP", "LOW_VOLATILITY", session = "MORNING")
        val d = cache.fingerprint(BigDecimal("280.50"), 62.4, "UP", "LOW_VOLATILITY", session = "EVENING")
        assertFalse(a == b)
        assertFalse(a == c)
        assertFalse(a == d)
    }

    @Test
    fun `fingerprint rounds price and buckets rsi`() {
        val byPrice = cache.fingerprint(BigDecimal("280.54"), 62.4, "UP", "LOW_VOLATILITY", session = "MORNING")
        val priceRounded = cache.fingerprint(BigDecimal("280.50"), 62.4, "UP", "LOW_VOLATILITY", session = "MORNING")
        assertEquals(byPrice, priceRounded)

        // RSI-бакет (по 10 пунктов): 62.9 и 62.6 -> один бакет 6
        val byRsi = cache.fingerprint(BigDecimal("280.50"), 62.9, "UP", "LOW_VOLATILITY", session = "MORNING")
        val rsiRounded = cache.fingerprint(BigDecimal("280.50"), 62.6, "UP", "LOW_VOLATILITY", session = "MORNING")
        assertEquals(byRsi, rsiRounded)
        assertEquals("280.5:6:UP:LOW_VOLATILITY:MNA:AN:MORNING", byRsi)
    }

    @Test
    fun `fingerprint distinguishes macd direction`() {
        val up = cache.fingerprint(BigDecimal("280.50"), 62.0, "UP", "LOW_VOLATILITY", macdHistogram = 1.2, session = "MORNING")
        val down = cache.fingerprint(BigDecimal("280.50"), 62.0, "UP", "LOW_VOLATILITY", macdHistogram = -1.2, session = "MORNING")
        assertFalse(up == down)
        assertTrue(up.contains(":M+:"))
        assertTrue(down.contains(":M-:"))
    }

    @Test
    fun `generic fingerprint joins components`() {
        val fp = cache.genericFingerprint("16.0", "75.0", "90.0")
        assertEquals("16.0:75.0:90.0", fp)
        val withNull = cache.genericFingerprint("16.0", null, "90.0")
        assertEquals("16.0:NA:90.0", withNull)
    }

    @Test
    fun `key is deterministic sha256`() {
        val k1 = cache.key("technical", "SBER", "280.5:62:UP:LOW_VOLATILITY")
        val k2 = cache.key("technical", "SBER", "280.5:62:UP:LOW_VOLATILITY")
        val k3 = cache.key("technical", "SBER", "280.5:63:UP:LOW_VOLATILITY")
        assertEquals(k1, k2)
        assertFalse(k1 == k3)
        assertTrue(k1.startsWith("llm:semantic:"))
        assertEquals(64 + "llm:semantic:".length, k1.length)
    }

    @Test
    fun `put then get returns stored response`() {
        val resp =
            LlmResponse(
                content = """{"conclusion":"BULLISH","confidence":0.8,"reasoning":"trend up"}""",
                tokensUsed = 123,
                latencyMs = 456,
                model = "kimi-k3",
            )
        cache.put("technical", "SBER", "280.5:62:UP:LOW_VOLATILITY", resp)

        val cached = cache.get("technical", "SBER", "280.5:62:UP:LOW_VOLATILITY")
        assertNotNull(cached)
        assertEquals(resp.content, cached!!.content)
        assertEquals(123, cached.tokensUsed)
        assertEquals("kimi-k3", cached.model)
        assertTrue(cached.fromCache)
    }

    @Test
    fun `get on miss returns null`() {
        val result = cache.get("technical", "SBER", "280.5:62:UP:LOW_VOLATILITY")
        assertNull(result)
    }

    @Test
    fun `fallback responses are not cached`() {
        cache.put("technical", "SBER", "280.5:62:UP:LOW_VOLATILITY", LlmResponse.fallback("NO_API_KEY"))
        assertNull(cache.get("technical", "SBER", "280.5:62:UP:LOW_VOLATILITY"))
    }
}
