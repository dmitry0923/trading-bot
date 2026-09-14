package com.trading.bot.infrastructure.llm

import com.trading.bot.config.LlmConfig
import com.trading.bot.infrastructure.llm.LlmSingleFlight.AcquireResult
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript
import java.time.Duration

/**
 * LlmSingleFlight: один LLM-вызов на семантический ключ (SETNX + owner-token).
 * Покрытие: захват свободного/занятого лока, off-конфиг → без Redis, недоступность
 * Redis → Unavailable (fail-closed, review/P1), release снимает ТОЛЬКО свой токен.
 */
class LlmSingleFlightTest {
    private fun config(enabled: Boolean): LlmConfig =
        LlmConfig().apply {
            singleFlightEnabled = enabled
            singleFlightLockTtlSeconds = 30
        }

    private fun valueOps(
        redis: StringRedisTemplate,
        result: Boolean? = true,
    ): ValueOperations<String, String> {
        val ops: ValueOperations<String, String> = mock()
        Mockito.`when`(redis.opsForValue()).thenReturn(ops)
        if (result != null) {
            Mockito
                .`when`(
                    ops.setIfAbsent(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        ArgumentMatchers.any<Duration>(),
                    ),
                ).thenReturn(result)
        } else {
            Mockito
                .`when`(
                    ops.setIfAbsent(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        ArgumentMatchers.any<Duration>(),
                    ),
                ).thenThrow(RuntimeException("redis down"))
        }
        return ops
    }

    @Test
    fun `acquire returns Owner with token when lock is free and records metric`() {
        val redis: StringRedisTemplate = mock()
        valueOps(redis, result = true)
        val registry = SimpleMeterRegistry()

        val acquired = LlmSingleFlight(redis, registry, config(true)).acquire("technical:SBER:fp")

        assertTrue(acquired is AcquireResult.Owner)
        assertTrue((acquired as AcquireResult.Owner).token.isNotBlank())
        assertEquals(1.0, registry.counter("llm.single_flight.acquired").count())
    }

    @Test
    fun `acquire returns Busy when another call is in flight`() {
        val redis: StringRedisTemplate = mock()
        valueOps(redis, result = false)

        val acquired = LlmSingleFlight(redis, SimpleMeterRegistry(), config(true)).acquire("technical:SBER:fp")

        assertEquals(AcquireResult.Busy, acquired)
    }

    @Test
    fun `acquire returns null passthrough when single-flight disabled`() {
        val redis: StringRedisTemplate = mock()
        val ops: ValueOperations<String, String> = mock()
        Mockito.`when`(redis.opsForValue()).thenReturn(ops)
        SimpleMeterRegistry()

        val acquired = LlmSingleFlight(redis, SimpleMeterRegistry(), config(false)).acquire("technical:SBER:fp")

        assertEquals(null, acquired)
        Mockito.verify(redis, Mockito.never()).opsForValue()
    }

    @Test
    fun `redis failure yields Unavailable fail-closed`() {
        val redis: StringRedisTemplate = mock()
        valueOps(redis, result = null)
        val registry = SimpleMeterRegistry()

        val acquired = LlmSingleFlight(redis, registry, config(true)).acquire("technical:SBER:fp")

        assertEquals(AcquireResult.Unavailable, acquired)
        assertEquals(1.0, registry.counter("llm.single_flight.unavailable").count())
    }

    @Test
    fun `release runs compare-and-delete script with owner token`() {
        val redis: StringRedisTemplate = mock()
        val ops: ValueOperations<String, String> = mock()
        Mockito.`when`(redis.opsForValue()).thenReturn(ops)
        Mockito
            .`when`(
                ops.setIfAbsent(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    ArgumentMatchers.any<Duration>(),
                ),
            ).thenReturn(true)
        val registry = SimpleMeterRegistry()

        val acquired = LlmSingleFlight(redis, registry, config(true)).acquire("technical:SBER:fp") as AcquireResult.Owner
        LlmSingleFlight(redis, registry, config(true)).release("technical:SBER:fp", ownerToken = acquired.token)

        Mockito
            .verify(redis, Mockito.atLeastOnce())
            .execute(
                ArgumentMatchers.any(RedisScript::class.java),
                ArgumentMatchers.anyList(),
                ArgumentMatchers.eq(acquired.token),
            )
        assertEquals(1.0, registry.counter("llm.single_flight.released").count())
    }

    @Test
    fun `release without owner token does not touch redis`() {
        val redis: StringRedisTemplate = mock()
        valueOps(redis, result = true)
        val registry = SimpleMeterRegistry()

        LlmSingleFlight(redis, registry, config(true)).release("technical:SBER:fp", ownerToken = null)

        Mockito.verify(redis, Mockito.never()).execute(
            ArgumentMatchers.any(RedisScript::class.java),
            ArgumentMatchers.anyList<String>(),
            ArgumentMatchers.anyString(),
        )
        assertEquals(0.0, registry.counter("llm.single_flight.released").count())
    }
}
