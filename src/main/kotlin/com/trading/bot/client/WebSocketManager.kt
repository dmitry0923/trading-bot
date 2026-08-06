package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Тип WebSocket-потока Alor.
 */
enum class WsStream {
    ORDERS,
    QUOTES,
}

/**
 * Состояние соединения WebSocket-потока.
 */
enum class WsConnectionStatus {
    CONNECTED,
    DISCONNECTED,
}

/**
 * Событие изменения состояния WebSocket-соединения.
 *
 * Публикуется [WebSocketManager] и потребляется:
 * - [com.trading.bot.service.StateReconciliationService] — полная сверка
 *   локального стейта с биржей при каждом CONNECTED (в т.ч. после реконнекта)
 *   и при каждом реальном DISCONNECTED (чтобы закрыть окно пропуска данных);
 * - [com.trading.bot.service.TradingBotService] — сброс кэша последних WS-тиков
 *   для мгновенного возобновления fallback-поллинга после реконнекта.
 *
 * @param stream тип потока (ORDERS | QUOTES)
 * @param status CONNECTED | DISCONNECTED
 * @param reconnectAttempt номер попытки подключения (0 — первичное подключение)
 * @param reason причина (SUBSCRIBED / STREAM_ERROR / STREAM_CLOSED / CONNECT_FAILED / HEARTBEAT_TIMEOUT / MAX_ATTEMPTS)
 */
data class WsConnectionEvent(
    val stream: WsStream,
    val status: WsConnectionStatus,
    val reconnectAttempt: Int = 0,
    val reason: String = "UNKNOWN",
    val timestamp: Instant = Instant.now(),
)

/**
 * Менеджер WebSocket-соединений Alor с Heartbeat-мониторингом.
 *
 * Отвечает за:
 * - Централизованный трекинг состояния каждого потока (ORDERS / QUOTES)
 *   и публикацию [WsConnectionEvent] — на них завязана процедура полной
 *   State Reconciliation при реконнектах.
 * - Liveness-мониторинг: [lastActivity] обновляется на КАЖДОЕ входящее сообщение
 *   и на каждый успешный heartbeat-ping. Watchdog ([watchdog]) периодически
 *   проверяет простой соединения: если данные/пинги не проходят дольше
 *   [AlorConfig.wsHeartbeatTimeoutMs] — поток помечается DISCONNECTED
 *   (триггерит сверку до того, как транспорт обнаружит обрыв).
 * - Глобальную монотонную последовательность сообщений [nextSequence] — для
 *   отбрасывания устаревших сообщений из очереди (stale data discard).
 * - Stale-guard котировок [isQuoteStale]: сообщения, задержанные в очереди
 *   дольше [AlorConfig.wsStaleMessageAgeMs], и сообщения, пришедшие в
 *   неправильном порядке (старее последнего принятого по тикеру), отбрасываются.
 *   Водяные знаки порядка сбрасываются при каждом переподключении — свежий
 *   снапшот нового соединения всегда принимается.
 *
 * Метрики:
 * - alor.ws.connected / alor.ws.disconnected (Counters, tag stream)
 * - alor.ws.status (Gauge: 1 = CONNECTED, 0 = DISCONNECTED)
 * - alor.ws.stale_connection — watchdog зафиксировал «тихий» обрыв
 * - alor.ws.quotes.stale_discarded / alor.ws.quotes.out_of_order — отброшенные котировки
 */
