package com.trading.bot.infrastructure.llm

/**
 * Унифицированный ответ LLM-провайдера.
 *
 * @param storageKey ключ объекта в S3/MinIO с полным трейсом вызова
 *                   (см. [com.trading.bot.infrastructure.tracing.TraceStorage]);
 *                   null — трейс не сохранён (хранение отключено или вызов из кэша
 *                   с пустым ключом). Записывается в agent_logs.storage_key.
 */
data class LlmResponse(
    val content: String,
    val tokensUsed: Int = 0,
    val latencyMs: Long = 0,
    val model: String = "",
    val isFallback: Boolean = false,
    val fromCache: Boolean = false,
    val storageKey: String? = null,
    val errorMessage: String? = null,
) {
    companion object {
        fun fallback(
            reason: String,
            message: String? = null,
        ): LlmResponse =
            LlmResponse(
                content = """{"conclusion":"NEUTRAL","confidence":0.0,"reasoning":"LLM unavailable: $reason"}""",
                isFallback = true,
                errorMessage = message ?: reason,
                latencyMs = 0,
            )
    }
}
