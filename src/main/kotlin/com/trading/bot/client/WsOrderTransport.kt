package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.infrastructure.UuidV7
import com.trading.bot.service.DeploymentApprovalService
import com.trading.bot.service.LiveStrategyFingerprintProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * WebSocket-доставка ордеров (roadmap 13.8.2 «WebSocket-only исполнение»,
 * primary-транспорт при [AlorConfig.wsOrdersEnabled]).
 *
 * Один persistent-канал на дефолтный портфель ([AlorConfig.portfolio]): команды
 * размещения/отмены шлются по WS, подтверждение приходит событием на том же канале
 * (подписка `OrdersGetAndSubscribeV2`). Корреляция — по `id` (idempotency key) для
 * размещения и по `orderNumber` + статусу для отмены (см. [WsOrderMessages]).
 *
 * Контракт доставки (тот же, что у REST):
 * - [MatchResult.Confirmed] → возвращается orderNumber;
 * - [MatchResult.Rejected] → возвращается null (определённый отказ);
 * - таймаут ответа ([AlorConfig.wsOrderTimeoutMs]) или обрыв соединения после
 *   отправки → [OrderDeliveryUncertainException] (UNCERTAIN — повторная отправка
 *   только после State Reconciliation по idempotency key);
 * - до отправки команды канал недоступен / не LIVE / не дефолтный портфель →
 *   [OrderTransportUnavailableException] (маршрутизатор переключается на REST).
 *
 * Multi-account (roadmap v2.2): WS-канал подписан только на дефолтный портфель;
 * ордера прочих портфелей маршрутизатор шлёт через REST (полностью корректный путь
 * с реконсиляцией). События исполнения всех портфелей и так доставляются в движок
 * существующими WS-потоками `subscribeToOrders`.
 *
 * Метрики: alor.ws.orders.connected/disconnected/sent{type}/confirmed{type}/
 * rejected{type}/uncertain{type}.
 */
