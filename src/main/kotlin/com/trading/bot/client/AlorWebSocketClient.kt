package com.trading.bot.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.config.AlorConfig
import com.trading.bot.model.ExecutionReport
import com.trading.bot.model.OrderStatus
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.BufferOverflowStrategy
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Тик котировки из WebSocket Alor.
 *
 * @param ticker тикер инструмента
 * @param price последняя цена сделки (Last), либо mid между лучшим Bid/Offer
 * @param receivedAt момент получения тика из WS — для метрики задержки обработки
 */
data class QuoteTick(
    val ticker: String,
    val price: BigDecimal,
    val receivedAt: Instant = Instant.now(),
)

/**
 * WebSocket-клиент Alor.
 *
 * Подписывается на исполнения ордеров (OrdersGetAndSubscribeV2) и на
 * real-time котировки (QuotesSubscribe). Оба потока имеют встроенное
 * переподключение с экспоненциальным backoff (1s, 2s, 4s, ... max 60s),
 * heartbeat (ping каждые 30s, watchdog по pong 45s) и backpressure
 * с семантикой DROP_OLDEST на входящих сообщениях.
 *
 * Метрики:
 * - alor.ws.reconnect / alor.ws.quotes.reconnect — переподключения
 * - alor.ws.disconnected / alor.ws.quotes.disconnected — отказ после MAX_ATTEMPTS
 * - alor.ws.drop / alor.ws.quotes.drop — сброшенные сообщения при переполнении буфера
 * - alor.ws.quote_received / alor.ws.execution_received — входящие сообщения
 * - alor.ws.message.lag — задержка от приёма тика до обработки (Timer)
 *
 * @see <a href="https://alor.dev/docs">Alor API документация</a>
 */