@Component
class WebSocketManager(
    private val alorConfig: AlorConfig,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    private val _events = MutableSharedFlow<WsConnectionEvent>(extraBufferCapacity = 64)
    val events: Flow<WsConnectionEvent> = _events.asSharedFlow()

    private val statuses = ConcurrentHashMap<WsStream, AtomicReference<WsConnectionStatus>>()
    private val lastActivityAt = ConcurrentHashMap<WsStream, AtomicLong>()
    private val sequences = ConcurrentHashMap<WsStream, AtomicLong>()
    private val lastQuoteTimeByTicker = ConcurrentHashMap<String, AtomicLong>()
    private val lastQuoteSeqByTicker = ConcurrentHashMap<String, AtomicLong>()

    init {
        for (stream in WsStream.entries) {
            statuses[stream] = AtomicReference(WsConnectionStatus.DISCONNECTED)
            lastActivityAt[stream] = AtomicLong(0)
            sequences[stream] = AtomicLong(0)
        }
        for (stream in WsStream.entries) {
            meterRegistry.gauge("alor.ws.status", Tags.of("stream", stream.name), statuses[stream]!!) { ref ->
                if (ref.get() == WsConnectionStatus.CONNECTED) 1.0 else 0.0
            }
        }
    }

    /**
     * Сообщает менеджеру об установленном соединении (после успешной подписки).
     * Сбрасывает водяные знаки порядка — снапшот нового соединения не
     * отбрасывается как «устаревший».
     */
    fun onConnected(
        stream: WsStream,
        reconnectAttempt: Int,
        reason: String = "SUBSCRIBED",
    ) {
        val was = statuses[stream]!!.getAndSet(WsConnectionStatus.CONNECTED)
        lastActivityAt[stream]!!.set(Instant.now().toEpochMilli())
        lastQuoteTimeByTicker.clear()
        lastQuoteSeqByTicker.clear()
        meterRegistry.counter("alor.ws.connected", Tags.of("stream", stream.name)).increment()
        if (was != WsConnectionStatus.CONNECTED) {
            logger.info { "WS ${stream.name}: connected (attempt=$reconnectAttempt, reason=$reason)" }
            _events.tryEmit(WsConnectionEvent(stream, WsConnectionStatus.CONNECTED, reconnectAttempt, reason))
        }
    }

    /**
     * Сообщает менеджеру об обрыве соединения. Эмитит событие только при
     * реальном переходе CONNECTED → DISCONNECTED (без дубликатов).
     */
    fun onDisconnected(
        stream: WsStream,
        reconnectAttempt: Int,
        reason: String,
    ) {
        val was = statuses[stream]!!.getAndSet(WsConnectionStatus.DISCONNECTED)
        meterRegistry.counter("alor.ws.disconnected", Tags.of("stream", stream.name, "reason", reason)).increment()
        if (was != WsConnectionStatus.DISCONNECTED) {
            logger.warn { "WS ${stream.name}: disconnected (attempt=$reconnectAttempt, reason=$reason)" }
            _events.tryEmit(WsConnectionEvent(stream, WsConnectionStatus.DISCONNECTED, reconnectAttempt, reason))
        }
    }

    /**
     * Фиксирует активность потока: любое входящее сообщение или успешный
     * heartbeat-ping. Используется watchdog'ом для определения «тихого» обрыва.
     */
    fun onActivity(stream: WsStream) {
        lastActivityAt[stream]!!.set(System.currentTimeMillis())
    }

    fun isConnected(stream: WsStream): Boolean = statuses[stream]!!.get() == WsConnectionStatus.CONNECTED

    /**
     * Глобальный монотонный номер сообщения для потока. Инкрементируется на
     * каждом принятом сообщении (в т.ч. через реконнекты).
     */
    fun nextSequence(stream: WsStream): Long = sequences[stream]!!.incrementAndGet()

    /**
     * Проверка «устарелости» котировки:
     * 1. Backlog: сообщение получено более [AlorConfig.wsStaleMessageAgeMs] назад
     *    (задержка в очереди на обработку) — отбрасывается.
     * 2. Out-of-order: если доступно биржевое время [exchangeTime] и оно НЕ
     *    новее последнего принятого для этого тикера — отбрасывается.
     *    Если биржевого времени нет — сравниваются локальные sequence номера.
     *
     * Принятые сообщения обновляют водяной знак; отброшенные — нет.
     */
    fun isQuoteStale(
        ticker: String,
        receivedAt: Instant,
        sequence: Long,
        exchangeTime: Instant? = null,
    ): Boolean {
        val key = ticker.uppercase()
        if (Duration.between(receivedAt, Instant.now()).toMillis() > alorConfig.wsStaleMessageAgeMs) {
            meterRegistry.counter("alor.ws.quotes.stale_discarded", Tags.of("ticker", key)).increment()
            logger.warn {
                "WS quotes: DISCARDED stale quote $key (age=${Duration.between(
                    receivedAt,
                    Instant.now(),
                ).toMillis()}ms > ${alorConfig.wsStaleMessageAgeMs}ms)"
            }
            return true
        }

        if (exchangeTime != null) {
            val last = lastQuoteTimeByTicker[key]?.get()
            if (last != null && exchangeTime.toEpochMilli() <= last) {
                meterRegistry.counter("alor.ws.quotes.out_of_order", Tags.of("ticker", key)).increment()
                logger.debug { "WS quotes: DISCARDED out-of-order quote $key (exchangeTime=$exchangeTime <= last=$last)" }
                return true
            }
            lastQuoteTimeByTicker.computeIfAbsent(key) { AtomicLong(exchangeTime.toEpochMilli()) }.set(exchangeTime.toEpochMilli())
            return false
        }

        val lastSeq = lastQuoteSeqByTicker[key]?.get()
        if (lastSeq != null && sequence <= lastSeq) {
            meterRegistry.counter("alor.ws.quotes.out_of_order", Tags.of("ticker", key)).increment()
            logger.debug { "WS quotes: DISCARDED out-of-order quote $key (sequence=$sequence <= last=$lastSeq)" }
            return true
        }
        lastQuoteSeqByTicker.computeIfAbsent(key) { AtomicLong(sequence) }.set(sequence)
        return false
    }

    /**
     * Heartbeat-watchdog: раз в 10с проверяет «живость» подключённых потоков.
     * Если с последней активности (данные или успешный ping) прошло дольше
     * [AlorConfig.wsHeartbeatTimeoutMs] — поток помечается DISCONNECTED, что
     * триггерит State Reconciliation до того, как транспорт обнаружит обрыв.
     */
    @Scheduled(fixedDelay = 10_000)
    fun watchdog() {
        for (stream in WsStream.entries) {
            if (statuses[stream]!!.get() != WsConnectionStatus.CONNECTED) continue
            val idleMs = System.currentTimeMillis() - lastActivityAt[stream]!!.get()
            if (idleMs <= alorConfig.wsHeartbeatTimeoutMs) continue
            logger.error {
                "WS ${stream.name}: no inbound data or heartbeat for ${idleMs}ms " +
                    "> timeout ${alorConfig.wsHeartbeatTimeoutMs}ms — marking DISCONNECTED (state reconciliation)"
            }
            meterRegistry.counter("alor.ws.stale_connection", Tags.of("stream", stream.name)).increment()
            onDisconnected(stream, 0, "HEARTBEAT_TIMEOUT")
        }
    }
}
