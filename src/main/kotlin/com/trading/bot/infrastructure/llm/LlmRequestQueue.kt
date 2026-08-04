package com.trading.bot.infrastructure.llm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Очередь запросов к LLM на Kotlin [Channel].
 *
 * Ограничивает число одновременных LLM-вызовов ([concurrency]): запросы сверх
 * лимита встают в FIFO-очередь ёмкостью [capacity] и выполняются воркерами
 * по мере освобождения слота. [submit] — suspend, поэтому ожидание слота
 * не блокирует поток (важно при Virtual Threads / корутинах).
 *
 * Использование: обернуть реальный HTTP-вызов в [submit]; semantic cache
 * и локальные fallback остаются вне очереди.
 */
class LlmRequestQueue(
    private val capacity: Int = 64,
    private val concurrency: Int = 2,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val queue = Channel<suspend () -> Unit>(capacity)

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(concurrency > 0) { "concurrency must be positive" }
        repeat(concurrency) { scope.launch { worker() } }
    }

    /**
     * Отправляет [block] в очередь и ожидает результат.
     * Исключения из [block] пробрасываются вызывающему коду.
     */
    suspend fun <T> submit(block: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        queue.send {
            try {
                result.complete(block())
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        return result.await()
    }

    private suspend fun worker() {
        for (task in queue) {
            task()
        }
    }

    /** Закрывает очередь и завершает воркеры после обработки оставшихся задач. */
    fun close() {
        queue.close()
    }
}
