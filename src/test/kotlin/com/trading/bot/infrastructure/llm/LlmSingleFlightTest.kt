package com.trading.bot.infrastructure.llm

import com.trading.bot.config.LlmConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript
import java.time.Duration

/**
 * LlmSingleFlight: один LLM-вызов на семантический ключ (SETNX).
 * Покрытие: захват свободного/занятого лока, off-конфиг → без Redis,
 * недоступность Redis → не блокируем критический путь, release через Redis script.
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
    fun `acquire returns true when lock is free and records metric`() {
        val redis: StringRedisTemplate = mock()
        valueOps(redis, result = true)
        val registry = SimpleMeterRegistry()

        val acquired = LlmSingleFlight(redis, registry, config(true)).acquire("technical:SBER:fp")

        assertEquals(true, acquired)
        assertEquals(1.0, registry.counter("llm.single_flight.acquired").count())
    }

    @Test
    fun `acquire returns false when another call is in flight`() {
        val redis: StringRedisTemplate = mock()
        valueOps(redis, result = false)

        val acquired = LlmSingleFlight(redis, SimpleMeterRegistry(), config(true)).acquire("technical:SBER:fp")

        assertEquals(false, acquired)
    }

    @Test
    fun `acquire is passthrough when single-flight disabled`() {
        val redis: StringRedisTemplate = mock()
        val ops: ValueOperations<String, String> = mock()
        Mockito.`when`(redis.opsForValue()).thenReturn(ops)
        SimpleMeterRegistry()

        val acquired = LlmSingleFlight(redis, SimpleMeterRegistry(), config(false)).acquire("technical:SBER:fp")

        assertEquals(true, acquired)
        Mockito.verify(redis, Mockito.never()).opsForValue()
    }

    @Test
    fun `redis failure still permits the call`() {
        val redis: StringRedisTemplate = mock()
        valueOps(redis, result = null)

        val acquired = LlmSingleFlight(redis, SimpleMeterRegistry(), config(true)).acquire("technical:SBER:fp")

        assertEquals(true, acquired)
    }

    @Test
    fun `release runs compare-and-delete script in redis`() {
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

        LlmSingleFlight(redis, registry, config(true)).acquire("technical:SBER:fp")
        LlmSingleFlight(redis, registry, config(true)).release("technical:SBER:fp")

        Mockito
            .verify(redis, Mockito.atLeastOnce())
            .execute(
                ArgumentMatchers.any(RedisScript::class.java),
                ArgumentMatchers.anyList(),
                ArgumentMatchers.anyString(),
            )
        assertEquals(1.0, registry.counter("llm.single_flight.released").count())
    }
}
