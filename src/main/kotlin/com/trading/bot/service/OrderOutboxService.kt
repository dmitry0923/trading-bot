package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.infrastructure.UuidV7
import com.trading.bot.model.OrderOutbox
import com.trading.bot.repository.OrderOutboxRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.util.UUID

/**
 * Outbox-паттерн для гарантированной доставки ордеров в Alor.
 *
 * Идемпотентность:
 * - `idempotencyKey` генерируется ОДИН раз на логический ордер (UUIDv7),
 *   сохраняется в строке outbox и в payload, и передаётся в Alor как "id".
 *   Все повторные попытки/переотправки используют ТОТ ЖЕ ключ → Alor
 *   дедуплицирует (никакого double execution).
 *
 * State Reconciliation перед повторным запросом:
 * - Перед ЛЮБОЙ повторной отправкой (retryCount > 0) [dispatch] вызывает
 *   [AlorClient.reconcileOrderByIdempotencyKey]:
 *   FOUND  → фиксируем реальный orderNumber, повторно НЕ отправляем;
 *   UNKNOWN → биржа недоступна, подтвердить нельзя → пропускаем цикл
 *             (fail-safe: не отправляем повторно);
 *   NOT_FOUND → ордера на бирже нет → безопасно переотправляем.
 *
 * Bounded retry:
 * - Worker переотправляет PENDING старше 30 сек и FAILED c retryCount < maxOrderRetries.
 *   [PlaceOrderResult.uncertain] сигнализирует, что запрос мог дойти до биржи
 *   (сетевой сбой/таймаут) — верхний слой не должен создавать дублирующий ордер.
 *
 * Связь с позициями:
 * - `positionId` сохраняется в payload — реконсилятор позиций находит строку outbox
 *   по позиции и завершает стейт-машину входа/закрытия.
 */
@Service
class OrderOutboxService(
    private val outboxRepo: OrderOutboxRepository,
    private val alorClient: AlorClient,
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class PlaceOrderResult(
        val outboxId: UUID,
        val alorOrderId: String?,
        val success: Boolean,
        val uncertain: Boolean = false,
    )

    /**
     * Сохраняет ордер в outbox (с уникальным idempotency key) и пытается отправить.
     *
     * @param positionId id позиции для стейт-машины входа/закрытия (null — нет позиции)
     * @param closeReason причина закрытия (для мониторинга/логов)
     */
    suspend fun placeOrder(
        ticker: String,
        side: String,
        qty: Int,
        price: BigDecimal?,
        type: String,
        positionId: Long? = null,
        closeReason: String? = null,
    ): PlaceOrderResult {
        // Ключ генерируется один раз на логический ордер — все ретраи идут с ним же.
        val idempotencyKey = UuidV7.uuidString()
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "ticker" to ticker,
                    "side" to side,
                    "qty" to qty,
                    "price" to price?.toPlainString(),
                    "type" to type,
                    "idempotencyKey" to idempotencyKey,
                    "positionId" to positionId,
                    "closeReason" to closeReason,
                ),
            )
        val outbox =
            outboxRepo.save(
                OrderOutbox(
                    payloadJson = payload,
                    idempotencyKey = idempotencyKey,
                    positionId = positionId,
                ),
            )
        logger.info { "Outbox order saved: ${outbox.id} $side $qty $ticker ($type) idem=$idempotencyKey pos=$positionId" }
        meterRegistry.counter("outbox.saved", Tags.of("type", type)).increment()
        return dispatch(outbox)
    }

    private suspend fun dispatch(outbox: OrderOutbox): PlaceOrderResult {
        val id = checkNotNull(outbox.id) { "Outbox row without id cannot be dispatched" }
        val payload = objectMapper.readTree(outbox.payloadJson)
        val ticker = payload.path("ticker").asString()
        val side = payload.path("side").asString()
        val qty = payload.path("qty").asInt()
        val price =
            payload
                .path("price")
                .asString()
                .takeIf { it.isNotBlank() && it != "null" }
                ?.toBigDecimal()
        val type = payload.path("type").asString("limit")
        val idempotencyKey = outbox.idempotencyKey ?: payload.path("idempotencyKey").asString()

        // STATE RECONCILIATION перед любым ПОВТОРНЫМ запросом.
        if (outbox.retryCount > 0) {
            when (val reconciled = alorClient.reconcileOrderByIdempotencyKey(idempotencyKey, ticker, side)) {
                is AlorClient.OrderReconciliation.Found -> {
                    outboxRepo.markSent(id, reconciled.orderNumber)
                    meterRegistry.counter("outbox.reconciled", Tags.of("type", type)).increment()
                    logger.info {
                        "Outbox ${outbox.id} reconciled: order ALREADY on exchange " +
                            "orderNumber=${reconciled.orderNumber} (no re-send, no double execution)"
                    }
                    return PlaceOrderResult(id, reconciled.orderNumber, success = true)
                }

                is AlorClient.OrderReconciliation.Unknown -> {
                    // Не можем подтвердить состояние — НЕ отправляем повторно (fail-safe).
                    logger.warn {
                        "Outbox ${outbox.id} reconciliation UNKNOWN (attempt ${outbox.retryCount}); " +
                            "skip re-send this cycle"
                    }
                    meterRegistry.counter("outbox.reconcile_unknown", Tags.of("type", type)).increment()
                    return PlaceOrderResult(id, null, success = false, uncertain = true)
                }

                is AlorClient.OrderReconciliation.NotFound -> {
                    // Ордера на бирже нет — безопасно переотправить с тем же ключом.
                    logger.info { "Outbox ${outbox.id} reconciled NOT_FOUND — safe to re-send (attempt ${outbox.retryCount + 1})" }
                }
            }
        }

        return try {
            val orderId =
                when (type) {
                    "limit" -> price?.let { alorClient.placeLimitOrder(ticker, side, qty, it, idempotencyKey) }
                    "market" -> alorClient.placeMarketOrder(ticker, side, qty, idempotencyKey)
                    else -> null
                }
            if (orderId != null) {
                outboxRepo.markSent(id, orderId)
                meterRegistry.counter("outbox.sent", Tags.of("type", type)).increment()
                logger.info { "Outbox order SENT: ${outbox.id} -> alorOrderId=$orderId (idem=$idempotencyKey)" }
                PlaceOrderResult(id, orderId, success = true)
            } else {
                outboxRepo.markFailed(id, "Order rejected by Alor (no orderNumber)")
                meterRegistry.counter("outbox.failed", Tags.of("type", type)).increment()
                logger.warn { "Outbox order REJECTED: ${outbox.id} (definitive, retry later via worker)" }
                PlaceOrderResult(id, null, success = false)
            }
        } catch (e: Exception) {
            outboxRepo.markFailed(id, e.message ?: "dispatch error")
            logger.error(e) { "Outbox order FAILED: ${outbox.id} — delivery UNCERTAIN (may have reached Alor)" }
            meterRegistry.counter("outbox.failed", Tags.of("type", type)).increment()
            PlaceOrderResult(id, null, success = false, uncertain = true)
        }
    }

    /**
     * Worker: переотправляет PENDING старше 30 сек и FAILED с retryCount < maxOrderRetries.
     * Каждая повторная отправка предваряется State Reconciliation (см. [dispatch]).
     */
    @Scheduled(fixedDelay = 10000)
    fun processPending() {
        scope.launch {
            try {
                val pending = outboxRepo.findRetryable(maxRetries = alorConfig.maxOrderRetries)
                if (pending.isNotEmpty()) {
                    logger.info { "Outbox worker: ${pending.size} order(s) to (re)dispatch" }
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
}
