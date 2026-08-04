package com.trading.bot.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.config.AlorConfig
import com.trading.bot.model.ExecutionReport
import com.trading.bot.model.OrderStatus
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.net.URI
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Тик котировки из WebSocket Alor.
 *
 * @param ticker тикер инструмента
 * @param price последняя цена сделки (Last), либо mid между лучшим Bid/Offer
 */
data class QuoteTick(
    val ticker: String,
    val price: BigDecimal
)

/**
 * WebSocket-клиент Alor.
 *
 * Подписывается на исполнения ордеров (OrdersGetAndSubscribeV2) и на
 * real-time котировки (QuotesSubscribe). Оба потока имеют встроенное
 * переподключение (до 5 попыток с экспоненциальным backoff).
 *
 * @see <a href="https://alor.dev/docs">Alor API документация</a>
 */
@Component
class AlorWebSocketClient(
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry
) {
    private val logger = KotlinLogging.logger {}
    private val wsClient = ReactorNettyWebSocketClient()
    private val maxAttempts = 5

    /**
     * Поток отчётов об исполнении. Переподключение встроено.
     */
    fun subscribeToOrders(): Flow<ExecutionReport> = callbackFlow {
        var cancelled = false
        var attempt = 0

        lateinit var scheduleReconnect: (Int) -> Unit
        lateinit var connect: (Int) -> Unit

        scheduleReconnect = { nextAttempt: Int ->
            if (cancelled || nextAttempt > maxAttempts) {
                logger.error { "Alor WS: max reconnect attempts ($maxAttempts) reached, giving up" }
                meterRegistry.counter("alor.ws.disconnected", Tags.of("reason", "MAX_ATTEMPTS")).increment()
                close()
            } else {
                val backoffSeconds = nextAttempt * 5L
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
                wsClient.execute(url) { session ->
                    val subscribeMsg = session.textMessage(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "opcode" to "OrdersGetAndSubscribeV2",
                                "guid" to UUID.randomUUID().toString(),
                                "token" to alorConfig.token,
                                "portfolio" to alorConfig.portfolio,
                                "exchange" to alorConfig.exchange,
                                "format" to "Simple"
                            )
                        )
                    )
                    session.send(Mono.just(subscribeMsg))
                        .thenMany(session.receive())
                        .mapNotNull { msg ->
                            parseExecution(msg.payloadAsText)
                        }
                        .doOnNext { report ->
                            meterRegistry.counter("alor.ws.execution_received").increment()
                            report?.let { trySend(it) }
                        }
                        .then()
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
                    }
                )
            } catch (e: Exception) {
                logger.warn(e) { "Alor WS connect failed" }
                if (!cancelled) scheduleReconnect(currentAttempt + 1)
            }
            }
        }

        connect(0)
        awaitClose { cancelled = true }
    }

    /**
     * Поток real-time котировок для списка символов. Переподключение встроено.
     *
     * @param symbols тикеры для подписки (например ["Si", "SBER"])
     * @return поток [QuoteTick] — последняя цена сделки каждого инструмента
     */
    fun subscribeToQuotes(symbols: List<String>): Flow<QuoteTick> = callbackFlow {
        if (symbols.isEmpty()) {
            close()
            return@callbackFlow
        }
        var cancelled = false
        var attempt = 0

        lateinit var scheduleReconnect: (Int) -> Unit
        lateinit var connect: (Int) -> Unit

        scheduleReconnect = { nextAttempt: Int ->
            if (cancelled || nextAttempt > maxAttempts) {
                logger.error { "Alor WS quotes: max reconnect attempts ($maxAttempts) reached, giving up" }
                meterRegistry.counter("alor.ws.quotes.disconnected", Tags.of("reason", "MAX_ATTEMPTS")).increment()
                close()
            } else {
                val backoffSeconds = nextAttempt * 5L
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
                    wsClient.execute(url) { session ->
                        val subscribeMsg = session.textMessage(
                            objectMapper.writeValueAsString(
                                mapOf(
                                    "opcode" to "QuotesSubscribe",
                                    "guid" to UUID.randomUUID().toString(),
                                    "token" to alorConfig.token,
                                    "exchange" to alorConfig.exchange,
                                    "format" to "Simple",
                                    "guids" to symbols.map { symbol ->
                                        mapOf("guid" to "q-$symbol", "symbol" to symbol)
                                    }
                                )
                            )
                        )
                        session.send(Mono.just(subscribeMsg))
                            .thenMany(session.receive())
                            .mapNotNull { msg -> parseQuote(msg.payloadAsText) }
                            .doOnNext { tick ->
                                meterRegistry.counter("alor.ws.quote_received", Tags.of("ticker", tick?.ticker ?: "UNKNOWN")).increment()
                                tick?.let { trySend(it) }
                            }
                            .then()
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
                        }
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Alor WS quotes connect failed" }
                    if (!cancelled) scheduleReconnect(currentAttempt + 1)
                }
            }
        }

        connect(0)
        awaitClose { cancelled = true }
    }

    private fun wsUrl(): String {
        val base = alorConfig.wsUrl.removeSuffix("/")
        return if (base.contains("?")) "$base&token=${alorConfig.token}" else "$base?token=${alorConfig.token}"
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
            val orderId = j.path("orderNumber").asText()
                .ifBlank { j.path("id").asText() }
                .ifBlank { j.path("orderNo").asText() }
                .ifBlank { return null }

            val statusRaw = j.path("status").asText("").lowercase()
            val status = when {
                statusRaw.contains("fill") && j.path("filledQty").asInt(0) > 0 &&
                    j.path("filledQty").asInt(0) >= j.path("quantity").asInt(0) -> OrderStatus.FILLED
                statusRaw.contains("fill") -> OrderStatus.PARTIALLY_FILLED
                statusRaw.contains("cancel") -> OrderStatus.CANCELED
                statusRaw.contains("reject") -> OrderStatus.REJECTED
                statusRaw.isNotBlank() -> OrderStatus.NEW
                else -> OrderStatus.UNKNOWN
            }

            val filledQty = j.path("filledQty").asInt(0)
                .let { if (it == 0) j.path("filledQuantity").asInt(0) else it }
            val avgPrice = j.path("avgFillPrice").asText().toBigDecimalOrNull()
                ?: j.path("filledPrice").asText().toBigDecimalOrNull()
                ?: j.path("price").asText().toBigDecimalOrNull()

            ExecutionReport(
                orderId = orderId,
                status = status,
                filledQty = filledQty,
                avgPrice = avgPrice,
                ticker = j.path("ticker").asText().ifBlank { j.path("symbol").asText() }.takeIf { it.isNotBlank() },
                side = j.path("side").asText().takeIf { it.isNotBlank() }
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
            val symbol = j.path("guid").asText().removePrefix("q-").takeIf { it.isNotBlank() }
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

            val price = last ?: if (bid != null && offer != null) {
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
