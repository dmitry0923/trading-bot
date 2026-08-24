package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.model.CloseReason
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.OutboxStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.TradeEventService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.LocalDateTime

/**
 * State Reconciliation: pending entry / close / protection resolution via outbox and REST verification.
 *
 * Responsibilities:
 * - Per-position [reconcilePosition] dispatching;
 * - Entry outbox resolution ([resolveEntryViaOutbox]);
 * - Close outbox resolution ([resolveCloseViaOutbox]);
 * - Entry abandonment ([abandonEntry]).
 *
 * Does NOT own delta-model close fills or SL/TP lifecycle — those live in
 * [CloseFillProcessor] and [ProtectionOrderManager] respectively.
 */
class ExecutionReconciler(
    private val alorClient: AlorClient,
    private val orderOutboxRepo: OrderOutboxRepository,
    private val positionRepo: PositionRepository,
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
    private val tradeEventService: TradeEventService,
    private val meterRegistry: MeterRegistry,
    private val metricPrefix: String,
    private val portfolioResolver: suspend (Long?) -> String,
    private val onEntryOpened: (Position) -> Unit,
    private val isGoneStatus: (AlorClient.OrderExecution) -> Boolean,
    private val isFilledStatus: (AlorClient.OrderExecution) -> Boolean,
    private val attachProtectionOrders: suspend (Position) -> Unit,
    private val confirmCloseFill: suspend (Position, java.math.BigDecimal, CloseReason) -> Unit,
    private val reconcileProtectionOrders: suspend (Position) -> Unit,
    private val onAbandonCleanup: (Long?) -> Unit,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Per-position background State Reconciliation step.
     *
     * Dispatches to the appropriate handler based on position state:
     * - pendingEntry → resolveEntryViaOutbox
     * - pendingClose + closeCancelPending → resolveCloseCancel (verify cancel, then re-arm)
     * - pendingClose + closeOrderId → confirmCloseFill
     * - pendingClose + no closeOrderId → resolveCloseViaOutbox
     * - otherwise → reconcileProtectionOrders
     */
    suspend fun reconcilePosition(pos: Position) {
        when {
            pos.pendingEntry -> {
                resolveEntryViaOutbox(pos)
            }

            pos.pendingClose -> {
                when {
                    pos.closeCancelPending -> resolveCloseCancel(pos)
                    pos.closeOrderId != null -> {
                        confirmCloseFill(pos, pos.currentPrice ?: pos.entryPrice, pos.closeReason ?: CloseReason.RECONCILIATION)
                    }
                    else -> resolveCloseViaOutbox(pos)
                }
            }

            else -> {
                reconcileProtectionOrders(pos)
            }
        }
    }

    /**
     * Resolve pending entry via outbox record.
     *
     * Lifecycle:
     * 1. Find outbox row by position id;
     * 2. If SENT + has orderId → verify via REST;
     * 3. If gone → abandon;
     * 4. If no fill → wait;
     * 5. If partial fill → wait until cancel threshold, then cancel remainder and finalize;
     * 6. If full fill → finalize;
     * 7. If FAILED + retries exhausted → abandon.
     */
    suspend fun resolveEntryViaOutbox(pos: Position) {
        val positionId =
            pos.id ?: run {
                logger.error { "Pending entry ${pos.ticker} has no id — cannot reconcile via outbox" }
                return
            }
        val outbox = orderOutboxRepo.findLatestByPositionId(positionId)
        if (outbox == null) {
            logger.warn { "No outbox row for pending entry ${pos.id}/${pos.ticker}; leaving pending" }
            return
        }
        when {
            outbox.status == OutboxStatus.SENT && outbox.alorOrderId != null -> {
                val execution = alorClient.verifyOrder(outbox.alorOrderId, portfolio = portfolioResolver(pos.accountId)) ?: return
                if (isGoneStatus(execution)) {
                    abandonEntry(pos, CloseReason.ENTRY_REJECTED)
                    return
                }
                if (execution.filledQuantity <= 0) return

                val requestedQty =
                    objectMapper
                        .readTree(outbox.payloadJson)
                        .path("qty")
                        .asInt(0)
                        .takeIf { it > 0 }
                        ?: pos.quantity
                val cumulative = execution.filledQuantity.coerceIn(1, requestedQty)
                val remainder = requestedQty - cumulative

                if (remainder > 0) {
                    if (cumulative != pos.quantity) {
                        pos.quantity = cumulative
                        pos.entryPrice = execution.avgPrice ?: pos.entryPrice
                        positionRepo.save(pos)
                    }
                    val elapsedMs = Duration.between(outbox.createdAt, LocalDateTime.now()).toMillis()
                    if (elapsedMs < alorConfig.entryPartialFillCancelAfterMs) {
                        logger.info {
                            "Pending entry ${pos.ticker}: partial fill ${pos.quantity}/$requestedQty, " +
                                "remainder $remainder still resting " +
                                "(${elapsedMs}ms < ${alorConfig.entryPartialFillCancelAfterMs}ms)"
                        }
                        return
                    }
                    val cancelled =
                        try {
                            alorClient.cancelOrder(
                                outbox.alorOrderId,
                                outbox.idempotencyKey ?: "",
                                portfolio = portfolioResolver(pos.accountId),
                            )
                        } catch (e: Exception) {
                            logger.error(e) {
                                "Entry remainder cancel FAILED for ${pos.ticker} " +
                                    "(order=${outbox.alorOrderId}) — retry next cycle"
                            }
                            AlorClient.CancelResult.UNCERTAIN
                        }
                    if (cancelled != AlorClient.CancelResult.CONFIRMED) return
                    val finalExec = alorClient.verifyOrder(outbox.alorOrderId, portfolio = portfolioResolver(pos.accountId))
                    val finalQty = (finalExec?.filledQuantity ?: cumulative).coerceIn(1, requestedQty)
                    pos.alorOrderId = outbox.alorOrderId
                    pos.pendingEntry = false
                    pos.entryPrice = finalExec?.avgPrice ?: execution.avgPrice ?: pos.entryPrice
                    pos.quantity = finalQty
                    positionRepo.save(pos)
                    tradeEventService.recordPositionOpened(pos)
                    onEntryOpened(pos)
                    attachProtectionOrders(pos)
                    meterRegistry.counter("$metricPrefix.entry.remainder_cancelled", Tags.of("ticker", pos.ticker)).increment()
                    logger.info {
                        "Pending entry ${pos.ticker} finalized after remainder cancel: " +
                            "qty=${pos.quantity} @ ${pos.entryPrice} (order=${outbox.alorOrderId})"
                    }
                    return
                }

                pos.alorOrderId = outbox.alorOrderId
                pos.pendingEntry = false
                pos.entryPrice = execution.avgPrice ?: pos.entryPrice
                pos.quantity = cumulative
                positionRepo.save(pos)
                tradeEventService.recordPositionOpened(pos)
                onEntryOpened(pos)
                attachProtectionOrders(pos)
                logger.info {
                    "Pending entry resolved ${pos.ticker}: order=${outbox.alorOrderId} qty=${pos.quantity} @ ${pos.entryPrice}"
                }
            }

            outbox.status == OutboxStatus.FAILED && outbox.retryCount >= alorConfig.maxOrderRetries -> {
                abandonEntry(pos, CloseReason.ENTRY_NOT_CONFIRMED)
            }

            else -> {}
        }
    }

    /**
     * Resolve pending close with closeCancelPending=true.
     *
     * Verifies the old close order via REST. If confirmed terminal (cancelled/rejected/expired),
     * applies any remaining fills via delta model, then clears close state. The monitor will
     * re-trigger a fresh close on the next tick if the position still needs closing.
     *
     * This prevents the over-close race: old order A is still live on the exchange and could
     * fill after we cancel it. Only after A is confirmed gone do we clear state and allow a
     * new close order to be created.
     */
    suspend fun resolveCloseCancel(pos: Position) {
        val orderId = pos.closeOrderId
        val positionId = pos.id
        if (orderId == null || positionId == null) {
            pos.closeCancelPending = false
            pos.pendingClose = false
            positionRepo.save(pos)
            return
        }

        val execution = alorClient.verifyOrder(
            orderId,
            expectedPrice = pos.currentPrice ?: pos.entryPrice,
            portfolio = portfolioResolver(pos.accountId),
        ) ?: return

        if (isGoneStatus(execution)) {
            // Old order confirmed terminal — apply any remaining fills via delta model
            val prevApplied = pos.cumulativeCloseFillQty
            val delta = execution.filledQuantity - prevApplied
            if (delta > 0) {
                confirmCloseFill(pos, pos.currentPrice ?: pos.entryPrice, pos.closeReason ?: CloseReason.RECONCILIATION)
            }

            // Re-read: confirmCloseFill may have transitioned to CLOSED via finalizeClosePosition.
            // Saving a stale OPEN entity would reopen the position.
            val fresh = positionRepo.findById(positionId)
            if (fresh.status != PositionStatus.OPEN) {
                meterRegistry.counter("$metricPrefix.close.cancel_confirmed", Tags.of("ticker", fresh.ticker)).increment()
                logger.info {
                    "Close cancel confirmed for ${fresh.ticker} (order=$orderId, status=${execution.status}) — " +
                        "position already ${fresh.status}, skipping stale state clear"
                }
                return
            }

            // Position still OPEN — safe to clear old close state
            fresh.closeOrderId = null
            fresh.cumulativeCloseFillQty = 0
            fresh.closeReason = null
            fresh.closeCancelPending = false
            fresh.pendingClose = false
            positionRepo.save(fresh)
            meterRegistry.counter("$metricPrefix.close.cancel_confirmed", Tags.of("ticker", fresh.ticker)).increment()
            logger.info {
                "Close cancel confirmed for ${fresh.ticker} (order=$orderId, status=${execution.status}) — " +
                    "old state cleared, monitor will re-arm if position still needs closing"
            }
            return
        }

        // Order is still live or filled — wait for cancel to complete
        if (isFilledStatus(execution)) {
            logger.warn {
                "Close order $orderId for ${pos.ticker} still FILLED/PARTIALLY_FILLED " +
                    "(status=${execution.status}, filled=${execution.filledQuantity}) — waiting for cancel"
            }
        }
    }

    /**
     * Resolve pending close without closeOrderId via outbox record.
     */
    suspend fun resolveCloseViaOutbox(pos: Position) {
        val positionId =
            pos.id ?: run {
                logger.error { "Pending close ${pos.ticker} has no id — cannot reconcile via outbox" }
                return
            }
        val outbox = orderOutboxRepo.findLatestByPositionId(positionId, purpose = "close")
        if (outbox == null) {
            logger.warn { "No outbox row for pending close ${pos.id}/${pos.ticker}; resetting pendingClose" }
            pos.pendingClose = false
            positionRepo.save(pos)
            return
        }
        when {
            outbox.status == OutboxStatus.SENT && outbox.alorOrderId != null -> {
                pos.closeOrderId = outbox.alorOrderId
                positionRepo.save(pos)
                confirmCloseFill(pos, pos.currentPrice ?: pos.entryPrice, pos.closeReason ?: CloseReason.RECONCILIATION)
            }

            outbox.status == OutboxStatus.FAILED && outbox.retryCount >= alorConfig.maxOrderRetries -> {
                logger.warn { "Pending close ${pos.id}/${pos.ticker} permanently failed; resetting for a fresh close order" }
                pos.pendingClose = false
                pos.closeOrderId = null
                positionRepo.save(pos)
            }

            else -> {}
        }
    }

    internal suspend fun abandonEntry(
        pos: Position,
        reason: CloseReason,
    ) {
        logger.warn { "Entry for ${pos.ticker} abandoned: $reason" }
        pos.pendingEntry = false
        pos.status = PositionStatus.CLOSED
        pos.closeReason = reason
        pos.closedAt = LocalDateTime.now()
        positionRepo.save(pos)
        positionRepo.releaseEntry(pos.ticker, pos.accountId)
        onAbandonCleanup(pos.id)
        meterRegistry.counter("$metricPrefix.entry.abandoned", Tags.of("ticker", pos.ticker, "reason", reason.code)).increment()
    }
}
