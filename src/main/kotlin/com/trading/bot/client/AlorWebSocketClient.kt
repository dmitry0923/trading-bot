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
import java.util.UUID

/**
 * WebSocket-клиент Alor.
 *
 * Подписывается на исполнения ордеров (OrdersGetAndSubscribeV2)
 * и отдаёт поток ExecutionReport с автоматическим переподключением
 * (до 5 попыток с экспоненциальным backoff).
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
}