@Component
class AlorWebSocketClient(
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val wsClient = ReactorNettyWebSocketClient()
    private val maxAttempts = 10
    private val heartbeatInterval = Duration.ofSeconds(30)
    private val heartbeatTimeout = Duration.ofSeconds(45)
    private val incomingBufferCapacity = 1000

    /**
     * Поток отчётов об исполнении. Переподключение встроено.
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
                                val subscribeMsg =
                                    session.textMessage(
                                        objectMapper.writeValueAsString(
                                            mapOf(
                                                "opcode" to "OrdersGetAndSubscribeV2",
                                                "guid" to UUID.randomUUID().toString(),
                                                "token" to alorConfig.token,
                                                "portfolio" to alorConfig.portfolio,
                                                "exchange" to alorConfig.exchange,
                                                "format" to "Simple",
                                            ),
                                        ),
                                    )
                                startHeartbeat(session, "alor.ws")
                                session
                                    .send(Mono.just(subscribeMsg))
                                    .thenMany(
                                        session
                                            .receive()
                                            .timeout(heartbeatTimeout)
                                            .onBackpressureBuffer(incomingBufferCapacity, BufferOverflowStrategy.DROP_OLDEST)
                                            .mapNotNull { msg -> parseExecution(msg.payloadAsText) }
                                            .doOnNext { report ->
                                                meterRegistry.counter("alor.ws.execution_received").increment()
                                                if (report != null) {
                                                    val result = trySend(report)
                                                    if (result.isFailure) meterRegistry.counter("alor.ws.drop").increment()
                                                }
                                            },
                                    ).then()
                            }.subscribe(
                                { /* connection closed normally */ },
                                { err ->
                                    logger.warn(err) { "Alor WS stream error" }
                                    meterRegistry.counter("alor.ws.error").increment()
                                    if (!cancelled) scheduleReconnect(currentAttempt + 1)
                                },
                                {
                                    logger.info { "Alor WS connection closed" }
                                    meterRegistry.counter("alor.ws.closed").increment()
                                    if (!cancelled) scheduleReconnect(currentAttempt + 1)
                                },
                            )
                    } catch (e: Exception) {
                        logger.warn(e) { "Alor WS connect failed" }
                        if (!cancelled) scheduleReconnect(currentAttempt + 1)
                    }
                }
            }

            connect(0)
            awaitClose { cancelled = true }
        }.buffer(capacity = incomingBufferCapacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Поток real-time котировок для списка символов. Переподключение встроено.
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
                                val subscribeMsg =
                                    session.textMessage(
                                        objectMapper.writeValueAsString(
                                            mapOf(
                                                "opcode" to "QuotesSubscribe",
                                                "guid" to UUID.randomUUID().toString(),
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
                                startHeartbeat(session, "alor.ws.quotes")
                                session
                                    .send(Mono.just(subscribeMsg))
                                    .thenMany(
                                        session
                                            .receive()
                                            .timeout(heartbeatTimeout)
                                            .onBackpressureBuffer(incomingBufferCapacity, BufferOverflowStrategy.DROP_OLDEST)
                                            .mapNotNull { msg -> parseQuote(msg.payloadAsText) }
                                            .doOnNext { tick ->
                                                meterRegistry
                                                    .counter(
                                                        "alor.ws.quote_received",
                                                        Tags.of("ticker", tick?.ticker ?: "UNKNOWN"),
                                                    ).increment()
                                                if (tick != null) {
                                                    val result = trySend(tick)
                                                    if (result.isFailure) {
                                                        meterRegistry.counter("alor.ws.quotes.drop").increment()
                                                        meterRegistry.counter("alor.ws.drop").increment()
                                                    }
                                                }
                                            },
                                    ).then()
                            }.subscribe(
                                { /* connection closed normally */ },
                                { err ->
                                    logger.warn(err) { "Alor WS quotes stream error" }
                                    meterRegistry.counter("alor.ws.quotes.error").increment()
                                    if (!cancelled) scheduleReconnect(currentAttempt + 1)
                                },
                                {
                                    logger.info { "Alor WS quotes connection closed" }
                                    meterRegistry.counter("alor.ws.quotes.closed").increment()
                                    if (!cancelled) scheduleReconnect(currentAttempt + 1)
                                },
                            )
                    } catch (e: Exception) {
                        logger.warn(e) { "Alor WS quotes connect failed" }
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
     * Heartbeat: ping каждые 30s — сервер отвечает pong'ами, что держит
     * поток данных живым. Watchdog по liveness реализован через timeout()
     * на основном потоке данных: любое сообщение (включая pong) сбрасывает
     * таймер, а 45s тишины роняют поток → переподключение.
     *
     * @param session активная WebSocket-сессия
     * @param metricPrefix префикс метрик (alor.ws | alor.ws.quotes)
     */
    private fun startHeartbeat(
        session: WebSocketSession,
        metricPrefix: String,
    ) {
        Flux
            .interval(heartbeatInterval)
            .onErrorResume { Mono.empty() }
            .concatMap { session.send(Mono.just(session.pingMessage { it.allocateBuffer(1) })) }
            .onErrorResume { err ->
                logger.warn(err) { "$metricPrefix heartbeat ping failed" }
                Mono.empty()
            }.subscribe(
                { /* ping sent */ },
                { err -> logger.warn(err) { "$metricPrefix heartbeat stopped" } },
            )
    }

    /**
     * Разбирает входящее WS-сообщение в ExecutionReport.
     * Возвращает null, если сообщение не относится к ордерам.
     */
    fun parseExecution(json: String): ExecutionReport? {
        return try {
            val j = objectMapper.readTree(json)
            val opcode = j.path("opcode").asText("")
            if (opcode.isNotBlank() && opcode != "OrdersGetAndSubscribeV2") {
                // Родительские/служебные сообщения (подтверждение подписки и т.п.) — пропускаем.
                return null
            }
            val orderId =
                j
                    .path("orderNumber")
                    .asText()
                    .ifBlank { j.path("id").asText() }
                    .ifBlank { j.path("orderNo").asText() }
                    .ifBlank { return null }

            val statusRaw = j.path("status").asText("").lowercase()
            val status =
                when {
                    statusRaw.contains("fill") && j.path("filledQty").asInt(0) > 0 &&
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
                j.path("avgFillPrice").asText().toBigDecimalOrNull()
                    ?: j.path("filledPrice").asText().toBigDecimalOrNull()
                    ?: j.path("price").asText().toBigDecimalOrNull()

            ExecutionReport(
                orderId = orderId,
                status = status,
                filledQty = filledQty,
                avgPrice = avgPrice,
                ticker =
                    j
                        .path("ticker")
                        .asText()
                        .ifBlank { j.path("symbol").asText() }
                        .takeIf { it.isNotBlank() },
                side = j.path("side").asText().takeIf { it.isNotBlank() },
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
     * {"guid":"q-SBER","quotes":[{"price":280.5,"volume":0,"o":"Last","oi":0}, ...]}
     * ```
     * Приоритет цены: `o == "Last"` (последняя сделка), иначе mid между Bid/Offer.
     * Возвращает null для служебных сообщений.
     */
    fun parseQuote(json: String): QuoteTick? {
        return try {
            val j = objectMapper.readTree(json)
            val symbol =
                j
                    .path("guid")
                    .asText()
                    .removePrefix("q-")
                    .takeIf { it.isNotBlank() }
                    ?: j.path("symbol").asText().takeIf { it.isNotBlank() }
                    ?: return null

            val quotes = j.path("quotes")
            if (!quotes.isArray) return null

            var last: BigDecimal? = null
            var bid: BigDecimal? = null
            var offer: BigDecimal? = null
            for (q in quotes) {
                when (q.path("o").asText()) {
                    "Last" -> last = q.path("price").asText().toBigDecimalOrNull()
                    "Bid" -> bid = q.path("price").asText().toBigDecimalOrNull()
                    "Offer" -> offer = q.path("price").asText().toBigDecimalOrNull()
                }
                if (last != null) break
            }

            val price =
                last ?: if (bid != null && offer != null) {
                    bid.add(offer).divide(BigDecimal(2), 6, RoundingMode.HALF_UP)
                } else {
                    bid ?: offer
                } ?: return null

            QuoteTick(ticker = symbol, price = price)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Alor WS quote: ${json.take(500)}" }
            null
        }
    }
}
