package com.trading.bot.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.config.LlmConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.util.concurrent.TimeUnit

@Component
class LlmClient(
    private val llmConfig: LlmConfig,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry
) {
    private val logger = KotlinLogging.logger {}
    private val webClient = WebClient.create()

    data class LlmResponse(
        val content: String,
        val tokensUsed: Int = 0,
        val latencyMs: Long = 0,
        val isFallback: Boolean = false
    )

    suspend fun chat(system: String, user: String, temperature: Double = 0.15): LlmResponse {
        if (llmConfig.apiKey.isBlank()) {
            meterRegistry.counter("llm.fallback", Tags.of("reason", "NO_API_KEY")).increment()
            return LlmResponse(content = "{}", isFallback = true)
        }

        val start = System.currentTimeMillis()
        return try {
            val body = mapOf(
                "model" to llmConfig.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to system),
                    mapOf("role" to "user", "content" to user)
                ),
                "temperature" to temperature,
                "max_tokens" to llmConfig.maxTokens
            )

            val raw: String = webClient.post()
                .uri("${llmConfig.baseUrl}/chat/completions")
                .header("Authorization", "Bearer ${llmConfig.apiKey}")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(Duration.ofSeconds(llmConfig.timeoutSec))
                .awaitSingle()

            val tree = objectMapper.readTree(raw)
            val content = tree.path("choices").path(0).path("message").path("content").asText()
            val tokens = tree.path("usage").path("total_tokens").asInt(0)
            val latency = System.currentTimeMillis() - start

            if (content.isBlank()) throw IllegalStateException("LLM returned empty content")

            meterRegistry.counter("llm.tokens", Tags.of("model", llmConfig.model)).increment(tokens.toDouble())
            meterRegistry.timer("llm.latency").record(latency, TimeUnit.MILLISECONDS)
            LlmResponse(content = content, tokensUsed = tokens, latencyMs = latency)
        } catch (e: Exception) {
            logger.warn(e) { "LLM call failed, using fallback" }
            meterRegistry.counter("llm.fallback", Tags.of("reason", "CALL_ERROR")).increment()
            LlmResponse(content = "{}", isFallback = true)
        }
    }
}
