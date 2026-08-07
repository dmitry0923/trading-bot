package com.trading.bot.infrastructure.tracing

/**
 * Хранилище LLM-трейсов. Реализация: [S3TraceStorage] (MinIO/S3).
 *
 * Помимо записи ([save]) поддерживает чтение — нужно RAG-индексу
 * ([com.trading.bot.service.RagErrorAnalyzer]) для построения корпуса
 * из уже сохранённых трейсов.
 *
 * @see LlmTrace
 */
interface TraceStorage {
    /**
     * Сохраняет трейс LLM-вызова и возвращает ключ объекта (storage_key).
     *
     * @param trace трейс вызова
     * @return ключ объекта в хранилище, либо null если сохранение отключено/не удалось
     *         (хранение best-effort: никогда не ломает основной поток торговли)
     */
    suspend fun save(trace: LlmTrace): String?

    /**
     * Возвращает ключи последних объектов (по времени модификации), не более [limit].
     * Пустой список, если хранилище отключено/недоступно (best-effort).
     */
    suspend fun list(limit: Int): List<String>

    /**
     * Читает и десериализует трейс по ключу объекта.
     *
     * @return трейс, либо null если объект не найден/не может быть прочитан
     */
    suspend fun read(key: String): LlmTrace?
}
