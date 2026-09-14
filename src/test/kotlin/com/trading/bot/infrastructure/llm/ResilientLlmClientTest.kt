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
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress

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
}
