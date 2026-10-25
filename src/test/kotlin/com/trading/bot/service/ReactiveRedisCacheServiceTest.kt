package com.trading.bot.service

import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.FeedbackCacheEntry
import com.trading.bot.model.entity.Strategy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.check
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.ReactiveRedisCallback
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicReference

/**
 * [ReactiveRedisCacheService]: кэш стратегий и feedback поверх
 * [ReactiveStringRedisTemplate].
 *
 * Redis не поднимается — мокаются `opsForValue()` и `execute()`. Проверяются обе
 * ветки каждой операции: штатный обмен (ключ, TTL, (де)сериализация) и деградация
 * при недоступном/сломанном Redis (операция не падает и возвращает null/false —
 * иначе сбой кэша валил бы торговый цикл).
 */
class ReactiveRedisCacheServiceTest {
    private val objectMapper = jacksonObjectMapper()
    private val valueOps: ReactiveValueOperations<String, String> = mock()
    private val template: ReactiveStringRedisTemplate = mock()
    private val service = ReactiveRedisCacheService(template, objectMapper)

    private fun strategy(ticker: String = "SBER"): Strategy =
        Strategy(
            ticker = ticker,
            action = StrategyAction.BUY,
            targetPrice = BigDecimal("100"),
            quantity = 1,
            signalStrength = 0.7,
            reasoning = "test",
            cycleId = "cycle-1",
            validUntil = LocalDateTime.of(2026, 10, 25, 12, 0),
        )

    private fun stubRedis(get: String?) {
        whenever(template.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.get(anyOrNull<String>())).thenReturn(Mono.justOrEmpty(get))
        whenever(valueOps.set(anyOrNull<String>(), anyOrNull<String>(), anyOrNull<Duration>())).thenReturn(Mono.just(true))
    }

    @Test
    fun `saveStrategy writes serialized strategy under strategy prefix with 15 minute ttl`() {
        stubRedis(get = null)
        val captured = AtomicReference<String>()

        runBlocking {
            service.saveStrategy(strategy("GAZP"))
            verify(valueOps).set(
                eq("strategy:GAZP"),
                check<String> { captured.set(it) },
                eq(Duration.ofMinutes(15)),
            )
        }

        assertTrue(captured.get().contains("\"ticker\":\"GAZP\""))
    }

    @Test
    fun `saveStrategy swallows redis failure`() {
        whenever(template.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.set(anyOrNull<String>(), anyOrNull<String>(), anyOrNull<Duration>()))
            .doThrow(IllegalStateException("redis down"))

        // Не должно бросить: сбой кэша не ломает торговый цикл
        runBlocking { service.saveStrategy(strategy()) }
    }

    @Test
    fun `getStrategy returns deserialized strategy`() {
        stubRedis(get = objectMapper.writeValueAsString(strategy("SBER")))

        val loaded = runBlocking { service.getStrategy("SBER") }

        assertEquals("SBER", loaded?.ticker)
        assertEquals(StrategyAction.BUY, loaded?.action)
        runBlocking { verify(valueOps).get(eq("strategy:SBER")) }
    }

    @Test
    fun `getStrategy returns null when key is missing`() {
        stubRedis(get = null)

        assertNull(runBlocking { service.getStrategy("SBER") })
    }

    @Test
    fun `getStrategy returns null when redis throws`() {
        whenever(template.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.get(anyOrNull<String>())).doThrow(IllegalStateException("redis down"))

        assertNull(runBlocking { service.getStrategy("SBER") })
    }

    @Test
    fun `getStrategy returns null when cached payload is not deserializable`() {
        stubRedis(get = "{not-json")

        assertNull(runBlocking { service.getStrategy("SBER") })
    }

    @Test
    fun `getAllStrategies skips tickers without cache entry`() {
        whenever(template.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.get(eq("strategy:SBER")))
            .thenReturn(Mono.just(objectMapper.writeValueAsString(strategy("SBER"))))
        whenever(valueOps.get(eq("strategy:GAZP"))).thenReturn(Mono.empty())

        val all = runBlocking { service.getAllStrategies(listOf("SBER", "GAZP")) }

        assertEquals(setOf("SBER"), all.keys)
        assertEquals("SBER", all.getValue("SBER").ticker)
    }

    @Test
    fun `saveFeedback writes entry under feedback prefix with 60 minute ttl`() {
        stubRedis(get = null)
        val captured = AtomicReference<String>()

        runBlocking {
            service.saveFeedback("SBER", "{\"ok\":true}", "hash-1")
            verify(valueOps).set(
                eq("feedback:SBER"),
                check<String> { captured.set(it) },
                eq(Duration.ofMinutes(60)),
            )
        }

        val entry = objectMapper.readValue(captured.get(), FeedbackCacheEntry::class.java)
        assertEquals("SBER", entry.ticker)
        assertEquals("hash-1", entry.statsHash)
        assertEquals("{\"ok\":true}", entry.feedbackJson)
    }

    @Test
    fun `saveFeedback swallows redis failure`() {
        whenever(template.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.set(anyOrNull<String>(), anyOrNull<String>(), anyOrNull<Duration>()))
            .doThrow(IllegalStateException("redis down"))

        runBlocking { service.saveFeedback("SBER", "{}", "hash-1") }
    }

    @Test
    fun `getFeedback returns payload when stats hash matches`() {
        stubRedis(get = objectMapper.writeValueAsString(FeedbackCacheEntry("SBER", "{\"feedback\":1}", "hash-1")))

        assertEquals(
            "{\"feedback\":1}",
            runBlocking { service.getFeedback("SBER", "hash-1") },
        )
    }

    @Test
    fun `getFeedback invalidates entry when stats hash changed`() {
        stubRedis(get = objectMapper.writeValueAsString(FeedbackCacheEntry("SBER", "{\"feedback\":1}", "hash-1")))

        assertNull(runBlocking { service.getFeedback("SBER", "hash-2") })
    }

    @Test
    fun `getFeedback returns null when redis is empty`() {
        stubRedis(get = null)

        assertNull(runBlocking { service.getFeedback("SBER", "hash-1") })
    }

    @Test
    fun `getFeedback returns null on redis error`() {
        whenever(template.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.get(anyOrNull<String>())).doThrow(IllegalStateException("redis down"))

        assertNull(runBlocking { service.getFeedback("SBER", "hash-1") })
    }

    @Test
    fun `isAvailable reports true when ping answered`() {
        whenever(template.execute(any<ReactiveRedisCallback<Boolean>>())).thenReturn(Flux.just(true))

        assertTrue(runBlocking { service.isAvailable() })
    }

    @Test
    fun `isAvailable reports false when ping yields nothing`() {
        whenever(template.execute(any<ReactiveRedisCallback<Boolean>>())).thenReturn(Flux.empty())

        assertFalse(runBlocking { service.isAvailable() })
    }

    @Test
    fun `isAvailable reports false when redis throws`() {
        whenever(template.execute(any<ReactiveRedisCallback<Boolean>>()))
            .doThrow(IllegalStateException("redis down"))

        assertFalse(runBlocking { service.isAvailable() })
    }

    @Test
    fun `reading strategies never writes to redis`() {
        stubRedis(get = null)

        runBlocking { service.getStrategy("SBER") }
        runBlocking { verify(valueOps, never()).set(anyOrNull<String>(), anyOrNull<String>(), anyOrNull<Duration>()) }
    }
}