@Component
class WsOrderTransport(
    private val alorConfig: AlorConfig,
    private val tradingConfig: TradingConfig,
    private val meterRegistry: MeterRegistry,
    private val socketFactory: WsOrderSocketFactory = ReactorWsOrderSocketFactory(alorConfig),
    private val scope: CoroutineScope? = null,
    private val deploymentApprovalService: DeploymentApprovalService,
    private val fingerprintProvider: LiveStrategyFingerprintProvider,
) : OrderTransport {
    private val logger = KotlinLogging.logger {}

    private val coroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val session = AtomicReference<WsOrderSocketConnection?>(null)

    private val pending = ConcurrentHashMap<String, PendingRequest>()

    private val isLive: Boolean get() = tradingConfig.mode == "LIVE"

    /**
     * Execution interlock (P1): WS-доставка только в LIVE; тикер, не прошедший
     * approval + strategy fingerprint, получает определённый отказ (null) — на биржу
     * не уходит. fail-closed: неготовность/ошибка состояния => deny.
     */
    private fun denyIfNotLiveApproved(ticker: String): Boolean {
        if (deploymentApprovalService.isLiveAllowed(ticker, fingerprintProvider.fingerprint())) return false
        logger.error { "LIVE order BLOCKED for $ticker — ticker not approved or strategy fingerprint mismatch (execution interlock)" }
        meterRegistry.counter("alor.ws.orders.blocked", Tags.of("reason", "NOT_LIVE_APPROVED", "ticker", ticker)).increment()
        return true
    }

    private data class PendingRequest(
        val guid: String,
        val isCancel: Boolean,
        val orderId: String?,
        val deferred: CompletableDeferred<WsOrderMessages.MatchResult>,
    )

    init {
        if (alorConfig.wsOrdersEnabled) {
            coroutineScope.launch { runConnectionLoop() }
        }
    }

    override suspend fun placeLimit(
        ticker: String,
        side: String,
        qty: Int,
        price: BigDecimal,
        idempotencyKey: String,
        portfolio: String,
    ): String? {
        if (denyIfNotLiveApproved(ticker)) return null
        requireWsAvailable(portfolio)
        val guid = UuidV7.uuidString()
        val deferred = CompletableDeferred<WsOrderMessages.MatchResult>()
        pending[idempotencyKey] = PendingRequest(guid, isCancel = false, orderId = null, deferred)
        return try {
            val command =
                WsOrderMessages.placeLimit(
                    alorConfig.token,
                    portfolio,
                    ticker,
                    alorConfig.exchange,
                    side,
                    qty,
                    price,
                    idempotencyKey,
                    guid,
                )
            send(command, idempotencyKey, "limit")
            awaitPlaceOutcome(deferred, idempotencyKey, "limit")
        } catch (e: OrderTransportException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "WS order send failed for $ticker (idem=$idempotencyKey) — delivery UNCERTAIN" }
            meterRegistry.counter("alor.ws.orders.uncertain", Tags.of("type", "limit", "reason", "SEND_FAILED")).increment()
            throw OrderDeliveryUncertainException("WS order send failed: ${e.message}", e)
        } finally {
            pending.remove(idempotencyKey)
        }
    }

    override suspend fun placeConditional(
        type: String,
        ticker: String,
        side: String,
        qty: Int,
        stopPrice: BigDecimal,
        idempotencyKey: String,
        portfolio: String,
    ): String? {
        if (denyIfNotLiveApproved(ticker)) return null
        requireWsAvailable(portfolio)
        val guid = UuidV7.uuidString()
        val deferred = CompletableDeferred<WsOrderMessages.MatchResult>()
        pending[idempotencyKey] = PendingRequest(guid, isCancel = false, orderId = null, deferred)
        return try {
            val command =
                WsOrderMessages.placeConditional(
                    alorConfig.token,
                    portfolio,
                    ticker,
                    alorConfig.exchange,
                    side,
                    type,
                    qty,
                    stopPrice,
                    idempotencyKey,
                    guid,
                )
            send(command, idempotencyKey, type)
            awaitPlaceOutcome(deferred, idempotencyKey, type)
        } catch (e: OrderTransportException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "WS conditional order send failed for $ticker (type=$type, idem=$idempotencyKey) — UNCERTAIN" }
            meterRegistry.counter("alor.ws.orders.uncertain", Tags.of("type", type, "reason", "SEND_FAILED")).increment()
            throw OrderDeliveryUncertainException("WS conditional order send failed: ${e.message}", e)
        } finally {
            pending.remove(idempotencyKey)
        }
    }

    override suspend fun cancel(
        orderId: String,
        idempotencyKey: String,
        portfolio: String,
    ): CancelResult {
        requireWsAvailable(portfolio)
        val guid = UuidV7.uuidString()
        val deferred = CompletableDeferred<WsOrderMessages.MatchResult>()
        pending[idempotencyKey] = PendingRequest(guid, isCancel = true, orderId = orderId, deferred)
        return try {
            val command =
                WsOrderMessages.cancel(
                    alorConfig.token,
                    portfolio,
                    alorConfig.exchange,
                    orderId,
                    idempotencyKey,
                    guid,
                )
            send(command, idempotencyKey, "cancel")
            awaitCancelOutcome(deferred, idempotencyKey, orderId)
        } catch (e: OrderTransportException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "WS cancel send failed for order $orderId (idem=$idempotencyKey) — UNCERTAIN" }
            meterRegistry.counter("alor.ws.orders.uncertain", Tags.of("type", "cancel", "reason", "SEND_FAILED")).increment()
            throw OrderDeliveryUncertainException("WS cancel send failed: ${e.message}", e)
        } finally {
            pending.remove(idempotencyKey)
        }
    }

    /**
     * Готов ли канал к команде: включён, LIVE, дефолтный портфель, соединение есть.
     * Команда ещё НЕ отправлена — исключение [OrderTransportUnavailableException]
     * сигнализирует маршрутизатору о безопасном переключении на REST.
     */
    private fun requireWsAvailable(portfolio: String) {
        if (!alorConfig.wsOrdersEnabled) throw OrderTransportUnavailableException("WS orders disabled")
        if (!isLive) throw OrderTransportUnavailableException("WS order transport requires LIVE mode")
        if (portfolio != alorConfig.portfolio) {
            throw OrderTransportUnavailableException("WS order transport scoped to default portfolio only")
        }
        if (session.get() == null) throw OrderTransportUnavailableException("WS order channel not connected")
    }

    private suspend fun send(
        command: String,
        idempotencyKey: String,
        type: String,
    ) {
        val conn = session.get() ?: throw OrderTransportUnavailableException("WS order channel not connected")
        conn.send(command)
        meterRegistry.counter("alor.ws.orders.sent", Tags.of("type", type)).increment()
        logger.debug { "WS order command sent (type=$type, idem=$idempotencyKey)" }
    }

    private suspend fun awaitPlaceOutcome(
        deferred: CompletableDeferred<WsOrderMessages.MatchResult>,
        idempotencyKey: String,
        type: String,
    ): String? {
        val result = withTimeoutOrNull(alorConfig.wsOrderTimeoutMs) { deferred.await() }
        return when (result) {
            is WsOrderMessages.MatchResult.Confirmed -> {
                meterRegistry.counter("alor.ws.orders.confirmed", Tags.of("type", type)).increment()
                logger.info { "WS order confirmed (type=$type, idem=$idempotencyKey) -> ${result.orderNumber}" }
                result.orderNumber
            }

            is WsOrderMessages.MatchResult.Rejected -> {
                meterRegistry.counter("alor.ws.orders.rejected", Tags.of("type", type)).increment()
                logger.warn { "WS order REJECTED (type=$type, idem=$idempotencyKey): ${result.reason}" }
                null
            }

            is WsOrderMessages.MatchResult.NotMatch -> {
                throw IllegalStateException("Unreachable: deferred only completes with final outcomes")
            }

            null -> {
                meterRegistry.counter("alor.ws.orders.uncertain", Tags.of("type", type, "reason", "TIMEOUT")).increment()
                throw OrderDeliveryUncertainException(
                    "WS order $type timeout after ${alorConfig.wsOrderTimeoutMs} ms (idem=$idempotencyKey)",
                )
            }
        }
    }

    private suspend fun awaitCancelOutcome(
        deferred: CompletableDeferred<WsOrderMessages.MatchResult>,
        idempotencyKey: String,
        orderId: String,
    ): CancelResult {
        val result = withTimeoutOrNull(alorConfig.wsOrderTimeoutMs) { deferred.await() }
        return when (result) {
            is WsOrderMessages.MatchResult.Confirmed -> {
                meterRegistry.counter("alor.ws.orders.confirmed", Tags.of("type", "cancel")).increment()
                logger.info { "WS cancel confirmed (order=$orderId, idem=$idempotencyKey)" }
                CancelResult.CONFIRMED
            }

            is WsOrderMessages.MatchResult.Rejected -> {
                meterRegistry.counter("alor.ws.orders.rejected", Tags.of("type", "cancel")).increment()
                logger.warn { "WS cancel REJECTED (order=$orderId, idem=$idempotencyKey): ${result.reason}" }
                CancelResult.REJECTED
            }

            is WsOrderMessages.MatchResult.NotMatch -> {
                throw IllegalStateException("Unreachable: deferred only completes with final outcomes")
            }

            null -> {
                meterRegistry.counter("alor.ws.orders.uncertain", Tags.of("type", "cancel", "reason", "TIMEOUT")).increment()
                CancelResult.UNCERTAIN
            }
        }
    }

    private suspend fun runConnectionLoop() {
        var attempt = 0
        while (true) {
            try {
                val conn = socketFactory.open()
                session.set(conn)
                attempt = 0
                meterRegistry.counter("alor.ws.orders.connected").increment()
                logger.info { "WS order channel connected" }
                conn.send(
                    WsOrderMessages.subscribe(
                        alorConfig.token,
                        alorConfig.portfolio,
                        alorConfig.exchange,
                        UuidV7.uuidString(),
                    ),
                )
                conn.messages.collect { text -> onMessage(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "WS order channel connection lost" }
            } finally {
                if (session.getAndSet(null) != null) {
                    meterRegistry.counter("alor.ws.orders.disconnected").increment()
                    failAllPending()
                }
            }
            delay(reconnectDelaySeconds(attempt))
            attempt++
        }
    }

    private fun onMessage(text: String) {
        for ((key, req) in pending.entries) {
            val result =
                if (req.isCancel) {
                    WsOrderMessages.matchCancel(text, req.orderId ?: "", req.guid)
                } else {
                    WsOrderMessages.matchPlace(text, key, req.guid)
                }
            if (result is WsOrderMessages.MatchResult.Confirmed || result is WsOrderMessages.MatchResult.Rejected) {
                req.deferred.complete(result)
                return
            }
        }
    }

    private fun failAllPending() {
        if (pending.isEmpty()) return
        logger.warn { "WS order channel dropped — failing ${pending.size} pending command(s) as UNCERTAIN" }
        for (req in pending.values) {
            req.deferred.completeExceptionally(
                OrderDeliveryUncertainException("WS order connection lost before outcome"),
            )
        }
        pending.clear()
    }

    private fun reconnectDelaySeconds(attempt: Int): Long = minOf(1L shl attempt, 60L)
}
