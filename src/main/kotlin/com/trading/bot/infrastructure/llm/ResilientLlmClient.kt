package com.trading.bot.infrastructure.llm

import com.trading.bot.config.LlmConfig
import com.trading.bot.config.LlmProvider
import com.trading.bot.config.TraceStorageConfig
import com.trading.bot.infrastructure.tracing.LlmTrace
import com.trading.bot.infrastructure.tracing.TraceContext
import com.trading.bot.infrastructure.tracing.TraceStorage
import com.trading.bot.service.SettingsService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.kotlin.circuitbreaker.decorateSuspendFunction
import io.github.resilience4j.kotlin.ratelimiter.decorateSuspendFunction
import io.github.resilience4j.kotlin.retry.decorateSuspendFunction
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Отказоустойчивый LLM-клиент.
 *
 * - Гибкий провайдер: RouterAI (по умолчанию) / Kimi / DeepSeek / Qwen.
 *   Активный провайдер и модель переключаются через UI/настройки
 *   (см. [com.trading.bot.service.SettingsService]).
 * - Circuit Breaker / Rate Limiter / Retry через Resilience4j (конфиг в application.yml: resilience4j.*)
 * - Очередь запросов [LlmRequestQueue]: ограничение параллельных вызовов + FIFO
 * - Таймаут HTTP 30 секунд
 * - response_format = {"type":"json_object"} — принудительный JSON
 * - Semantic Cache (Redis) поверх вызовов
 * - Fallback: JSON с conclusion=NEUTRAL, confidence=0.0 при недоступности LLM
 * - Метрики: llm.latency, llm.tokens.used, llm.fallback.activated, llm.cache.hit/miss
 */
