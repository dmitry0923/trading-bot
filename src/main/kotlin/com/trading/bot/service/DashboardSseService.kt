package com.trading.bot.service

import com.trading.bot.event.ExecutionReportEvent
import com.trading.bot.event.PositionClosedEvent
import com.trading.bot.event.PositionOpenedEvent
import com.trading.bot.event.PriceChangedEvent
import com.trading.bot.event.StrategyGeneratedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.runBlocking
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Real-time дашборд через Server-Sent Events.
 *
 * - Периодически собирает снимок [DashboardService.build] по событиям домена
 *   (открытие/закрытие позиций, стратегии, исполнения, тик цены).
 * - Рассылка по клиентам ограничена [minIntervalMs] (2 сек): поток котировок не должен
 *   ждать, пока соберётся и разошлётся снапшот.
 * - Каждое событие маршрутизируется только подходящим подписчикам (по `accountId`).
 * - Multi-account: подписчик указывает [accountId], снимок фильтруется по счёту.
 *
 * **Почему отдельный поток:** события домена публикуются синхронно из потока котировок
 * (`PriceChangedEvent` в WS flow), а `build()` делает несколько запросов к БД и
 * `analyzeLastNDays` (с записью blind spots). Выполнение этого на потоке котировок
 * останавливало обработку цен: буфер `DROP_OLDEST` терял тики, и SL/TP/trailing не
 * видели цен по открытым позициям (скан-7 P1). Теперь здесь один выделенный поток,
 * а событие только помечает «надо пересобрать».
 *
 * @property dashboardService сервис сборки снимка
 * @property objectMapper сериализатор снимка
 * @property meterRegistry метрики Prometheus
 */
