package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import com.trading.bot.infrastructure.UuidV7
import com.trading.bot.model.dto.ExecutionReport
import com.trading.bot.model.dto.OrderStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.BufferOverflowStrategy
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * Тик котировки из WebSocket Alor.
 *
 * @param ticker тикер инструмента
 * @param price последняя цена сделки (Last), либо mid между лучшим Bid/Offer
 * @param receivedAt момент приёма сообщения из WS (до буферизации) — используется
 *   для отбрасывания устаревших сообщений из очереди (stale data discard)
 * @param sequence глобальный монотонный номер сообщения потока
 * @param exchangeTime биржевое время котировки, если есть в сообщении
 */
data class QuoteTick(
    val ticker: String,
    val price: BigDecimal,
    val receivedAt: Instant = Instant.now(),
    val sequence: Long = 0,
    val exchangeTime: Instant? = null,
)

/**
 * WebSocket-клиент Alor.
 *
 * Подписывается на исполнения ордеров (OrdersGetAndSubscribeV2) и на
 * real-time котировки (QuotesSubscribe). Оба потока имеют встроенное
 * переподключение с экспоненциальным backoff (1s, 2s, 4s, ... max 60s)
 * и backpressure с семантикой DROP_OLDEST на входящих сообщениях.
 *
 * Heartbeat-мониторинг (через [WebSocketManager]):
 * - ping каждые [AlorConfig.wsHeartbeatIntervalMs]; успешная отправка фиксируется
 *   как активность потока. Ошибка отправки закрывает сессию → переподключение.
 * - Watchdog менеджера: если данные/ping не проходят дольше
 *   [AlorConfig.wsHeartbeatTimeoutMs] — поток помечается DISCONNECTED, что
 *   триггерит полную State Reconciliation (REST-портфель) ещё до того, как
 *   транспорт обнаружит обрыв.
 * - При КАЖДОМ переподключении публикуется [WsConnectionEvent] →
 *   [com.trading.bot.service.StateReconciliationService] сверяет заявки,
 *   позиции и сделки через REST (никаких торгов на «мёртвых» данных).
 *
 * Stale data discard:
 * - Каждое сообщение котировки помечается временем приёма ДО буфера очереди;
 *   сообщения, задержанные в очереди дольше [AlorConfig.wsStaleMessageAgeMs],
 *   и сообщения в неправильном порядке (старее последнего принятого по тикеру)
 *   отбрасываются ([WebSocketManager.isQuoteStale]).
 *
 * Метрики:
 * - alor.ws.reconnect / alor.ws.quotes.reconnect — переподключения
 * - alor.ws.disconnected / alor.ws.quotes.disconnected — отказ после MAX_ATTEMPTS
 * - alor.ws.drop / alor.ws.quotes.drop — сброшенные сообщения при переполнении буфера
 * - alor.ws.quote_received / alor.ws.execution_received — входящие сообщения
 * - alor.ws.message.lag — задержка от приёма тика до обработки (Timer)
 * - alor.ws.*.heartbeat_failed — неудачный heartbeat-ping
 * - alor.ws.quotes.stale_discarded / alor.ws.quotes.out_of_order — устаревшие котировки
 *
 * @see <a href="https://alor.dev/docs">Alor API документация</a>
 */
