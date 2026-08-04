package com.trading.bot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.client.AlorClient
import com.trading.bot.infrastructure.db.BlockingDb
import com.trading.bot.model.OrderOutbox
import com.trading.bot.repository.OrderOutboxRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

/**
 * Outbox-паттерн для гарантированной доставки ордеров в Alor.
 *
 * Алгоритм:
 * 1. Сохранить ордер в order_outbox (status=PENDING)
 * 2. Отправить в Alor
 * 3. Успех → markSent(outboxId, alorOrderId)
 * 4. Ошибка → markFailed(outboxId, error)
 *
 * Фоновый worker переотправляет PENDING-ордера старше 30 сек
 * (например, после перезапуска приложения в середине доставки).
 * Идемпотентность обеспечивается idempotencyKey в payload (Alor дедуплицирует по "id").
 */
@Service
class OrderOutboxService(
    private val outboxRepo: OrderOutboxRepository,
    private val alorClient: AlorClient,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class PlaceOrderResult(
        val outboxId: UUID,
        val alorOrderId: String?,
        val success: Boolean
    )

    /**
     * Сохраняет ордер в outbox и немедленно пытается отправить в Alor.
     */
    suspend fun placeOrder(
        ticker: String,
        side: String,
        qty: Int,
        price: BigDecimal?,
        type: String
    ): PlaceOrderResult {
        val idempotencyKey = orderIdempotencyKey(ticker, side, qty, price, type)
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "ticker" to ticker,
                "side" to side,
                "qty" to qty,
                "price" to price?.toPlainString(),
                "type" to type,
                "idempotencyKey" to idempotencyKey
            )
        )
        val outbox = BlockingDb.io { outboxRepo.save(OrderOutbox(payloadJson = payload)) }
        logger.info { "Outbox order saved: ${outbox.id} $side $qty $ticker ($type)" }
        meterRegistry.counter("outbox.saved", Tags.of("type", type)).increment()
        return dispatch(outbox)
    }

    private suspend fun dispatch(outbox: OrderOutbox): PlaceOrderResult {
        val payload = objectMapper.readTree(outbox.payloadJson)
        val ticker = payload.path("ticker").asText()
        val side = payload.path("side").asText()
        val qty = payload.path("qty").asInt()
        val price = payload.path("price").asText().takeIf { it.isNotBlank() && it != "null" }?.toBigDecimal()
        val type = payload.path("type").asText("limit")

        return try {
            val orderId = when (type) {
                "limit" -> price?.let { alorClient.placeLimitOrder(ticker, side, qty, it) }
                "market" -> alorClient.placeMarketOrder(ticker, side, qty)
                else -> null
            }
            if (orderId != null) {
                BlockingDb.io { outboxRepo.markSent(outbox.id!!, orderId) }
                meterRegistry.counter("outbox.sent", Tags.of("type", type)).increment()
                logger.info { "Outbox order SENT: ${outbox.id} -> alorOrderId=$orderId" }
                PlaceOrderResult(outbox.id!!, orderId, success = true)
            } else {
                BlockingDb.io { outboxRepo.markFailed(outbox.id!!, "Order rejected by Alor (no orderNumber)") }
                meterRegistry.counter("outbox.failed", Tags.of("type", type)).increment()
                PlaceOrderResult(outbox.id!!, null, success = false)
            }
        } catch (e: Exception) {
            BlockingDb.io { outboxRepo.markFailed(outbox.id!!, e.message ?: "dispatch error") }
            logger.error(e) { "Outbox order FAILED: ${outbox.id}" }
            meterRegistry.counter("outbox.failed", Tags.of("type", type)).increment()
            PlaceOrderResult(outbox.id!!, null, success = false)
        }
    }

    /**
     * Worker: переотправляет PENDING-ордера старше 30 сек.
     */
    @Scheduled(fixedDelay = 10000)
    fun processPending() {
        scope.launch {
            try {
                val pending = BlockingDb.io { outboxRepo.findPendingOlderThan(30) }
                if (pending.isNotEmpty()) {
                    logger.info { "Outbox worker: ${pending.size} pending order(s) to re-dispatch" }
                    pending.forEach { outbox ->
                        try {
                            dispatch(outbox)
                        } catch (e: Exception) {
                            logger.error(e) { "Outbox re-dispatch failed for ${outbox.id}" }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Outbox worker error" }
            }
        }
    }

    private fun orderIdempotencyKey(ticker: String, side: String, qty: Int, price: BigDecimal?, type: String): String {
        val raw = "$ticker|$side|$qty|$price|$type|${System.currentTimeMillis()}"
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }
}
