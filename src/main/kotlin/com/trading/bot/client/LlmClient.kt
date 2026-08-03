package com.trading.bot.client
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.config.LlmConfig
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

@Component
class LlmClient(private val webClient: WebClient, private val llmConfig: LlmConfig, private val objectMapper: ObjectMapper) {
    private val logger = KotlinLogging.logger {}
    data class LlmResponse(val content: String, val tokensUsed: Int = 0, val latencyMs: Long = 0)

    suspend fun chat(systemPrompt: String, userPrompt: String): LlmResponse {
        val start = System.currentTimeMillis()
        val body = mapOf("model" to llmConfig.model, "messages" to listOf(mapOf("role" to "system", "content" to systemPrompt), mapOf("role" to "user", "content" to userPrompt)), "temperature" to llmConfig.temperature, "max_tokens" to llmConfig.maxTokens)
        return try {
            val r = webClient.post().uri("${llmConfig.baseUrl}/chat/completions").header("Authorization", "Bearer ${llmConfig.apiKey}").header("Content-Type", "application/json").bodyValue(objectMapper.writeValueAsString(body)).retrieve().bodyToMono(String::class.java).timeout(Duration.ofSeconds(llmConfig.timeoutSec.toLong())).awaitSingle()
            val j = objectMapper.readTree(r)
            LlmResponse(j["choices"]?.get(0)?.get("message")?.get("content")?.asText() ?: "", j["usage"]?.get("total_tokens")?.asInt() ?: 0, System.currentTimeMillis() - start)
        } catch (e: Exception) { logger.error(e) { "LLM error" }; LlmResponse("ERROR: ${e.message}", 0, System.currentTimeMillis() - start) }
    }
}
