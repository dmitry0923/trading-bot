package com.trading.bot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.client.AlorClient
import com.trading.bot.model.OrderOutbox
import com.trading.bot.repository.OrderOutboxRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transactional outbox для аудита и синхронной доставки ордеров в Alor.
 *
 * HTTP retry внутри [AlorClient] всегда использует один clientOrderId и потому
 * не создаёт дубликаты. Неопределённые записи после падения процесса не
 * отправляются автоматически: без callback в позиционный агрегат поздний ордер
 * остался бы неуправляемым. Worker переводит их в FAILED для ручной сверки.
 */
@Service
class OrderOutboxService(
    private val outboxRepo: OrderOutboxRepository,
    private val alorClient: AlorClient,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerRunning = AtomicBoolean(false)

    data class PlaceOrderResult(
        val outboxId: UUID,
        val alorOrderId: String?,
        val success: Boolean,
    )

    suspend fun placeOrder(
        ticker: String,
        side: String,
        qty: Int,
        price: BigDecimal?,
        type: String,
    ): PlaceOrderResult {
        require(ticker.isNotBlank()) { "ticker must not be blank" }
        require(side == "buy" || side == "sell") { "side must be buy or sell" }
        require(qty > 0) { "quantity must be positive" }
        require(type == "limit" || type == "market") { "type must be limit or market" }
        require(type != "limit" || price != null) { "limit order requires price" }

        val clientOrderId = orderIdempotencyKey(ticker, side, qty, price, type)
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "ticker" to ticker,
                    "side" to side,
                    "qty" to qty,
                    "price" to price?.toPlainString(),
                    "type" to type,
                    "idempotencyKey" to clientOrderId,
                ),
            )
        val saved = outboxRepo.save(OrderOutbox(payloadJson = payload))
        val outboxId = requireNotNull(saved.id) { "Outbox repository did not assign an id" }
        logger.info { "Outbox order saved: $outboxId $side $qty $ticker ($type)" }
        meterRegistry.counter("outbox.saved", Tags.of("type", type)).increment()

        val claimed =
            outboxRepo.claim(outboxId)
                ?: return PlaceOrderResult(outboxId, null, success = false)
        return dispatch(claimed)
    }

    private suspend fun dispatch(outbox: OrderOutbox): PlaceOrderResult {
        val outboxId = requireNotNull(outbox.id) { "Cannot dispatch outbox row without id" }
        val payload = objectMapper.readTree(outbox.payloadJson)
        val ticker = payload.path("ticker").asText()
        val side = payload.path("side").asText()
        val qty = payload.path("qty").asInt()
        val price =
            payload
                .path("price")
                .asText()
                .takeIf { it.isNotBlank() && it != "null" }
                ?.toBigDecimal()
        val type = payload.path("type").asText("limit")
        val clientOrderId = payload.path("idempotencyKey").asText()

        return try {
            val orderId =
                when (type) {
                    "limit" -> {
                        price?.let {
                            alorClient.placeLimitOrder(ticker, side, qty, it, clientOrderId)
                        }
                    }

                    "market" -> {
                        alorClient.placeMarketOrder(ticker, side, qty, clientOrderId)
                    }

                    else -> {
                        null
                    }
                }
            if (orderId != null) {
                outboxRepo.markSent(outboxId, orderId)
                meterRegistry.counter("outbox.sent", Tags.of("type", type)).increment()
                logger.info { "Outbox order SENT: $outboxId -> alorOrderId=$orderId" }
                PlaceOrderResult(outboxId, orderId, success = true)
            } else {
                fail(outbox, "Order was not accepted by Alor")
                PlaceOrderResult(outboxId, null, success = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(outbox, e.message ?: "dispatch error")
            logger.error(e) { "Outbox order attempt failed: $outboxId" }
            PlaceOrderResult(outboxId, null, success = false)
        }
    }

    private suspend fun fail(
        outbox: OrderOutbox,
        error: String,
    ) {
        val outboxId = requireNotNull(outbox.id)
        val attempt = outbox.attemptCount + 1
        outboxRepo.markFailed(outboxId, attempt, error)
        meterRegistry.counter("outbox.failed").increment()
        logger.error { "Outbox order FAILED: $outboxId ($error)" }
    }

    /** Карантинизирует незавершённые после crash записи, не отправляя поздний ордер. */
    @Scheduled(fixedDelay = 30_000)
    fun quarantineUnresolved() {
        if (!workerRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                outboxRepo.claimReady().forEach { outbox ->
                    outboxRepo.markFailed(
                        requireNotNull(outbox.id),
                        outbox.attemptCount + 1,
                        "Uncertain order after process interruption; manual broker reconciliation required",
                    )
                    meterRegistry.counter("outbox.quarantined").increment()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Outbox quarantine worker error" }
            } finally {
                workerRunning.set(false)
            }
        }
    }

    private fun orderIdempotencyKey(
        ticker: String,
        side: String,
        qty: Int,
        price: BigDecimal?,
        type: String,
    ): String {
        val raw = "$ticker|$side|$qty|${price?.toPlainString()}|$type|${UUID.randomUUID()}"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel("OrderOutboxService is shutting down")
    }
}
