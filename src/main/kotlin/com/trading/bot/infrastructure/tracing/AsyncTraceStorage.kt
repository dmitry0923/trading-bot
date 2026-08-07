package com.trading.bot.infrastructure.tracing

import com.trading.bot.config.TraceStorageConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.launch
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * Асинхронный декоратор над [TraceStorage]: убирает putObject из hot-path
 * LLM-вызова (`ResilientLlmClient.complete`).
 *
 * Семантика:
 * - [save] вычисляет детерминированный ключ объекта ([traceObjectKey]) и пытается
 *   поставить запись в ограниченный FIFO-буфер ([TraceStorageConfig.asyncBufferSize]) —
 *   ключ возвращается сразу, фактическая запись выполняется фоновым консюмером.
 * - При переполнении буфера — синхронный fallback на [delegate] (трейс не теряется).
 * - Ошибки фоновой записи логируются и считаются в метрике `trace.write.async`
 *   — best-effort, как и синхронная запись.
 * - [list] / [listByTraceId] / [read] делегируются напрямую.
 *
 * Порядок записей сохраняется (один консюмер, FIFO).
 */
@Component
@Primary
class AsyncTraceStorage(
    private val config: TraceStorageConfig,
    private val delegate: TraceStorage,
    private val meterRegistry: MeterRegistry,
) : TraceStorage {
    private val logger = KotlinLogging.logger {}

    private data class PendingWrite(
        val trace: LlmTrace,
        val key: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buffer: Channel<PendingWrite> = Channel(capacity = config.asyncBufferSize.coerceAtLeast(1))
    private val queued =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    init {
        meterRegistry.gauge("trace.buffer.size", this) { it.queued.get().toDouble() }
        scope.launch {
            for (pending in buffer) {
                queued.decrementAndGet()
                try {
                    delegate.save(pending.trace, pending.key)
                    meterRegistry.counter("trace.write.async", Tags.of("result", "written")).increment()
                } catch (e: Exception) {
                    logger.warn(e) { "Async trace write failed for key=${pending.key}" }
                    meterRegistry.counter("trace.write.async", Tags.of("result", "failed")).increment()
                }
            }
        }
    }

    override suspend fun save(
        trace: LlmTrace,
        key: String?,
    ): String? {
        if (!config.enabled) return null
        val objectKey = key ?: traceObjectKey(trace)
        val result: ChannelResult<Unit> = buffer.trySend(PendingWrite(trace, objectKey))
        return when {
            result.isSuccess -> {
                queued.incrementAndGet()
                meterRegistry.counter("trace.write.async", Tags.of("result", "queued")).increment()
                objectKey
            }

            else -> {
                logger.warn { "Async trace buffer full; writing synchronously" }
                meterRegistry.counter("trace.write.async", Tags.of("result", "sync_fallback")).increment()
                delegate.save(trace, objectKey)
            }
        }
    }

    override suspend fun list(limit: Int): List<String> = delegate.list(limit)

    override suspend fun listByTraceId(
        traceId: String,
        limit: Int,
    ): List<String> = delegate.listByTraceId(traceId, limit)

    override suspend fun read(key: String): LlmTrace? = delegate.read(key)

    @PreDestroy
    fun close() {
        scope.cancel()
    }
}
