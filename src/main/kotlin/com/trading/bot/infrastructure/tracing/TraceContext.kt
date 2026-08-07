package com.trading.bot.infrastructure.tracing

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC

/**
 * Пропагация trace_id сквозь весь цикл бота (WS-тик -> промпт -> ответ LLM -> ордер).
 *
 * - MDC-ключи: [TRACE_ID], [CYCLE_ID], [TICKER], [AGENT] — попадают в JSON-логи
 *   (см. logback-spring.xml, LogstashEncoder).
 * - [MDCContext] (kotlinx-coroutines-slf4j) — ThreadContextElement: переносит MDC
 *   через переключения потоков/диспетчеров в suspend-точках, поэтому trace_id
 *   не теряется внутри coroutineScope/async.
 *
 * Корневой trace_id = [com.trading.bot.infrastructure.UuidV7.uuidString] (cycleId),
 * создаётся в StrategyService и наследуется всеми дочерними корутинами.
 */
object TraceContext {
    const val TRACE_ID = "trace_id"
    const val CYCLE_ID = "cycle_id"
    const val TICKER = "ticker"
    const val AGENT = "agent"

    fun put(
        key: String,
        value: String?,
    ) {
        if (value.isNullOrBlank()) MDC.remove(key) else MDC.put(key, value)
    }

    fun traceId(): String? = MDC.get(TRACE_ID)

    fun currentMdc(): Map<String, String> = MDC.getCopyOfContextMap()?.toMap() ?: emptyMap()

    /**
     * Coroutine context element с текущим MDC + [extra]. Используется как аргумент
     * `launch(context)` / `async(context)`: дочерняя корутина наследует trace_id.
     */
    fun mdcContext(extra: Map<String, String> = emptyMap()): MDCContext = MDCContext(currentMdc() + extra)

    /**
     * Выполняет [block] в корутине, где MDC = текущий MDC + [extra].
     * После завершения [withContext] восстанавливает предыдущее окружение.
     */
    suspend fun <T> withMdc(
        extra: Map<String, String>,
        block: suspend () -> T,
    ): T = withContext(MDCContext(currentMdc() + extra)) { block() }
}
