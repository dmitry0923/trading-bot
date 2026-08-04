package com.trading.bot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.event.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Real-time рассылка дашборда через Server-Sent Events.
 *
 * - Подписчики получают полный снимок [DashboardService.build] по событиям домена
 *   (открытие/закрытие позиций, стратегия, исполнение, тик цены).
 * - Тики цен дебаунсятся интервалом [minIntervalMs] (2 сек) — WS-котировки могут
 *   приходить чаще, чем имеет смысл обновлять панель.
 * - Каждый подписчик получает поток именованных событий `dashboard` в JSON.
 * - Метрики: dashboard.sse.connections, dashboard.sse.broadcasts, dashboard.sse.send_error.
 *
 * @property dashboardService источник данных панели
 * @property objectMapper сериализация событий
 * @property meterRegistry метрики Prometheus
 */
@Service
class DashboardSseService(
    private val dashboardService: DashboardService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry
) {
    private val logger = KotlinLogging.logger {}

    /** Активные подключения. Параллельная коллекция с idempotent remove. */
    private val emitters = ConcurrentHashMap.newKeySet<SseEmitter>()

    /** Время последней рассылки, мс (для троттлинга ценовых тиков). */
    private val lastBroadcastAt = AtomicLong(0L)

    /** Минимальный интервал между рассылками, мс. */
    private val minIntervalMs = 2_000L

    /**
     * Регистрирует нового подписчика и немедленно отправляет текущий снимок.
     *
     * @return [SseEmitter] для эндпоинта `/api/v1/dashboard/stream`
     */
    fun subscribe(): SseEmitter {
        val emitter = SseEmitter(0L)
        emitters.add(emitter)
        emitter.onCompletion { emitters.remove(emitter) }
        emitter.onTimeout { emitters.remove(emitter) }
        emitter.onError { emitters.remove(emitter) }
        meterRegistry.counter("dashboard.sse.connections").increment()
        send(emitter)
        return emitter
    }

    @EventListener
    fun onPositionOpened(@Suppress("UNUSED_PARAMETER") event: PositionOpenedEvent) = throttledBroadcast("POSITION_OPENED")

    @EventListener
    fun onPositionClosed(@Suppress("UNUSED_PARAMETER") event: PositionClosedEvent) = throttledBroadcast("POSITION_CLOSED")

    @EventListener
    fun onStrategyGenerated(@Suppress("UNUSED_PARAMETER") event: StrategyGeneratedEvent) = throttledBroadcast("STRATEGY")

    @EventListener
    fun onExecutionReport(@Suppress("UNUSED_PARAMETER") event: ExecutionReportEvent) = throttledBroadcast("EXECUTION")

    @EventListener
    fun onPriceChanged(@Suppress("UNUSED_PARAMETER") event: PriceChangedEvent) = throttledBroadcast("PRICE")

    /**
     * Рассылка с троттлингом: не чаще одного раза за [minIntervalMs].
     *
     * @param reason источник события (для метрики)
     */
    private fun throttledBroadcast(reason: String) {
        val now = System.currentTimeMillis()
        val last = lastBroadcastAt.get()
        if (now - last < minIntervalMs) return
        if (!lastBroadcastAt.compareAndSet(last, now)) return
        broadcast(reason)
    }

    /**
     * Полная рассылка снимка всем активным подписчикам.
     *
     * @param reason источник события
     */
    private fun broadcast(reason: String) {
        val payload = try {
            objectMapper.writeValueAsString(dashboardService.build())
        } catch (e: Exception) {
            logger.error(e) { "Dashboard payload build failed" }
            return
        }
        meterRegistry.counter("dashboard.sse.broadcasts", Tags.of("reason", reason)).increment()
        emitters.forEach { send(it, payload) }
    }

    /**
     * Отправляет снимок конкретному подписчику. Некорректный подписчик удаляется.
     *
     * @param emitter подписчик
     * @param payload готовый JSON (если null — строится заново)
     */
    private fun send(emitter: SseEmitter, payload: String? = null) {
        val json = payload ?: try {
            objectMapper.writeValueAsString(dashboardService.build())
        } catch (e: Exception) {
            logger.error(e) { "Dashboard payload build failed" }
            return
        }
        try {
            synchronized(emitter) {
                emitter.send(SseEmitter.event().name("dashboard").data(json))
            }
        } catch (e: Exception) {
            logger.debug(e) { "Dashboard SSE send failed, removing subscriber" }
            meterRegistry.counter("dashboard.sse.send_error").increment()
            emitters.remove(emitter)
        }
    }
}