@Component
class AlorWebSocketClient(
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val webSocketManager: WebSocketManager,
) {
    private val logger = KotlinLogging.logger {}
    private val wsClient = ReactorNettyWebSocketClient()
    private val maxAttempts = 10
    private val incomingBufferCapacity = 1000

    /**
     * Поток отчётов об исполнении. Переподключение встроено; при реконнекте
     * публикуется событие в [WebSocketManager] → State Reconciliation.
     */
    fun subscribeToOrders(): Flow<ExecutionReport> =
        callbackFlow {
            var cancelled = false

            lateinit var scheduleReconnect: (Int) -> Unit
            lateinit var connect: (Int) -> Unit

            scheduleReconnect = { nextAttempt: Int ->
                if (cancelled || nextAttempt > maxAttempts) {
                    logger.error { "Alor WS: max reconnect attempts ($maxAttempts) reached, giving up" }
                    meterRegistry.counter("alor.ws.disconnected", Tags.of("reason", "MAX_ATTEMPTS")).increment()
                    close()
                } else {
                    val backoffSeconds = reconnectDelaySeconds(nextAttempt)
                    logger.warn { "Alor WS: reconnecting in ${backoffSeconds}s (attempt $nextAttempt/$maxAttempts)" }
                    meterRegistry.counter("alor.ws.reconnect").increment()
                    launch {
                        delay(backoffSeconds * 1000)
                        if (!cancelled) connect(nextAttempt)
                    }
                }
            }

            connect = { currentAttempt: Int ->
                if (!cancelled) {
                    try {
                        val url = URI.create(wsUrl())
                        wsClient
                            .execute(url) { session ->
                                val heartbeat = startHeartbeat(session, "alor.ws", WsStream.ORDERS)
                                val subscribeMsg =
                                    session.textMessage(
                                        objectMapper.writeValueAsString(
                                            mapOf(
                                                "opcode" to "OrdersGetAndSubscribeV2",
                                                "guid" to UuidV7.uuidString(),
                                                "token" to alorConfig.token,
                                                "portfolio" to alorConfig.portfolio,
                                                "exchange" to alorConfig.exchange,
                                                "format" to "Simple",
                                            ),
                                        ),
                                    )
                                session
                                    .send(Mono.just(subscribeMsg))
                                    .doOnSuccess { webSocketManager.onConnected(WsStream.ORDERS, currentAttempt) }
                                    .thenMany(
                                        session
                                            .receive()
                                            .doOnNext { webSocketManager.onActivity(WsStream.ORDERS) }
                                            .onBackpressureBuffer(incomingBufferCapacity, BufferOverflowStrategy.DROP_OLDEST)
                                            .mapNotNull { msg -> parseExecution(msg.payloadAsText) }
                                            .doOnNext { report ->
                                                meterRegistry.counter("alor.ws.execution_received").increment()
                                                val result = trySend(report)
                                                if (result.isFailure) meterRegistry.counter("alor.ws.drop").increment()
                                            },
                                    ).then()
                                    .doFinally { heartbeat.dispose() }
                            }.subscribe(
                                { /* connection closed normally */ },
                                { err ->
                                    logger.warn(err) { "Alor WS stream error" }
                                    meterRegistry.counter("alor.ws.error").increment()
                                    webSocketManager.onDisconnected(WsStream.ORDERS, currentAttempt + 1, "STREAM_ERROR")
                                    if (!cancelled) scheduleReconnect(currentAttempt + 1)
                                },
                                {
                                    logger.info { "Alor WS connection closed" }
                                    meterRegistry.counter("alor.ws.closed").increment()
                                    webSocketManager.onDisconnected(WsStream.ORDERS, currentAttempt + 1, "STREAM_CLOSED")
                                    if (!cancelled) scheduleReconnect(currentAttempt + 1)
                                },
                            )
                    } catch (e: Exception) {
                        logger.warn(e) { "Alor WS connect failed" }
                        webSocketManager.onDisconnected(WsStream.ORDERS, currentAttempt + 1, "CONNECT_FAILED")
                        if (!cancelled) scheduleReconnect(currentAttempt + 1)
                    }
                }
            }

            connect(0)
            awaitClose { cancelled = true }
        }.buffer(capacity = incomingBufferCapacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Поток real-time котировок для списка символов. Переподключение встроено;
     * при реконнекте публикуется событие в [WebSocketManager] → State Reconciliation.
     *
     * Устаревшие сообщения (задержка в очереди > [AlorConfig.wsStaleMessageAgeMs]
     * или нарушенный порядок по тикеру) отбрасываются.
     *
     * @param symbols тикеры для подписки (например ["Si", "SBER"])
     * @return поток [QuoteTick] — последняя цена сделки каждого инструмента
     */
    fun subscribeToQuotes(symbols: List<String>): Flow<QuoteTick> =
        callbackFlow {
            if (symbols.isEmpty()) {
                close()
                return@callbackFlow
            }
            var cancelled = false

            lateinit var scheduleReconnect: (Int) -> Unit
            lateinit var connect: (Int) -> Unit

            scheduleReconnect = { nextAttempt: Int ->
                if (cancelled || nextAttempt > maxAttempts) {
                    logger.error { "Alor WS quotes: max reconnect attempts ($maxAttempts) reached, giving up" }
                    meterRegistry.counter("alor.ws.quotes.disconnected", Tags.of("reason", "MAX_ATTEMPTS")).increment()
                    close()
                } else {
                    val backoffSeconds = reconnectDelaySeconds(nextAttempt)
                    logger.warn { "Alor WS quotes: reconnecting in ${backoffSeconds}s (attempt $nextAttempt/$maxAttempts)" }
                    meterRegistry.counter("alor.ws.quotes.reconnect").increment()
                    launch {
                        delay(backoffSeconds * 1000)
                        if (!cancelled) connect(nextAttempt)
                    }
                }
            }

            connect = { currentAttempt: Int ->
                if (!cancelled) {
                    try {
                        val url = URI.create(wsUrl())
                        wsClient
                            .execute(url) { session ->
                                val heartbeat = startHeartbeat(session, "alor.ws.quotes", WsStream.QUOTES)
                                val subscribeMsg =
                                    session.textMessage(
                                        objectMapper.writeValueAsString(
                                            mapOf(
                                                "opcode" to "QuotesSubscribe",
                                                "guid" to UuidV7.uuidString(),
                                                "token" to alorConfig.token,
                                                "exchange" to alorConfig.exchange,
                                                "format" to "Simple",
                                                "guids" to
                                                    symbols.map { symbol ->
                                                        mapOf("guid" to "q-$symbol", "symbol" to symbol)
                                                    },
                                            ),
                                        ),
                                    )
                                session
                                    .send(Mono.just(subscribeMsg))
                                    .doOnSuccess { webSocketManager.onConnected(WsStream.QUOTES, currentAttempt) }
                                    .thenMany(
                                        session
                                            .receive()
                                            .doOnNext { webSocketManager.onActivity(WsStream.QUOTES) }
                                            .map { msg -> msg to Instant.now() }
                                            .onBackpressureBuffer(incomingBufferCapacity, BufferOverflowStrategy.DROP_OLDEST)
                                            .mapNotNull { (msg, receivedAt) ->
                                                val tick = parseQuote(msg.payloadAsText) ?: return@mapNotNull null
                                                tick.copy(
                                                    receivedAt = receivedAt,
                                                    sequence = webSocketManager.nextSequence(WsStream.QUOTES),
                                                )
                                            }.filter { tick ->
                                                val stale =
                                                    webSocketManager.isQuoteStale(
                                                        tick.ticker,
                                                        tick.receivedAt,
                                                        tick.sequence,
                                                        tick.exchangeTime,
                                                    )
                                                if (stale) {
                                                    meterRegistry
                                                        .counter("alor.ws.quotes.drop", Tags.of("ticker", tick.ticker))
                                                        .increment()
                                                }
                                                !stale
                                            }.doOnNext { tick ->
                                                meterRegistry
                                                    .counter(
                                                        "alor.ws.quote_received",
                                                        Tags.of("ticker", tick.ticker),
                                                    ).increment()
                                                val result = trySend(tick)
                                                if (result.isFailure) {
                                                    meterRegistry.counter("alor.ws.quotes.drop").increment()
                                                    meterRegistry.counter("alor.ws.drop").increment()
                                                }
                                            },
                                    ).then()
                                    .doFinally { heartbeat.dispose() }
                            }.subscribe(
                                { /* connection closed normally */ },
                                { err ->
                                    logger.warn(err) { "Alor WS quotes stream error" }
                                    meterRegistry.counter("alor.ws.quotes.error").increment()
                                    webSocketManager.onDisconnected(WsStream.QUOTES, currentAttempt + 1, "STREAM_ERROR")
                                    if (!cancelled) scheduleReconnect(currentAttempt + 1)
                                },
                                {
                                    logger.info { "Alor WS quotes connection closed" }
                                    meterRegistry.counter("alor.ws.quotes.closed").increment()
                                    webSocketManager.onDisconnected(WsStream.QUOTES, currentAttempt + 1, "STREAM_CLOSED")
                                    if (!cancelled) scheduleReconnect(currentAttempt + 1)
                                },
                            )
                    } catch (e: Exception) {
                        logger.warn(e) { "Alor WS quotes connect failed" }
                        webSocketManager.onDisconnected(WsStream.QUOTES, currentAttempt + 1, "CONNECT_FAILED")
                        if (!cancelled) scheduleReconnect(currentAttempt + 1)
                    }
                }
            }

            connect(0)
            awaitClose { cancelled = true }
        }.buffer(capacity = incomingBufferCapacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun wsUrl(): String {
        val base = alorConfig.wsUrl.removeSuffix("/")
        return if (base.contains("?")) "$base&token=${alorConfig.token}" else "$base?token=${alorConfig.token}"
    }

    /**
     * Экспоненциальный backoff: 1s, 2s, 4s, 8s, 16s, 32s, затем плато 60s.
     *
     * @param attempt номер попытки (>= 1)
     */
    private fun reconnectDelaySeconds(attempt: Int): Long = minOf(1L shl (attempt - 1), 60L)

    /**
     * Heartbeat: ping каждые [AlorConfig.wsHeartbeatIntervalMs].
     *
     * Успешная отправка фиксируется как активность потока в [WebSocketManager]
     * (liveness без зависимости от наличия входящих данных — важно в тихие
     * периоды, например на обеденном перерыве биржи). Ошибка отправки говорит
     * о «мёртвом» соединении → сессия закрывается, поток переподключается.
     *
     * Heartbeat привязан к жизненному циклу сессии: [Disposable] освобождается
     * в `doFinally` при завершении потока (больше нет утечки ping-корутины).
     *
     * @param session активная WebSocket-сессия
     * @param metricPrefix префикс метрик (alor.ws | alor.ws.quotes)
     * @param stream поток для регистрации активности в менеджере
     */
    private fun startHeartbeat(
        session: WebSocketSession,
        metricPrefix: String,
        stream: WsStream,
    ): Disposable =
        Flux
            .interval(Duration.ofMillis(alorConfig.wsHeartbeatIntervalMs))
            .onErrorResume { Mono.empty() }
            .concatMap {
                session
                    .send(Mono.just(session.pingMessage { it.allocateBuffer(1) }))
                    .doOnSuccess { webSocketManager.onActivity(stream) }
                    .onErrorResume { err ->
                        logger.warn(err) { "$metricPrefix heartbeat ping FAILED — closing session to force reconnect" }
                        meterRegistry.counter("$metricPrefix.heartbeat_failed").increment()
                        session.close().onErrorResume { Mono.empty() }
                    }
            }.subscribe(
                { /* ping sent */ },
                { err -> logger.warn(err) { "$metricPrefix heartbeat stopped" } },
            )

    /**
     * Разбирает входящее WS-сообщение в ExecutionReport.
     * Возвращает null, если сообщение не относится к ордерам.
     */
    fun parseExecution(json: String): ExecutionReport? {
        return try {
            val j = objectMapper.readTree(json)
            val opcode = j.path("opcode").asString("")
            if (opcode.isNotBlank() && opcode != "OrdersGetAndSubscribeV2") {
                // Родительские/служебные сообщения (подтверждение подписки и т.п.) — пропускаем.
                return null
            }
            val orderId =
                j
                    .path("orderNumber")
                    .asString()
                    .ifBlank { j.path("id").asString() }
                    .ifBlank { j.path("orderNo").asString() }
                    .ifBlank { return null }

            val statusRaw = j.path("status").asString("").lowercase()
            val status =
                when {
                    statusRaw.contains("fill") &&
                        j.path("filledQty").asInt(0) > 0 &&
                        j.path("filledQty").asInt(0) >= j.path("quantity").asInt(0) -> OrderStatus.FILLED

                    statusRaw.contains("fill") -> OrderStatus.PARTIALLY_FILLED

                    statusRaw.contains("cancel") -> OrderStatus.CANCELED

                    statusRaw.contains("reject") -> OrderStatus.REJECTED

                    statusRaw.isNotBlank() -> OrderStatus.NEW

                    else -> OrderStatus.UNKNOWN
                }

            val filledQty =
                j
                    .path("filledQty")
                    .asInt(0)
                    .let { if (it == 0) j.path("filledQuantity").asInt(0) else it }
            val avgPrice =
                j.path("avgFillPrice").asString().toBigDecimalOrNull()
                    ?: j.path("filledPrice").asString().toBigDecimalOrNull()
                    ?: j.path("price").asString().toBigDecimalOrNull()

            ExecutionReport(
                orderId = orderId,
                status = status,
                filledQty = filledQty,
                avgPrice = avgPrice,
                ticker =
                    j
                        .path("ticker")
                        .asString()
                        .ifBlank { j.path("symbol").asString() }
                        .takeIf { it.isNotBlank() },
                side = j.path("side").asString().takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Alor WS message: ${json.take(500)}" }
            null
        }
    }

    /**
     * Разбирает входящее WS-сообщение котировки в [QuoteTick].
     *
     * Сообщение Alor в Simple-формате:
     * ```json
     * {"guid":"q-SBER","quotes":[{"price":280.5,"volume":0,"o":"Last","time":167...,"oi":0}, ...]}
     * ```
     * Приоритет цены: `o == "Last"` (последняя сделка), иначе mid между Bid/Offer.
     * Биржевое время берётся из котировок (при наличии) для отбрасывания
     * устаревших сообщений. Возвращает null для служебных сообщений.
     */
    fun parseQuote(json: String): QuoteTick? {
        return try {
            val j = objectMapper.readTree(json)
            val symbol =
                j
                    .path("guid")
                    .asString()
                    .removePrefix("q-")
                    .takeIf { it.isNotBlank() }
                    ?: j.path("symbol").asString().takeIf { it.isNotBlank() }
                    ?: return null

            val quotes = j.path("quotes")
            if (!quotes.isArray) return null

            var last: BigDecimal? = null
            var bid: BigDecimal? = null
            var offer: BigDecimal? = null
            var exchangeTime: Instant? = null
            for (q in quotes) {
                when (q.path("o").asString()) {
                    "Last" -> last = q.path("price").asString().toBigDecimalOrNull()
                    "Bid" -> bid = q.path("price").asString().toBigDecimalOrNull()
                    "Offer" -> offer = q.path("price").asString().toBigDecimalOrNull()
                }
                if (exchangeTime == null) exchangeTime = parseTime(q.path("time"))
                if (last != null) break
            }
            if (exchangeTime == null) exchangeTime = parseTime(j.path("time"))

            val price =
                last ?: if (bid != null && offer != null) {
                    bid.add(offer).divide(BigDecimal(2), 6, RoundingMode.HALF_UP)
                } else {
                    bid ?: offer
                } ?: return null

            QuoteTick(ticker = symbol, price = price, exchangeTime = exchangeTime)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Alor WS quote: ${json.take(500)}" }
            null
        }
    }

    private fun parseTime(node: tools.jackson.databind.JsonNode): Instant? {
        val raw = node.asLong(0)
        if (raw <= 0) return null
        return if (raw > 9_999_999_999L) Instant.ofEpochMilli(raw) else Instant.ofEpochSecond(raw)
    }
}
