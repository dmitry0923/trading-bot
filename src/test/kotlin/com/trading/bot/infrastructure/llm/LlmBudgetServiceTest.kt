package com.trading.bot.infrastructure.llm

import com.trading.bot.config.LlmConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
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

    @Test
    fun `reserve script is redis 6 compatible and sets ttl on every budget key`() {
        val captured = captureReserveCall()

        // `EXPIRE key ttl NX` появился только в Redis 7: на Redis 6 скрипт падал
        // и КАЖДЫЙ LLM-вызов уходил в fail-closed BUDGET_UNAVAILABLE.
        assertFalse(captured.script.contains("'NX'"), "EXPIRE ... NX requires Redis 7: ${captured.script}")
        val expiries = Regex("EXPIRE', KEYS\\[(\\d)]").findAll(captured.script).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("1", "2", "3", "4"),
            expiries,
            "TTL must be set on minute/cycle/day/cost keys (day keys would leak otherwise)",
        )
        // ARGV: 1 estimate, 2-5 limits, 6 cost, 7 minuteTtl, 8 cycleTtl, 9 dayTtl
        assertEquals(9, captured.args.size)
        assertEquals("120", captured.args[6])
        assertEquals("600", captured.args[7])
        assertEquals("172800", captured.args[8])
    }

    private data class Captured(
        val script: String,
        val args: List<String>,
    )

    /** Перехватывает реальный вызов скрипта: текст Lua и ARGV. */
    private fun captureReserveCall(): Captured {
        val redis: StringRedisTemplate = Mockito.mock(StringRedisTemplate::class.java)
        val captured = arrayOfNulls<Captured>(1)
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
                    Mockito.any(),
                ),
            ).thenAnswer { invocation ->
                val script = invocation.getArgument<RedisScript<List<Any?>>>(0)
                val source = (script as DefaultRedisScript<*>).scriptAsString
                val args = (1..9).map { index -> invocation.getArgument<Any?>(index + 1)?.toString().orEmpty() }
                captured[0] = Captured(script = source, args = args)
                listOf("1", "")
            }
        service(redis, SimpleMeterRegistry(), LlmConfig(), true).reserve("technical", "cache-1", 400)
        return requireNotNull(captured[0])
    }
}
