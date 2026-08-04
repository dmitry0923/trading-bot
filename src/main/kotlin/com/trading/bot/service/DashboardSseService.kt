package com.trading.bot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.event.ExecutionReportEvent
import com.trading.bot.event.PositionClosedEvent
import com.trading.bot.event.PositionOpenedEvent
import com.trading.bot.event.PriceChangedEvent
import com.trading.bot.event.StrategyGeneratedEvent
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Real-time рассылка дашборда через Server-Sent Events.
 *
 * Снимок строится в корутине, поэтому обработчик Spring-события не блокируется
 * на R2DBC-запросах. Частые ценовые тики объединяются в одну trailing-рассылку:
 * последнее обновление не теряется даже если пришло внутри throttle-окна.
 */
@Service
class DashboardSseService(
    private val dashboardService: DashboardService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val emitters = ConcurrentHashMap.newKeySet<SseEmitter>()
    private val lastBroadcastAt = AtomicLong(0L)
    private val broadcastScheduled = AtomicBoolean(false)
    private val latestReason = AtomicReference("UNKNOWN")
    private val minIntervalMs = 2_000L

    fun subscribe(): SseEmitter {
        val emitter = SseEmitter(0L)
        emitters.add(emitter)
        emitter.onCompletion { emitters.remove(emitter) }
        emitter.onTimeout { emitters.remove(emitter) }
        emitter.onError { emitters.remove(emitter) }
        meterRegistry.counter("dashboard.sse.connections").increment()
        scope.launch { sendSnapshot(emitter) }
        return emitter
    }

    @EventListener
    fun onPositionOpened(event: PositionOpenedEvent) = scheduleBroadcast("POSITION_OPENED:${event.ticker}")

    @EventListener
    fun onPositionClosed(event: PositionClosedEvent) = scheduleBroadcast("POSITION_CLOSED:${event.ticker}")

    @EventListener
    fun onStrategyGenerated(event: StrategyGeneratedEvent) = scheduleBroadcast("STRATEGY:${event.strategy.ticker}")

    @EventListener
    fun onExecutionReport(event: ExecutionReportEvent) = scheduleBroadcast("EXECUTION:${event.report.orderId}")

    @EventListener
    fun onPriceChanged(event: PriceChangedEvent) = scheduleBroadcast("PRICE:${event.ticker}")

    private fun scheduleBroadcast(reason: String) {
        if (emitters.isEmpty()) return
        latestReason.set(reason)
        if (!broadcastScheduled.compareAndSet(false, true)) return

        scope.launch {
            try {
                val waitMs = (minIntervalMs - (System.currentTimeMillis() - lastBroadcastAt.get())).coerceAtLeast(0L)
                if (waitMs > 0) delay(waitMs)
                broadcast(latestReason.get())
                lastBroadcastAt.set(System.currentTimeMillis())
            } finally {
                broadcastScheduled.set(false)
            }
        }
    }

    private suspend fun broadcast(reason: String) {
        val payload = buildPayload() ?: return
        meterRegistry.counter("dashboard.sse.broadcasts", Tags.of("reason", reason.substringBefore(':'))).increment()
        emitters.forEach { send(it, payload) }
    }

    private suspend fun sendSnapshot(emitter: SseEmitter) {
        buildPayload()?.let { send(emitter, it) }
    }

    private suspend fun buildPayload(): String? =
        try {
            objectMapper.writeValueAsString(dashboardService.build())
        } catch (e: Exception) {
            logger.error(e) { "Dashboard payload build failed" }
            null
        }

    private fun send(
        emitter: SseEmitter,
        payload: String,
    ) {
        try {
            synchronized(emitter) {
                emitter.send(SseEmitter.event().name("dashboard").data(payload))
            }
        } catch (e: Exception) {
            logger.debug(e) { "Dashboard SSE send failed, removing subscriber" }
            meterRegistry.counter("dashboard.sse.send_error").increment()
            emitters.remove(emitter)
            emitter.complete()
        }
    }

    @PreDestroy
    fun shutdown() {
        emitters.forEach(SseEmitter::complete)
        emitters.clear()
        scope.cancel("DashboardSseService is shutting down")
    }
}