@Component
class ResilientLlmClient(
    private val llmConfig: LlmConfig,
    private val semanticCache: SemanticCache,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
    private val rateLimiterRegistry: RateLimiterRegistry,
    private val retryRegistry: RetryRegistry,
    private val settingsService: SettingsService,
    private val traceStorage: TraceStorage,
    private val traceStorageConfig: TraceStorageConfig,
) {
    private val logger = KotlinLogging.logger {}

    private val llmQueue =
        LlmRequestQueue(
            capacity = llmConfig.queueCapacity,
            concurrency = llmConfig.queueConcurrency,
        )

    private val webClient: WebClient =
        WebClient
            .builder()
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient
                        .create()
                        .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                        .responseTimeout(Duration.ofSeconds(llmConfig.timeoutSec)),
                ),
            ).build()

    private data class ResolvedEndpoint(
        val provider: LlmProvider,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
    )

    /**
     * Выполняет LLM-вызов с resilience-обвязкой и semantic cache.
     *
     * @param agent       имя агента (technical, strategy, arbitrator, ...) — для метрик и ключей кэша
     * @param ticker      тикер
     * @param prompt      шаблон из PromptRegistry
     * @param variables   переменные для рендера {{var}}
     * @param fingerprint семантический отпечаток рынка (null — кэш отключён для этого вызова)
     * @param temperature температура генерации
     * @param cacheNamespace изолирует semantic cache (например "backtest"); null — общий (live) кэш
     */
    suspend fun complete(
        agent: String,
        ticker: String,
        prompt: PromptTemplate,
        variables: Map<String, Any>,
        fingerprint: String? = null,
        temperature: Double = llmConfig.temperature,
        cacheNamespace: String? = null,
    ): LlmResponse {
        val endpoint = resolveEndpoint()

        if (endpoint.apiKey.isBlank()) {
            meterRegistry.counter("llm.fallback.activated", Tags.of("agent", agent, "reason", "NO_API_KEY")).increment()
            return LlmResponse.fallback("NO_API_KEY")
        }

        // Весь вызов выполняется в MDC-контексте с agent: trace_id наследуется
        // от родительской корутины цикла (см. TraceContext / StrategyService),
        // поэтому каждый JSON-лог и трейс привязаны к конкретному агенту и циклу.
        return TraceContext.withMdc(mapOf(TraceContext.AGENT to agent)) {
            if (fingerprint != null) {
                semanticCache.get(agent, ticker, fingerprint, cacheNamespace)?.let { return@withMdc it }
            }

            val system = prompt.renderSystem(variables)
            val user = prompt.renderUser(variables)

            val response =
                try {
                    llmQueue.submit { decoratedCall { callLlm(endpoint, system, user, temperature, agent) } }
                } catch (e: Exception) {
                    logger.warn(e) { "LLM call failed for agent=$agent ticker=$ticker" }
                    meterRegistry.counter("llm.fallback.activated", Tags.of("agent", agent, "reason", "CALL_ERROR")).increment()
                    LlmResponse.fallback("CALL_ERROR", e.message)
                }

            // Полный трейс (промпты + ответ) в S3/MinIO; storage_key попадает
            // в ответ, semantic cache и agent_logs — без хранения сырых промптов в БД.
            val finalResponse =
                response.copy(storageKey = persistTrace(agent, ticker, fingerprint, system, user, response, endpoint))

            if (fingerprint != null && !finalResponse.isFallback) {
                semanticCache.put(agent, ticker, fingerprint, finalResponse, cacheNamespace)
            }
            finalResponse
        }
    }

    /**
     * Определяет активный провайдер: приоритет у настроек из UI (SettingsService),
     * иначе значения из application.yml.
     */
    private fun resolveEndpoint(): ResolvedEndpoint {
        val settings = settingsService.getSettings()
        val provider = settings.llmProvider() ?: llmConfig.provider
        val (defaultBaseUrl, defaultModel) = llmConfig.endpointFor(provider)
        val baseUrl = settings.llmBaseUrl.takeIf { it.isNotBlank() } ?: defaultBaseUrl
        val model = settings.llmModel.takeIf { it.isNotBlank() } ?: defaultModel
        val apiKey = settings.llmApiKey.takeIf { it.isNotBlank() } ?: llmConfig.apiKey
        return ResolvedEndpoint(provider = provider, baseUrl = baseUrl, model = model, apiKey = apiKey)
    }

    /**
     * Сохраняет полный трейс LLM-вызова в S3/MinIO (best-effort, см. [TraceStorage]).
     *
     * @return storage_key объекта в хранилище, либо null если хранение отключено/не удалось
     */
    private suspend fun persistTrace(
        agent: String,
        ticker: String,
        fingerprint: String?,
        system: String,
        user: String,
        response: LlmResponse,
        endpoint: ResolvedEndpoint,
    ): String? {
        if (!traceStorageConfig.enabled) return null
        val trace =
            LlmTrace(
                traceId = TraceContext.traceId(),
                ticker = ticker,
                agent = agent,
                provider = endpoint.provider.name,
                model = response.model.ifBlank { endpoint.model },
                fingerprint = fingerprint,
                systemPrompt = system,
                userPrompt = user,
                responseContent = response.content,
                tokensUsed = response.tokensUsed,
                latencyMs = response.latencyMs,
                isFallback = response.isFallback,
                fromCache = response.fromCache,
                errorMessage = response.errorMessage,
            )
        return traceStorage.save(trace)
    }

    /**
     * Декорирует вызов: Retry (внутри) → RateLimiter → CircuitBreaker (снаружи).
     */
    private suspend fun decoratedCall(block: suspend () -> LlmResponse): LlmResponse {
        var call: suspend () -> LlmResponse = block
        if (llmConfig.retryEnabled) {
            val retry = retryRegistry.retry("llm")
            call = retry.decorateSuspendFunction { call() }
        }
        if (llmConfig.rateLimiterEnabled) {
            val limiter = rateLimiterRegistry.rateLimiter("llm")
            call = limiter.decorateSuspendFunction { call() }
        }
        if (llmConfig.circuitBreakerEnabled) {
            val breaker = circuitBreakerRegistry.circuitBreaker("llm")
            call = breaker.decorateSuspendFunction { call() }
        }
        return call()
    }

    private suspend fun callLlm(
        endpoint: ResolvedEndpoint,
        system: String,
        user: String,
        temperature: Double,
        agent: String,
    ): LlmResponse {
        val start = System.currentTimeMillis()
        val body =
            mapOf(
                "model" to endpoint.model,
                "messages" to
                    listOf(
                        mapOf("role" to "system", "content" to system),
                        mapOf("role" to "user", "content" to user),
                    ),
                "temperature" to temperature,
                "max_tokens" to llmConfig.maxTokens,
                "response_format" to mapOf("type" to "json_object"),
            )

        val raw: String =
            webClient
                .post()
                .uri(endpoint.baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer ${endpoint.apiKey}")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(Duration.ofSeconds(llmConfig.timeoutSec))
                .awaitSingle()

        val tree = objectMapper.readTree(raw)
        val content =
            tree
                .path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asString()
        if (content.isBlank()) throw IllegalStateException("LLM returned empty content")

        val tokens = tree.path("usage").path("total_tokens").asInt(0)
        val latency = System.currentTimeMillis() - start

        meterRegistry.counter("llm.tokens.used", Tags.of("agent", agent, "model", endpoint.model)).increment(tokens.toDouble())
        meterRegistry.timer("llm.latency", Tags.of("agent", agent)).record(latency, TimeUnit.MILLISECONDS)
        return LlmResponse(content = content, tokensUsed = tokens, latencyMs = latency, model = endpoint.model)
    }
}