@Service
class DashboardSseService(
    private val dashboardService: DashboardService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    /** Подписчик: SseEmitter + фильтр по счёту (null = агрегированный вид). */
    private data class Subscriber(
        val emitter: SseEmitter,
        val accountId: Long?,
    )

    /** Активные подписчики (CopyOnWrite: список редко меняется, часто читается). */
    private val subscribers = CopyOnWriteArrayList<Subscriber>()

    /** Время последней рассылки, мс (для антидребезга частых тиков). */
    private val lastBroadcastAt = AtomicLong(0L)

    /** Предельное время между рассылками, мс. */
    private val minIntervalMs = 2_000L

    /**
     * Таймаут SSE-подключения, мс.
     *
     * `SseEmitter(0L)` заставляет Spring ждать «бесконечно», поэтому Tomcat делает
     * `asyncContext.setTimeout(0)` и логирует предупреждение; при таймауте — 503.
     */
    private val sseTimeoutMs = 3_600_000L

    /**
     * Отдельный однопоточный исполнитель для сборки и рассылки снапшота: события домена
     * публикуются синхронно из потока котировок, и `build()` не должен его занимать.
     */
    private val broadcastExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dashboard-sse-broadcast").apply { isDaemon = true }
        }

    /** Последнее отложенное событие (для «догоняющего» broadcast). */
    private val pendingReason = AtomicReference<String?>(null)

    /** Признак уже запланированного «догоняющего» broadcast. */
    private val trailingScheduled = AtomicBoolean(false)

    @PreDestroy
    fun shutdown() {
        broadcastExecutor.shutdownNow()
    }

    /**
     * Регистрирует SSE-подписчика.
     *
     * @param accountId фильтр по счёту; null = агрегированный вид
     * @return [SseEmitter] для контроллера `/api/v1/dashboard/stream`
     */
    fun subscribe(accountId: Long? = null): SseEmitter {
        val emitter = SseEmitter(sseTimeoutMs)
        subscribers.add(Subscriber(emitter, accountId))
        emitter.onCompletion { subscribers.removeIf { it.emitter === emitter } }
        emitter.onTimeout { subscribers.removeIf { it.emitter === emitter } }
        emitter.onError { subscribers.removeIf { it.emitter === emitter } }
        meterRegistry.counter("dashboard.sse.connections").increment()
        send(emitter, accountId)
        return emitter
    }

    @EventListener
    @Suppress("UNUSED_PARAMETER")
    fun onPositionOpened(event: PositionOpenedEvent) = throttledBroadcast("POSITION_OPENED")

    @EventListener
    @Suppress("UNUSED_PARAMETER")
    fun onPositionClosed(event: PositionClosedEvent) = throttledBroadcast("POSITION_CLOSED")

    @EventListener
    @Suppress("UNUSED_PARAMETER")
    fun onStrategyGenerated(event: StrategyGeneratedEvent) = throttledBroadcast("STRATEGY")

    @EventListener
    @Suppress("UNUSED_PARAMETER")
    fun onExecutionReport(event: ExecutionReportEvent) = throttledBroadcast("EXECUTION")

    @EventListener
    @Suppress("UNUSED_PARAMETER")
    fun onPriceChanged(event: PriceChangedEvent) = throttledBroadcast("PRICE")

    /**
     * Рассылка с антидребезгом: не чаще [minIntervalMs], но **без потери последнего**
     * события.
     *
     * Раньше «лишние» события просто отбрасывались: если после закрытия трёх позиций в
     * одном окне не приходило новых событий, клиенты видели их открытыми до переподключения
     * (скан-7 P2). Теперь при подавлении планируется один догоняющий broadcast.
     *
     * @param reason причина снимка (для метрики)
     */
    private fun throttledBroadcast(reason: String) {
        val now = System.currentTimeMillis()
        val last = lastBroadcastAt.get()
        val elapsed = now - last
        if (elapsed < minIntervalMs) {
            pendingReason.set(reason)
            scheduleTrailing((minIntervalMs - elapsed).coerceAtLeast(1L))
            return
        }
        if (!lastBroadcastAt.compareAndSet(last, now)) {
            pendingReason.set(reason)
            scheduleTrailing(minIntervalMs)
            return
        }
        enqueueBroadcast(reason)
    }

    /** Планирует ровно один догоняющий broadcast через [delayMs]. */
    private fun scheduleTrailing(delayMs: Long) {
        if (!trailingScheduled.compareAndSet(false, true)) return
        broadcastExecutor.execute {
            try {
                // +50 мс: округление оставшегося интервала до мс не должно «съедать» окно
                Thread.sleep(delayMs + 50)
                val now = System.currentTimeMillis()
                val last = lastBroadcastAt.get()
                if (now - last >= minIntervalMs && lastBroadcastAt.compareAndSet(last, now)) {
                    enqueueBroadcast(pendingReason.getAndSet(null) ?: "TRAILING")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                trailingScheduled.set(false)
            }
        }
    }

    private fun enqueueBroadcast(reason: String) {
        broadcastExecutor.execute {
            try {
                broadcast(reason)
            } catch (e: Exception) {
                logger.warn(e) { "Dashboard SSE broadcast failed (reason=$reason)" }
            }
        }
    }

    /**
     * Рассылает снимок всем подписчикам. Снимок собирается один раз на уникальный
     * фильтр (accountId) и рассылается всем подписчикам этого фильтра.
     *
     * @param reason причина снимка
     */
    private fun broadcast(reason: String) {
        meterRegistry.counter("dashboard.sse.broadcasts", Tags.of("reason", reason)).increment()
        subscribers.groupBy { it.accountId }.forEach { (accountId, group) ->
            val payload = buildPayload(accountId) ?: return@forEach
            group.forEach { send(it.emitter, it.accountId, payload) }
        }
    }

    /**
     * Отправляет снимок конкретному подписчику. Недоступные подписчики удаляются.
     *
     * @param emitter подписчик
     * @param accountId фильтр по счёту (null = агрегированный вид)
     * @param payload готовый JSON (если null — собирается здесь)
     */
    private fun send(
        emitter: SseEmitter,
        accountId: Long?,
        payload: String? = null,
    ) {
        val json = payload ?: buildPayload(accountId) ?: return
        try {
            synchronized(emitter) {
                emitter.send(SseEmitter.event().name("dashboard").data(json))
            }
        } catch (e: Exception) {
            logger.debug(e) { "Dashboard SSE send failed, removing subscriber" }
            meterRegistry.counter("dashboard.sse.send_error").increment()
            subscribers.removeIf { it.emitter === emitter }
        }
    }

    private fun buildPayload(accountId: Long?): String? =
        try {
            objectMapper.writeValueAsString(runBlocking { dashboardService.build(accountId) })
        } catch (e: Exception) {
            logger.error(e) { "Dashboard payload build failed" }
            null
        }
}
