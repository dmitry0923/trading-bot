package com.trading.bot.infrastructure.llm

import com.sun.net.httpserver.HttpServer
import com.trading.bot.config.LlmConfig
import com.trading.bot.config.TraceStorageConfig
import com.trading.bot.infrastructure.tracing.TraceStorage
import com.trading.bot.model.entity.BotSettings
import com.trading.bot.service.SettingsService
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.Duration

/**
 * Ретгресс-тест бага декораторов [ResilientLlmClient] (этап 3, risk / R3).
 *
 * Раньше [ResilientLlmClient.decoratedCall] строил цепочку через живую `var call` и
 * замыкание `{ call() }`. Kotlin-лямбда читает переменную в МОМЕНТ ВЫЗОВА, поэтому после
 * `call = retry.decorateSuspendFunction { call() }` повторный вызов обёртки замыкался на
 * НОВУЮ `call` (саму себя) -> бесконечная рекурсия -> StackOverflowError при ЛЮБОМ включённом
 * декораторе (в проде retry/rateLimiter/circuitBreaker включены по умолчанию).
 *
 * Тест прогоняет реальный HTTP-вызов к локальному JDK HttpServer со ВСЕМИ тремя декораторами
 * включёнными: с багом метод падал бы StackOverflowError, с фиксом — возвращает LlmResponse.
 */
class ResilientLlmClientTest {
    private fun jsonResponse(
        content: String,
        tokens: Int,
    ): String = """{"choices":[{"message":{"content":"$content"}}],"usage":{"total_tokens":$tokens}}"""

