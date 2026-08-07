package com.trading.bot.model

import java.time.Instant

/**
 * Результат RAG-анализа ошибки LLM-агента.
 *
 * @param query текст запроса (ошибка/симптом)
 * @param mode способ построения отчёта: LLM (RAG через модель) |
 *              RULE_BASED (локальная сводка) | DISABLED
 * @param report текст разбора первопричины
 * @param retrievedTraces релевантные трейсы из корпуса
 * @param createdAt время анализа
 */
data class RagAnalysis(
    val query: String,
    val mode: String,
    val report: String,
    val retrievedTraces: List<RagRetrievedTrace>,
    val createdAt: Instant = Instant.now(),
)

/**
 * Один релевантный LLM-трейс из корпуса (без полных промптов — только метаданные
 * и обрезанный ответ для читаемости API/UI).
 */
data class RagRetrievedTrace(
    val key: String,
    val agent: String,
    val ticker: String?,
    val provider: String,
    val model: String,
    val isFallback: Boolean,
    val fromCache: Boolean,
    val errorMessage: String?,
    val score: Double,
    val responsePreview: String,
)

data class RagAnalyzeRequest(
    val query: String,
    val ticker: String? = null,
    val k: Int? = null,
)

data class RagTraceRequest(
    val storageKey: String,
    val k: Int? = null,
)
