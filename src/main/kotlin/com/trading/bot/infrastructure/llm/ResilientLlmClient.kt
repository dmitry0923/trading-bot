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
import kotlinx.coroutines.delay
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
 * - Бюджет LLM (резервирование токенов/стоимости, fail-closed) — [LlmBudgetService]
 * - Single-flight: при одинаковом семантическом ключе только один поток ходит к LLM —
 *   остальные ожидают запись владельца ([LlmSingleFlight])
 * - Fallback: JSON с conclusion=NEUTRAL, signalStrength=0.0 при недоступности LLM
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
    private val llmBudgetService: LlmBudgetService? = null,
    private val llmSingleFlight: LlmSingleFlight? = null,
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

    private companion object {
        const val SINGLE_FLIGHT_POLL_MS = 100L
    }

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
    open suspend fun complete(
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

        // Версия семантики кэша: смена промпта/модели/логики агента автоматически
        // инвалидирует старые записи (защита от устаревших LLM-ответов).
        val seed =
            if (fingerprint != null) {
                versionSeed(prompt.version, endpoint)
            } else {
                null
            }

        // Весь вызов выполняется в MDC-контексте с agent: trace_id наследуется
        // от родительской корутины цикла (см. TraceContext / StrategyService),
        // поэтому каждый JSON-лог и трейс привязаны к конкретному агенту и циклу.
        return TraceContext.withMdc(mapOf(TraceContext.AGENT to agent)) {
            if (fingerprint != null) {
                semanticCache.get(agent, ticker, fingerprint, cacheNamespace, seed)?.let { return@withMdc it }
            }

            val system = prompt.renderSystem(variables)
            val user = prompt.renderUser(variables)

            // ---- Бюджет: резервируем расход ДО вызова (fail-closed по лимитам) ----
            // Оценка = длина промпта + максимальный размер ответа (maxTokens, review/P1):
            // одного промпта мало — бюджет должен покрывать и выход модели.
            val budget =
                llmBudgetService?.let { budget ->
                    val cacheKey = cacheKeyOf(agent, ticker, fingerprint, cacheNamespace, seed)
                    budget.reserve(agent, cacheKey, budget.estimateTokens(system, user) + llmConfig.maxTokens)
                }
            if (budget != null && !budget.allowed) {
                val reason = budget.reason ?: "TOKEN_BUDGET_EXCEEDED"
                logger.warn { "LLM budget blocked agent=$agent ticker=$ticker: $reason" }
                meterRegistry.counter("llm.fallback.activated", Tags.of("agent", agent, "reason", reason)).increment()
                return@withMdc LlmResponse.fallback(reason)
            }

            // ---- Single-flight: при одинаковом семантическом ключе к LLM ходит один поток ----
            val cacheKey = cacheKeyOf(agent, ticker, fingerprint, cacheNamespace, seed)
            val sf =
                if (fingerprint != null) {
                    llmSingleFlight
                } else {
                    null
                }

            // Single-flight выключен или у вызова нет fingerprint — прямой вызов владельцем.
            if (sf == null) {
                return@withMdc executeLlmCall(agent, ticker, fingerprint, system, user, temperature, endpoint, cacheNamespace, seed)
            }

            when (val acquired = sf.acquire(cacheKey, seed)) {
                is LlmSingleFlight.AcquireResult.Owner -> {
                    try {
                        executeLlmCall(agent, ticker, fingerprint, system, user, temperature, endpoint, cacheNamespace, seed)
                    } finally {
                        sf.release(cacheKey, seed, acquired.token)
                    }
                }

                is LlmSingleFlight.AcquireResult.Busy -> {
                    // Не владелец: параллельный цикл уже выполняет этот вызов — ждём
                    // запись в кэш от владельца и только после таймаута даём fallback.
                    waitForOwnerCache(agent, ticker, fingerprint!!, cacheNamespace, seed)
                }

                is LlmSingleFlight.AcquireResult.Unavailable -> {
                    // Redis недоступен (review/P1): дедупликация не гарантирована — fail-closed.
                    logger.warn { "Single-flight unavailable (Redis) for agent=$agent ticker=$ticker -> fail-closed" }
                    meterRegistry
                        .counter(
                            "llm.fallback.activated",
                            Tags.of("agent", agent, "reason", "SINGLE_FLIGHT_UNAVAILABLE"),
                        ).increment()
                    LlmResponse.fallback("SINGLE_FLIGHT_UNAVAILABLE")
                }

                null -> {
                    executeLlmCall(agent, ticker, fingerprint, system, user, temperature, endpoint, cacheNamespace, seed)
                }
            }
        }
    }

    /** Выполняет LLM-вызов (очередь + resilience), трассирует и пишет semantic cache. */
    private suspend fun executeLlmCall(
        agent: String,
        ticker: String,
        fingerprint: String?,
        system: String,
        user: String,
        temperature: Double,
        endpoint: ResolvedEndpoint,
        cacheNamespace: String?,
        seed: String?,
    ): LlmResponse {
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
            semanticCache.put(agent, ticker, fingerprint, finalResponse, cacheNamespace, seed)
        }
        return finalResponse
    }

    private suspend fun waitForOwnerCache(
        agent: String,
        ticker: String,
        fingerprint: String,
        cacheNamespace: String?,
        seed: String?,
    ): LlmResponse {
        val deadline = System.currentTimeMillis() + llmConfig.singleFlightWaitMs
        while (System.currentTimeMillis() < deadline) {
            delay(SINGLE_FLIGHT_POLL_MS)
            semanticCache.get(agent, ticker, fingerprint, cacheNamespace, seed)?.let { return it }
        }
        meterRegistry.counter("llm.fallback.activated", Tags.of("agent", agent, "reason", "SINGLE_FLIGHT_BUSY")).increment()
        logger.warn { "Single-flight wait timeout agent=$agent ticker=$ticker" }
        return LlmResponse.fallback("SINGLE_FLIGHT_BUSY")
    }

    /** Детерминированный семантический ключ (тот же, что у participant, ожидающих кэш). */
    private fun cacheKeyOf(
        agent: String,
        ticker: String,
        fingerprint: String?,
        cacheNamespace: String?,
        seed: String?,
    ): String =
        if (fingerprint != null) {
            semanticCache.key(agent, ticker, fingerprint, cacheNamespace, seed)
        } else {
            "$agent:$ticker:no-fingerprint"
        }

    /**
     * Версия семантики кэша: имя промпта + модель + [LlmConfig.cacheDataVersion] +
     * провайдер/baseUrl. Смена любого из них (включая переключение между провайдерами
     * с одинаковой моделью) инвалидирует старые записи — защита от устаревших ответов.
     */
    private fun versionSeed(
        promptVersion: String,
        endpoint: ResolvedEndpoint,
    ): String = "$promptVersion:${endpoint.model}:${llmConfig.cacheDataVersion}:${endpoint.provider.name}:${endpoint.baseUrl}"

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
     *
     * Неизменяемая цепочка через [val]-переменные: каждая обёртка замыкается на
     * ПРЕДЫДУЩИЙ [val], а не на живую `var`.
     *
     * Ретгресс (этап 3, risk / R3): раньше использовалась `var call` + замыкание
     * `{ call() }`. Котлин-лямбда читает переменную в МОМЕНТ ВЫЗОВА, поэтому после
     * `call = retry.decorateSuspendFunction { call() }` повторный вызов полученной
     * обёртки замыкается на САМУ `call` (уже новую обёртку) → бесконечная рекурсия
     * `decoratedCall -> itself -> bytecode limit -> StackOverflowError`. Теперь
     * цепочка собрана без live-переменной: `breaker(rateLimiter(retry(block)))`.
     */
    private suspend fun decoratedCall(block: suspend () -> LlmResponse): LlmResponse {
        val withRetry =
            if (llmConfig.retryEnabled) {
                retryRegistry.retry("llm").decorateSuspendFunction { block() }
            } else {
                block
            }
        val withRateLimit =
            if (llmConfig.rateLimiterEnabled) {
                rateLimiterRegistry.rateLimiter("llm").decorateSuspendFunction { withRetry() }
            } else {
                withRetry
            }
        return if (llmConfig.circuitBreakerEnabled) {
            circuitBreakerRegistry.circuitBreaker("llm").decorateSuspendFunction { withRateLimit() }
        } else {
            withRateLimit
        }()
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
