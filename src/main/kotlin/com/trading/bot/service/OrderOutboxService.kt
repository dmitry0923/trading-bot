package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.infrastructure.UuidV7
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.OrderOutbox
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
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
import kotlin.math.abs
import kotlin.random.Random

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
 *   NOT_FOUND → дополнительная сверка close-ордеров по qty позиции
 *               ([closeReconcileByPositionDelta]): позиция на бирже уменьшена/закрыта →
 *               close исполнился (закрывает окно eventual consistency квери-API заявок,
 *               когда исполнившийся маркет-ордер уже вышел из списка открытых); иначе —
 *               безопасно переотправляем.
 *
 * Bounded retry с экспоненциальным backoff + jitter:
 * - Worker переотправляет PENDING старше 30 сек и FAILED c retryCount < maxOrderRetries,
 *   между попытками выдерживая LEAST(2^retryCount * base, max) секунд (+ jitter).
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
    private val positionRepo: PositionRepository,
    private val alorClient: AlorClient,
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val distributedLockService: DistributedLockService,
    private val distributedLockConfig: DistributedLockConfig,
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
     * @param stopPrice цена-триггер стоп/тейк-заявки (type="stop"/"take-profit")
     * @param purpose назначение ордера ("entry"/"close"/"sl"/"tp") — фильтр реконсилятора
     */
    suspend fun placeOrder(
        ticker: String,
        side: String,
        qty: Int,
        price: BigDecimal?,
        type: String,
        positionId: Long? = null,
        closeReason: String? = null,
        stopPrice: BigDecimal? = null,
        purpose: String? = null,
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
                    "stopPrice" to stopPrice?.toPlainString(),
                    "purpose" to purpose,
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

    /**
     * Гарантированная отмена биржевой заявки (защитные SL/TP при закрытии позиции,
     * снятие старой заявки при перевыставлении) — тоже через outbox, чтобы отмена
     * дошла даже после падения бота.
     *
     * @param positionId id позиции (для фильтрации строк реконсилятором)
     * @param orderId биржевой orderId отменяемой заявки
     */
    suspend fun placeCancelOrder(
        positionId: Long,
        orderId: String,
    ): PlaceOrderResult {
        val idempotencyKey = UuidV7.uuidString()
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "ticker" to "",
                    "type" to "cancel",
                    "idempotencyKey" to idempotencyKey,
                    "positionId" to positionId,
                    "purpose" to "cancel",
                    "orderId" to orderId,
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
        logger.info { "Outbox cancel saved: ${outbox.id} order=$orderId pos=$positionId" }
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
        val stopPrice =
            payload
                .path("stopPrice")
                .asString()
                .takeIf { it.isNotBlank() && it != "null" }
                ?.toBigDecimal()
        val type = payload.path("type").asString("limit")
        val idempotencyKey = outbox.idempotencyKey ?: payload.path("idempotencyKey").asString()
        val isCancel = type == "cancel"

        // STATE RECONCILIATION перед любым ПОВТОРНЫМ запросом.
        // (Отмена — отдельный тип: сверка по idempotencyKey ордера к ней не применима.)
        if (outbox.retryCount > 0 && !isCancel) {
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
                    when (closeReconcileByPositionDelta(outbox, ticker, side, qty)) {
                        CloseReconcile.CONFIRMED -> {
                            // Close-ордер подтверждён по изменению qty позиции на бирже
                            // (квери-API заявок мог отстать / ордер уже исполнился и вышел из open orders).
                            // Повторно НЕ отправляем — это закрывает окно double execution.
                            val fallbackOrder = outbox.idempotencyKey ?: id.toString()
                            outboxRepo.markSent(id, fallbackOrder)
                            meterRegistry.counter("outbox.close_confirmed_by_position", Tags.of("type", type)).increment()
                            logger.info {
                                "Outbox ${outbox.id} close CONFIRMED by position delta — " +
                                    "no re-send, no double execution (orderNumber-fallback=$fallbackOrder)"
                            }
                            return PlaceOrderResult(id, fallbackOrder, success = true)
                        }

                        CloseReconcile.REDUCED_UNCONFIRMED -> {
                            // Позиция уже уменьшена/бирже недоступна — ордер исполнился или в полёте.
                            // НЕ переотправляем (fail-safe против double execution), ждём следующего цикла.
                            logger.warn {
                                "Outbox ${outbox.id} close: position reduced/unknown — skip re-send this cycle"
                            }
                            meterRegistry.counter("outbox.close_reduced_unconfirmed", Tags.of("type", type)).increment()
                            return PlaceOrderResult(id, null, success = false, uncertain = true)
                        }

                        CloseReconcile.NOT_EXECUTED -> {
                            // Ордера на бирже нет, позиция не изменилась — безопасно переотправить с тем же ключом.
                            logger.info { "Outbox ${outbox.id} reconciled NOT_FOUND — safe to re-send (attempt ${outbox.retryCount + 1})" }
                        }
                    }
                }
            }
        }

        return try {
            val orderId =
                when (type) {
                    "limit" -> price?.let { alorClient.placeLimitOrder(ticker, side, qty, it, idempotencyKey) }
                    "market" -> alorClient.placeMarketOrder(ticker, side, qty, idempotencyKey)
                    "stop" -> stopPrice?.let { alorClient.placeStopOrder(ticker, side, qty, it, idempotencyKey) }
                    "take-profit" -> stopPrice?.let { alorClient.placeTakeProfitOrder(ticker, side, qty, it, idempotencyKey) }
                    "cancel" ->
                        when (alorClient.cancelOrder(payload.path("orderId").asString(), idempotencyKey)) {
                            // CONFIRMED — заявка снята; REJECTED — «не рабочая» (уже отменена/
                            // исполнена/не найдена) — обе однозначны, ордера больше нет.
                            AlorClient.CancelResult.CONFIRMED, AlorClient.CancelResult.REJECTED -> "cancelled"
                            AlorClient.CancelResult.UNCERTAIN -> null
                        }
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
     * Результат сверки close-ордера по изменению qty позиции на бирже.
     */
    private enum class CloseReconcile {
        /** Позиция закрыта/уменьшена на ожидаемый объём — close исполнился, НЕ переотправляем. */
        CONFIRMED,

        /** Позиция частично уменьшена или REST недоступен — подтвердить нельзя, НЕ переотправляем. */
        REDUCED_UNCONFIRMED,

        /** Позиция не изменилась — close не исполнился, безопасно переотправить. */
        NOT_EXECUTED,
    }

    /**
     * Вторичная State Reconciliation для close-ордеров по qty позиции (Gap: eventual
     * consistency квери-API заявок). Когда GET /client/orders даёт NOT_FOUND, а close-ордер
     * уже исполнился (маркет-ордер ушёл из списка открытых заявок), позиция на бирже
     * уменьшается/закрывается — по этому сигналу ордер считается исполненным.
     *
     * Применяется только к close-заявкам ([Position.pendingClose]); entry NOT_FOUND
     * обрабатывается прежним образом (ре-сенд с тем же idempotency key).
     */
    private suspend fun closeReconcileByPositionDelta(
        outbox: OrderOutbox,
        ticker: String,
        side: String,
        qty: Int,
    ): CloseReconcile {
        val positionId = outbox.positionId ?: return CloseReconcile.NOT_EXECUTED
        val pos =
            try {
                positionRepo.findById(positionId)
            } catch (e: Exception) {
                logger.warn(e) { "Outbox ${outbox.id}: cannot load position $positionId for close reconcile" }
                return CloseReconcile.NOT_EXECUTED
            }
        if (!pos.pendingClose) return CloseReconcile.NOT_EXECUTED

        val signed =
            if (pos.direction == PositionDirection.LONG) {
                pos.quantity.toLong()
            } else {
                -pos.quantity.toLong()
            }
        val delta = if (side == "sell") -qty.toLong() else qty.toLong()
        val expectedSigned = signed + delta

        return when (val result = alorClient.getPositions()) {
            is AlorClient.ReconcileResult.Failed -> {
                // Биржа недоступна — подтвердить нельзя → fail-safe (не переотправляем).
                logger.warn { "Outbox ${outbox.id}: positions REST failed during close reconcile — skip re-send" }
                CloseReconcile.REDUCED_UNCONFIRMED
            }

            is AlorClient.ReconcileResult.Ok -> {
                val exchangeQty =
                    result.items
                        .firstOrNull { it.ticker.equals(ticker, ignoreCase = true) }
                        ?.qty
                        ?: 0L
                val expectedMet =
                    (expectedSigned >= 0 && exchangeQty <= expectedSigned) ||
                        (expectedSigned < 0 && exchangeQty >= expectedSigned)
                when {
                    exchangeQty == 0L || expectedMet -> CloseReconcile.CONFIRMED
                    abs(exchangeQty) < abs(signed) -> CloseReconcile.REDUCED_UNCONFIRMED
                    else -> CloseReconcile.NOT_EXECUTED
                }
            }
        }
    }

    /**
     * Worker: переотправляет PENDING старше 30 сек и FAILED с retryCount < maxOrderRetries.
     * Каждая повторная отправка предваряется State Reconciliation (см. [dispatch]).
     */
    @Scheduled(fixedDelay = 10000)
    fun processPending() {
        scope.launch {
            distributedLockService.runExclusive(
                name = "scheduler:outbox-worker",
                ttlSeconds = distributedLockConfig.schedulerTtlSeconds,
            ) {
                try {
                    // Экспоненциальный backoff + jitter между повторными доставками:
                    // каждая следующая попытка откладывается дольше (LEAST(2^retry * base, max) + jitter).
                    val pending =
                        outboxRepo.findRetryable(
                            maxRetries = alorConfig.maxOrderRetries,
                            backoffBaseSeconds = alorConfig.outboxBackoffBaseSeconds,
                            backoffMaxSeconds = alorConfig.outboxBackoffMaxSeconds,
                            jitterSeconds = Random.nextInt(0, 6),
                        )
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
}
