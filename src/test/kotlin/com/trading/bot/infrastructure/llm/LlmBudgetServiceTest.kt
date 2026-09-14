package com.trading.bot.infrastructure.llm

import com.trading.bot.config.LlmConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import java.util.Locale

/**
 * LlmBudgetService: резервирование бюджета под LLM-вызовы (Lua в Redis, fail-closed).
 * Покрытие: разрешение/отказ с причиной скрипта, отключённый конфиг → без Redis,
 * недоступность Redis → fail-closed отказ.
 *
 * Redis замокан как в DistributedLockServiceTest: один матчер Any на каждый ARGV
 * скрипта (скрипт отправляет 8 строковых аргументов).
 */
class LlmBudgetServiceTest {
    private fun redisTemplate(result: List<Any?>): StringRedisTemplate {
        val redis: StringRedisTemplate = Mockito.mock(StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        Mockito
            .`when`(
                redis.execute<List<Any?>>(
                    ArgumentMatchers.any<RedisScript<List<Any?>>>(),
                    ArgumentMatchers.anyList(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                ),
            ).thenReturn(result)
        return redis
    }

    private fun failingRedis(): StringRedisTemplate {
        val redis: StringRedisTemplate = Mockito.mock(StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        Mockito
            .`when`(
                redis.execute<List<Any?>>(
                    ArgumentMatchers.any<RedisScript<List<Any?>>>(),
                    ArgumentMatchers.anyList(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                ),
            ).thenThrow(RuntimeException("redis down"))
        return redis
    }

    private fun service(
        redis: StringRedisTemplate,
        registry: SimpleMeterRegistry,
        config: LlmConfig,
        enabled: Boolean,
    ): LlmBudgetService {
        config.budgetEnabled = enabled
        return LlmBudgetService(redis, registry, config)
    }

    @Test
    fun `reserve allows and records metric when script approves`() {
        val registry = SimpleMeterRegistry()

        val decision = service(redisTemplate(listOf("1", "")), registry, LlmConfig(), true).reserve("technical", "cache-1", 400)

        assertTrue(decision.allowed)
        assertNull(decision.reason)
        assertEquals(1.0, registry.counter("llm.budget.reserve").count())
    }

    @Test
    fun `reserve rejects over limit with script reason`() {
        val registry = SimpleMeterRegistry()

        val decision =
            service(redisTemplate(listOf("0", "TOKEN_BUDGET_EXCEEDED:MINUTE")), registry, LlmConfig(), true)
                .reserve("technical", "cache-1", 400)

        assertEquals(false, decision.allowed)
        assertEquals("TOKEN_BUDGET_EXCEEDED:MINUTE", decision.reason)
        assertEquals(1.0, registry.counter("llm.budget.reject", "agent", "technical", "reason", "TOKEN_BUDGET_EXCEEDED:MINUTE").count())
    }

    @Test
    fun `reserve is a no-op when budget disabled`() {
        val redis: StringRedisTemplate = Mockito.mock(StringRedisTemplate::class.java)
        val registry = SimpleMeterRegistry()

        val decision = service(redis, registry, LlmConfig(), false).reserve("technical", "cache-1", 400)

        assertTrue(decision.allowed)
        assertNull(decision.reason)
        Mockito.verifyNoInteractions(redis)
    }

    @Test
    fun `reserve is fail-closed when redis unavailable`() {
        val registry = SimpleMeterRegistry()

        val decision = service(failingRedis(), registry, LlmConfig(), true).reserve("technical", "cache-1", 400)

        assertEquals(false, decision.allowed)
        assertEquals("BUDGET_UNAVAILABLE", decision.reason)
        assertEquals(1.0, registry.counter("llm.budget.reject", "agent", "technical", "reason", "BUDGET_UNAVAILABLE").count())
    }

    @Test
    fun `reserve sends cost estimate with dot decimal separator regardless of locale`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale("ru", "RU"))
        try {
            val redis: StringRedisTemplate = Mockito.mock(StringRedisTemplate::class.java)
            @Suppress("UNCHECKED_CAST")
            Mockito
                .`when`(
                    redis.execute<List<Any?>>(
                        ArgumentMatchers.any<RedisScript<List<Any?>>>(),
                        ArgumentMatchers.anyList(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                    ),
                ).thenReturn(listOf("1", ""))

            service(redis, SimpleMeterRegistry(), LlmConfig(), true).reserve("technical", "cache-1", 400)

            Mockito
                .verify(redis)
                .execute<List<Any?>>(
                    ArgumentMatchers.any<RedisScript<List<Any?>>>(),
                    ArgumentMatchers.anyList(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any(),
                    ArgumentMatchers.argThat<String> { it.contains('.') },
                    Mockito.any(),
                    Mockito.any(),
                )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `estimate tokens is positive for non-empty prompts`() {
        val s = service(Mockito.mock(StringRedisTemplate::class.java), SimpleMeterRegistry(), LlmConfig(), true)

        assertEquals(4, s.estimateTokens("0123456789ab", "0123"))
        assertEquals(0, s.estimateTokens("", ""))
    }
}
