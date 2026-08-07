package com.trading.bot.service

import com.trading.bot.infrastructure.tracing.LlmTrace
import com.trading.bot.infrastructure.tracing.TraceStorage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Чтение LLM-трейсов из объектного хранилища для расследования инцидентов
 * и аудита решений агентов (без RAG/LLM-разбора, дешёвый путь).
 *
 * - [getByStorageKey] — один трейс по ключу из agent_logs.storage_key;
 * - [listByCycleId] — все вызовы агентов конкретного цикла (cycleId = trace_id);
 * - [listRecent] — последние трейсы по всему бакету.
 */
@Service
class TraceQueryService(
    private val storage: TraceStorage,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun getByStorageKey(key: String): LlmTrace? {
        require(key.isNotBlank()) { "storageKey must not be blank" }
        return try {
            storage.read(key)
        } catch (e: Exception) {
            logger.warn(e) { "Trace query failed for key=$key" }
            null
        }
    }

    suspend fun listByCycleId(
        cycleId: String,
        limit: Int,
    ): List<LlmTrace> {
        require(cycleId.isNotBlank()) { "cycleId must not be blank" }
        val safeLimit = limit.coerceIn(1, 100)
        return try {
            storage
                .listByTraceId(cycleId, safeLimit)
                .mapNotNull { key -> storage.read(key) }
        } catch (e: Exception) {
            logger.warn(e) { "Trace query failed for cycleId=$cycleId" }
            emptyList()
        }
    }

    suspend fun listRecent(limit: Int): List<LlmTrace> {
        val safeLimit = limit.coerceIn(1, 100)
        return try {
            storage
                .list(safeLimit)
                .mapNotNull { key -> storage.read(key) }
        } catch (e: Exception) {
            logger.warn(e) { "Recent trace query failed" }
            emptyList()
        }
    }
}
