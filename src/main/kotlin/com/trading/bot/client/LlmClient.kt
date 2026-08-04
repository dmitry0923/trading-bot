package com.trading.bot.client

import com.trading.bot.infrastructure.llm.PromptTemplate
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import org.springframework.stereotype.Component

/**
 * Обратно-совместимая обёртка над [ResilientLlmClient].
 *
 * Сохраняет прежний публичный API (chat(system, user, temperature)),
 * но всё фактическое выполнение идёт через отказоустойчивый клиент
 * с Circuit Breaker / Retry / Semantic Cache.
 */
@Component
class LlmClient(
    private val resilientLlmClient: ResilientLlmClient
) {
    data class LlmResponse(
        val content: String,
        val tokensUsed: Int = 0,
        val latencyMs: Long = 0,
        val isFallback: Boolean = false
    )

    suspend fun chat(system: String, user: String, temperature: Double = 0.15): LlmResponse {
        val prompt = PromptTemplate(name = "legacy", version = "default", system = system, userTemplate = user)
        val resp = resilientLlmClient.complete(
            agent = "legacy",
            ticker = "",
            prompt = prompt,
            variables = emptyMap(),
            temperature = temperature
        )
        return LlmResponse(
            content = resp.content,
            tokensUsed = resp.tokensUsed,
            latencyMs = resp.latencyMs,
            isFallback = resp.isFallback
        )
    }
}
