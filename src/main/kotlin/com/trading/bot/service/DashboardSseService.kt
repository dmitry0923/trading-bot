package com.trading.bot.service

import com.trading.bot.event.ExecutionReportEvent
import com.trading.bot.event.PositionClosedEvent
import com.trading.bot.event.PositionOpenedEvent
import com.trading.bot.event.PriceChangedEvent
import com.trading.bot.event.StrategyGeneratedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.runBlocking
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Real-time рассылка дашборда через Server-Sent Events.
 *
 * - Подписчики получают полный снимок [DashboardService.build] по событиям домена
 *   (открытие/закрытие позиций, стратегия, исполнение, тик цены).
 * - Тики цен дебаунсятся интервалом [minIntervalMs] (2 сек) — WS-котировки могут
 *   приходить чаще, чем имеет смысл обновлять панель.
 * - Каждый подписчик получает поток именованных событий `dashboard` в JSON.
 * - Multi-account (roadmap v2.2): подписчик может указать [accountId] — снимок
 *   фильтруется по аккаунту (см. [DashboardService.build]); null = агрегированный вид.
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
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    /** Подписчик: SseEmitter + фильтр по аккаунту (null = агрегированный вид). */
    private data class Subscriber(
        val emitter: SseEmitter,
        val accountId: Long?,
    )

    /** Активные подключения (CopyOnWrite: список редко меняется, чтения на каждый тик). */
    private val subscribers = CopyOnWriteArrayList<Subscriber>()

    /** Время последней рассылки, мс (для троттлинга ценовых тиков). */
    private val lastBroadcastAt = AtomicLong(0L)

    /** Минимальный интервал между рассылками, мс. */
    private val minIntervalMs = 2_000L

    /**
     * Таймаут SSE-подключения, мс.
     *
     * `SseEmitter(0L)` по докам Spring означает «без таймаута», однако на Tomcat
     * `asyncContext.setTimeout(0)` трактуется как «использовать контейнерный
     * дефолт» (30с): запрос завершается AsyncRequestTimeoutException → HTTP 503,
     * и дашборд теряет live-обновления. Поэтому задаём явный длинный таймаут;
     * «зомби»-подписки (клиент ушёл без закрытия) снимаются при первой же
     * неудачной рассылке [send].
     */
    private val sseTimeoutMs = 3_600_000L

    /**
     * Регистрирует нового подписчика и немедленно отправляет текущий снимок.
     *
     * @param accountId фильтр по аккаунту; null = агрегированный вид
     * @return [SseEmitter] для эндпоинта `/api/v1/dashboard/stream`
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
     * Полная рассылка снимка всем активным подписчикам. Снимок строится один раз
     * на уникальный фильтр (accountId) и рассылается всем подписчикам этого фильтра.
     *
     * @param reason источник события
     */
    private fun broadcast(reason: String) {
        meterRegistry.counter("dashboard.sse.broadcasts", Tags.of("reason", reason)).increment()
        subscribers.groupBy { it.accountId }.forEach { (accountId, group) ->
            val payload = buildPayload(accountId) ?: return@forEach
            group.forEach { send(it.emitter, it.accountId, payload) }
        }
    }

    /**
     * Отправляет снимок конкретному подписчику. Некорректный подписчик удаляется.
     *
     * @param emitter подписчик
     * @param accountId фильтр по аккаунту (null = агрегированный вид)
     * @param payload готовый JSON (если null — строится заново)
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
