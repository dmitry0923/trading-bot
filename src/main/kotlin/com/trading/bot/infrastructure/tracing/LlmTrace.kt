package com.trading.bot.infrastructure.tracing

import java.time.Instant

/**
 * Полный трейс одного LLM-вызова — сериализуется в JSON и сохраняется
 * в объектное хранилище (S3/MinIO) через [TraceStorage].
 *
 * Позволяет воспроизвести и проаудировать любой агентский вызов: какие
 * промпты были отправлены, что вернула модель, сколько это стоило, и как
 * решение связано с циклом (traceId = cycleId) и тикером.
 *
 * @param traceId идентификатор цикла (trace_id из TraceContext), может быть null
 * @param ticker тикер инструмента
 * @param agent имя агента (technical, strategy, arbitrator, ...)
 * @param provider провайдер LLM (ROUTER_AI, KIMI, DEEPSEEK, QWEN)
 * @param model модель провайдера
 * @param fingerprint семантический отпечаток рынка (ключ semantic cache)
 * @param systemPrompt отрендеренный system-промпт
 * @param userPrompt отрендеренный user-промпт
 * @param responseContent сырой ответ LLM (или fallback-JSON)
 * @param tokensUsed потреблённые токены
 * @param latencyMs задержка вызова, мс
 * @param isFallback ответ был получен через fallback (LLM недоступен)
 * @param fromCache ответ получен из semantic cache
 * @param errorMessage текст ошибки (для fallback)
 * @param createdAt время вызова
 */
data class LlmTrace(
    val traceId: String?,
    val ticker: String?,
    val agent: String,
    val provider: String,
    val model: String,
    val fingerprint: String?,
    val systemPrompt: String,
    val userPrompt: String,
    val responseContent: String,
    val tokensUsed: Int,
    val latencyMs: Long,
    val isFallback: Boolean,
    val fromCache: Boolean,
    val errorMessage: String? = null,
    val createdAt: Instant = Instant.now(),
)
