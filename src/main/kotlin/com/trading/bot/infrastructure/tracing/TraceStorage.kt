package com.trading.bot.infrastructure.tracing

/**
 * Хранилище LLM-трейсов. Реализации: [S3TraceStorage] (MinIO/S3) и
 * [AsyncTraceStorage] — асинхронный декоратор поверх [S3TraceStorage],
 * убирающий putObject из hot-path LLM-вызова.
 *
 * Помимо записи ([save]) поддерживает чтение — нужно RAG-индексу
 * ([com.trading.bot.service.RagErrorAnalyzer]) и API расследования
 * ([com.trading.bot.service.TraceQueryService]).
 *
 * @see LlmTrace
 */
interface TraceStorage {
    /**
     * Сохраняет трейс LLM-вызова и возвращает ключ объекта (storage_key).
     *
     * @param trace трейс вызова
     * @param key заранее вычисленный ключ объекта (может выставлять
     *            [AsyncTraceStorage]); null — реализация вычисляет сама
     * @return ключ объекта в хранилище, либо null если сохранение отключено/не удалось
     *         (хранение best-effort: никогда не ломает основной поток торговли)
     */
    suspend fun save(
        trace: LlmTrace,
        key: String? = null,
    ): String?

    /**
     * Возвращает ключи последних объектов (по времени модификации), не более [limit].
     * Пустой список, если хранилище отключено/недоступно (best-effort).
     */
    suspend fun list(limit: Int): List<String>

    /**
     * Возвращает ключи последних объектов конкретного трейса (prefix traceId/),
     * не более [limit]. Пустой список, если хранилище отключено/недоступно.
     */
    suspend fun listByTraceId(
        traceId: String,
        limit: Int,
    ): List<String>

    /**
     * Читает и десериализует трейс по ключу объекта.
     *
     * @return трейс, либо null если объект не найден/не может быть прочитан
     */
    suspend fun read(key: String): LlmTrace?
}
