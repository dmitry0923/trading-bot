package com.trading.bot.infrastructure.llm

/**
 * Унифицированный ответ LLM-провайдера.
 */
data class LlmResponse(
    val content: String,
    val tokensUsed: Int = 0,
    val latencyMs: Long = 0,
    val model: String = "",
    val isFallback: Boolean = false,
    val fromCache: Boolean = false
) {
    companion object {
        /** Fallback JSON — используется при недоступности LLM. */
        val FALLBACK_CONTENT = """{"conclusion":"NEUTRAL","confidence":0.0,"reasoning":"LLM unavailable"}"""

        fun fallback(reason: String): LlmResponse =
            LlmResponse(
                content = """{"conclusion":"NEUTRAL","confidence":0.0,"reasoning":"LLM unavailable: $reason"}""",
                isFallback = true,
                latencyMs = 0
            )
    }
}
