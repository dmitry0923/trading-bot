package com.trading.bot.integration

import com.trading.bot.infrastructure.llm.PromptTemplate
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.service.SettingsService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Chaos-тесты: отключение LLM (Kimi/сеть) → graceful degradation (roadmap 13.3.3).
 *
 * Проверяют, что при недоступном LLM-провайдере бот не падает:
 *   - пустой API-ключ → мгновенный fallback NO_API_KEY (без сетевого вызова);
 *   - недоступный endpoint → fallback CALL_ERROR (после ошибки соединения);
 *   - fallback-ответ детерминирован (NEUTRAL / signalStrength 0.0) и фиксируется
 *     метрикой `llm.fallback.activated{reason=...}`.
 *
 * Retry/CircuitBreaker/RateLimiter отключены, чтобы сценарий был детерминированным
 * (один запрос, мгновенная ошибка соединения на loopback).
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class ChaosLlmIntegrationTest {
    companion object {
        @Container
        val postgres = chaosPostgres(15434)

        @Container
        val redis = chaosRedis(16381)

        @DynamicPropertySource
        @JvmStatic
        @Suppress("unused") // Вызывается рефлексивно Spring TestContext через @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            val host = "127.0.0.1"

            registry.add("spring.datasource.url") { "jdbc:postgresql://$host:${postgres.firstMappedPort}/${postgres.databaseName}" }
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)

            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://$host:${postgres.firstMappedPort}/${postgres.databaseName}" }
            registry.add("spring.r2dbc.username", postgres::getUsername)
            registry.add("spring.r2dbc.password", postgres::getPassword)

            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379).toString() }

            registry.add("llm.api-key") { "" }
            registry.add("llm.router-ai-base-url") { "http://127.0.0.1:1/v1" }
            registry.add("llm.retry-enabled") { "false" }
            registry.add("llm.circuit-breaker-enabled") { "false" }
            registry.add("llm.rate-limiter-enabled") { "false" }
            registry.add("llm.timeout-sec") { "3" }
        }
    }

    @Autowired
    lateinit var llmClient: ResilientLlmClient

    @Autowired
    lateinit var settingsService: SettingsService

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    private val prompt =
        PromptTemplate(
            name = "chaos",
            version = "1",
            system = "Test system prompt.",
            userTemplate = "Test user prompt for {{ticker}}.",
        )

    private val variables = mapOf("ticker" to "SBER")

    @Test
    fun `blank api key degrades to fallback without network call`() {
        val response =
            runBlocking {
                llmClient.complete("technical", "SBER", prompt, variables, fingerprint = null)
            }

        assertTrue(response.isFallback, "пустой API-ключ → fallback, а не сетевой вызов")
        assertEquals("NO_API_KEY", response.errorMessage, "причина fallback = NO_API_KEY")
        assertTrue(response.content.contains("NEUTRAL"), "fallback-ответ детерминирован (NEUTRAL)")

        val fallbacks = meterRegistry.counter("llm.fallback.activated", Tags.of("agent", "technical", "reason", "NO_API_KEY")).count()
        assertTrue(fallbacks >= 1.0, "метрика llm.fallback.activated{reason=NO_API_KEY} зафиксирована, было: $fallbacks")
    }

    @Test
    fun `unreachable llm endpoint degrades to CALL_ERROR fallback`() {
        val original = settingsService.getSettings()
        try {
            runBlocking {
                settingsService.updateSettings(
                    original.copy(
                        llmApiKey = "sk-chaos",
                        llmBaseUrl = "http://127.0.0.1:1",
                    ),
                )
            }

            val response =
                runBlocking {
                    llmClient.complete("strategy", "SBER", prompt, variables, fingerprint = null)
                }

            assertTrue(response.isFallback, "недоступный LLM-endpoint → fallback, а не исключение наверх")
            assertNotNull(response.errorMessage, "CALL_ERROR содержит детали ошибки")
            assertTrue(response.content.contains("NEUTRAL"), "fallback-ответ детерминирован (NEUTRAL)")

            val fallbacks = meterRegistry.counter("llm.fallback.activated", Tags.of("agent", "strategy", "reason", "CALL_ERROR")).count()
            assertTrue(fallbacks >= 1.0, "метрика llm.fallback.activated{reason=CALL_ERROR} зафиксирована, было: $fallbacks")
        } finally {
            runBlocking {
                settingsService.updateSettings(original.copy(llmApiKey = "", llmBaseUrl = ""))
            }
        }
    }
}