    @Test
    fun `all resilience decorators enabled does not recurse infinitely`() =
        runBlocking {
            val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/chat/completions") { exchange ->
                val body = jsonResponse("ok", 12).toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val port = server.address.port
                val settings = BotSettings().copy(llmProvider = "KIMI", llmBaseUrl = "http://127.0.0.1:$port", llmApiKey = "test-key")
                val settingsService = mock<SettingsService>()
                whenever(settingsService.getSettings()).thenReturn(settings)

                val llmConfig =
                    LlmConfig().apply {
                        retryEnabled = true
                        rateLimiterEnabled = true
                        circuitBreakerEnabled = true
                        timeoutSec = 5
                    }
                val client =
                    ResilientLlmClient(
                        llmConfig = llmConfig,
                        semanticCache = mock(),
                        objectMapper = ObjectMapper(),
                        meterRegistry = SimpleMeterRegistry(),
                        circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults(),
                        rateLimiterRegistry = RateLimiterRegistry.ofDefaults(),
                        retryRegistry = RetryRegistry.ofDefaults(),
                        settingsService = settingsService,
                        traceStorage = mock<TraceStorage>(),
                        traceStorageConfig = TraceStorageConfig().apply { enabled = false },
                        llmBudgetService = null,
                        llmSingleFlight = null,
                    )

                val response =
                    client.complete(
                        agent = "technical",
                        ticker = "SBER",
                        prompt = PromptTemplate(name = "test", version = "1", system = "sys", userTemplate = "user"),
                        variables = emptyMap(),
                        fingerprint = null,
                        temperature = 0.1,
                    )

                assertEquals("ok", response.content)
                assertFalse(response.isFallback)
                assertEquals(12, response.tokensUsed)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `single-flight waiter is not charged to the LLM budget`(): Unit =
        runBlocking {
            // Дефект: бюджет резервировался ДО single-flight, поэтому ожидающий
            // (который LLM не вызывал и получил SINGLE_FLIGHT_BUSY) списывал токены.
            val registry = SimpleMeterRegistry()
            val budgetRedis: StringRedisTemplate = mock()
            stubReserve(budgetRedis, listOf("1", ""))
            val config =
                LlmConfig().apply {
                    budgetEnabled = true
                    singleFlightEnabled = true
                    singleFlightWaitMs = 30
                    semanticCacheEnabled = true
                    retryEnabled = false
                    circuitBreakerEnabled = false
                    rateLimiterEnabled = false
                }
            val budget = LlmBudgetService(budgetRedis, registry, config)

            val busyRedis: StringRedisTemplate = mock()
            val valueOps: ValueOperations<String, String> = mock()
            Mockito.`when`(busyRedis.opsForValue()).thenReturn(valueOps)
            Mockito
                .`when`(
                    valueOps.setIfAbsent(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        ArgumentMatchers.any<Duration>(),
                    ),
                ).thenReturn(false)

            val settingsService = mock<SettingsService>()
            whenever(settingsService.getSettings()).thenReturn(
                BotSettings().copy(llmProvider = "KIMI", llmBaseUrl = "http://127.0.0.1:1", llmApiKey = "test-key"),
            )
            val semanticCache = mock<SemanticCache>()
            whenever(
                semanticCache.key(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    anyOrNull(),
                    anyOrNull(),
                ),
            ).thenReturn("cache-key")
            val client =
                ResilientLlmClient(
                    llmConfig = config,
                    semanticCache = semanticCache,
                    objectMapper = ObjectMapper(),
                    meterRegistry = registry,
                    circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults(),
                    rateLimiterRegistry = RateLimiterRegistry.ofDefaults(),
                    retryRegistry = RetryRegistry.ofDefaults(),
                    settingsService = settingsService,
                    traceStorage = mock<TraceStorage>(),
                    traceStorageConfig = TraceStorageConfig().apply { enabled = false },
                    llmBudgetService = budget,
                    llmSingleFlight = LlmSingleFlight(busyRedis, registry, config),
                )

            val response =
                client.complete(
                    agent = "technical",
                    ticker = "SBER",
                    prompt = PromptTemplate(name = "test", version = "1", system = "sys", userTemplate = "user"),
                    variables = emptyMap(),
                    fingerprint = "fp",
                    temperature = 0.1,
                )

            assertTrue(response.isFallback, "waiter must not receive a real LLM answer")
            assertEquals("SINGLE_FLIGHT_BUSY", response.errorMessage)
            assertEquals(
                null,
                registry.find("llm.budget.reserve").counter(),
                "waiting participant must not reserve budget for someone else's call",
            )
        }

    @Test
    fun `owner reserves budget before calling the provider`(): Unit =
        runBlocking {
            val registry = SimpleMeterRegistry()
            val budgetRedis: StringRedisTemplate = mock()
            stubReserve(budgetRedis, listOf("0", "TOKEN_BUDGET_EXCEEDED:MINUTE"))
            val config =
                LlmConfig().apply {
                    budgetEnabled = true
                    retryEnabled = false
                    circuitBreakerEnabled = false
                    rateLimiterEnabled = false
                }
            val budget = LlmBudgetService(budgetRedis, registry, config)
            val settingsService = mock<SettingsService>()
            whenever(settingsService.getSettings()).thenReturn(
                BotSettings().copy(llmProvider = "KIMI", llmBaseUrl = "http://127.0.0.1:1", llmApiKey = "test-key"),
            )
            val client =
                ResilientLlmClient(
                    llmConfig = config,
                    semanticCache = mock(),
                    objectMapper = ObjectMapper(),
                    meterRegistry = registry,
                    circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults(),
                    rateLimiterRegistry = RateLimiterRegistry.ofDefaults(),
                    retryRegistry = RetryRegistry.ofDefaults(),
                    settingsService = settingsService,
                    traceStorage = mock<TraceStorage>(),
                    traceStorageConfig = TraceStorageConfig().apply { enabled = false },
                    llmBudgetService = budget,
                    llmSingleFlight = null,
                )

            val response =
                client.complete(
                    agent = "technical",
                    ticker = "SBER",
                    prompt = PromptTemplate(name = "test", version = "1", system = "sys", userTemplate = "user"),
                    variables = emptyMap(),
                    fingerprint = null,
                    temperature = 0.1,
                )

            assertEquals("TOKEN_BUDGET_EXCEEDED:MINUTE", response.errorMessage)
            assertEquals(1.0, registry.counter("llm.budget.reject", "agent", "technical", "reason", "TOKEN_BUDGET_EXCEEDED:MINUTE").count())
        }

    /** Стаб Lua-скрипта резервирования бюджета (9 ARGV). */
    private fun stubReserve(
        redis: StringRedisTemplate,
        result: List<Any?>,
    ) {
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
    }
}
