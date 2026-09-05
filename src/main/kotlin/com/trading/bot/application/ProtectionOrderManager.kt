package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.domain.risk.ExitRules
import com.trading.bot.model.CloseReason
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.OutboxStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.OrderOutboxService
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Exchange protection order lifecycle (SL/TP): place, reconcile, replace, cancel.
 *
 * - [attachProtectionOrders] — place missing orders at current levels;
 * - [onProtectionLevelsChanged] — level changed (trailing/strategy) → schedule replace;
 * - [reconcileProtectionOrders] — background: propagate IDs from outbox, detect
 *   execution/cancellation, finish replacements, place missing orders;
 * - [cancelProtectionOrders] — cancel on position close.
 *
 * Levels: SL = [ExitRules.effectiveSl] (hard stop or trailing if stricter), TP = takeProfit.
 * While a pending replace is active ([Position.slPendingReplace]/[Position.tpPendingReplace]),
 * NO new order is placed (protection against duplicate stop/take).
 */
class ProtectionOrderManager(
    private val alorClient: AlorClient,
    private val orderOutboxService: OrderOutboxService,
    private val orderOutboxRepo: OrderOutboxRepository,
    private val positionRepo: PositionRepository,
    private val alorConfig: AlorConfig,
    private val portfolioResolver: suspend (Long?) -> String,
    private val onSlProtectionFailed: (Position) -> Unit,
    private val protectionOrdersEnabled: Boolean = false,
    /**
     * Callback to apply SL/TP execution fill via [CloseFillProcessor].
     * Breaks circular dependency: ProtectionOrderManager → CloseFillProcessor.
     */
    private val applyCloseExecution: suspend (Position, Int, java.math.BigDecimal, CloseReason) -> Unit,
) {
    private val logger = KotlinLogging.logger {}

    private fun protectionCloseSide(pos: Position): String =
        when (pos.direction) {
            PositionDirection.LONG -> "sell"
            PositionDirection.SHORT -> "buy"
        }

    /**
     * Place missing exchange SL/TP orders (idempotent). Called on entry confirmation
     * and from [reconcileProtectionOrders].
     */
    suspend fun attachProtectionOrders(pos: Position) {
        if (!protectionOrdersEnabled) return
        if (pos.status != PositionStatus.OPEN) return
        if (pos.closeCancelPending) {
            logger.info {
                "Protection re-arm skipped for ${pos.ticker}: close cancellation pending"
            }
            return
        }
        val positionId =
            pos.id ?: run {
                logger.error { "Cannot attach protection orders for ${pos.ticker}: position has no id" }
                return
            }
        var dirty = false

        val effSl = ExitRules.effectiveSl(pos)
        if (effSl != null && pos.slOrderId == null && !pos.slPendingReplace && !pos.slCancelPending &&
            !protectionOutboxActive(positionId, "sl")
        ) {
            val placed =
                orderOutboxService.placeOrder(
                    pos.ticker,
                    protectionCloseSide(pos),
                    pos.quantity,
                    null,
                    "stop",
                    positionId = positionId,
                    stopPrice = effSl,
                    purpose = "sl",
                )
            if (placed.alorOrderId != null) {
                pos.slOrderId = placed.alorOrderId
                pos.slOrderPrice = effSl
                // New protection order — fill counter starts from zero
                pos.cumulativeSlFillQty = 0
                dirty = true
                logger.info { "Exchange SL placed ${pos.ticker} @ $effSl qty=${pos.quantity} -> ${placed.alorOrderId}" }
            } else {
                logger.warn { "Exchange SL for ${pos.ticker} @ $effSl not confirmed (uncertain/rejected) — reconcile will resolve" }
            }
        }

        val tp = pos.takeProfit
        if (tp != null && pos.tpOrderId == null && !pos.tpPendingReplace && !pos.tpCancelPending &&
            !protectionOutboxActive(positionId, "tp")
        ) {
            val placed =
                orderOutboxService.placeOrder(
                    pos.ticker,
                    protectionCloseSide(pos),
                    pos.quantity,
                    null,
                    "take-profit",
                    positionId = positionId,
                    stopPrice = tp,
                    purpose = "tp",
                )
            if (placed.alorOrderId != null) {
                pos.tpOrderId = placed.alorOrderId
                pos.tpOrderPrice = tp
                // New protection order — fill counter starts from zero
                pos.cumulativeTpFillQty = 0
                dirty = true
                logger.info { "Exchange TP placed ${pos.ticker} @ $tp qty=${pos.quantity} -> ${placed.alorOrderId}" }
            } else {
                logger.warn { "Exchange TP for ${pos.ticker} @ $tp not confirmed (uncertain/rejected) — reconcile will resolve" }
            }
        }

        if (dirty) positionRepo.save(pos)
    }

    /**
     * Level change (trailing/strategy): schedule replacement via PendingReplace flags.
     */
    suspend fun onProtectionLevelsChanged(pos: Position) {
        if (pos.status != PositionStatus.OPEN) return
        var dirty = false

        val effSl = ExitRules.effectiveSl(pos)
        if (effSl != null && pos.slOrderId != null && pos.slOrderPrice != null &&
            effSl.compareTo(pos.slOrderPrice) != 0 && !pos.slPendingReplace && !pos.slCancelPending
        ) {
            pos.slPendingReplace = true
            dirty = true
            logger.info { "SL level changed ${pos.ticker}: ${pos.slOrderPrice} -> $effSl (replace scheduled)" }
        }

        val tp = pos.takeProfit
        if (tp != null && pos.tpOrderId != null && pos.tpOrderPrice != null &&
            tp.compareTo(pos.tpOrderPrice) != 0 && !pos.tpPendingReplace && !pos.tpCancelPending
        ) {
            pos.tpPendingReplace = true
            dirty = true
            logger.info { "TP level changed ${pos.ticker}: ${pos.tpOrderPrice} -> $tp (replace scheduled)" }
        }

        if (dirty) positionRepo.save(pos)
        attachProtectionOrders(pos)
    }

    /**
     * Background maintenance of exchange protection orders: outbox ID propagation,
     * execution/cancellation detection, replacement completion, missing order placement.
     */
    suspend fun reconcileProtectionOrders(pos: Position) {
        if (pos.status != PositionStatus.OPEN) {
            if (pos.slOrderId != null || pos.tpOrderId != null) {
                logger.warn { "Orphan SL/TP detected for ${pos.ticker} (${pos.status}); cancelling via outbox" }
                cancelProtectionOrders(pos)
                positionRepo.save(pos)
            }
            return
        }
        resolveProtectionOutbox(pos)
        if (pos.status != PositionStatus.OPEN) return
        checkProtectionFills(pos)
        if (pos.status != PositionStatus.OPEN) return
        finishProtectionReplacement(pos)
        attachProtectionOrders(pos)
    }

    /**
     * Cancel exchange protection orders via outbox (guaranteed delivery).
     * Sets [Position.slCancelPending] / [Position.tpCancelPending] instead of
     * clearing orderId — the ID is only cleared after confirmed cancellation or
     * detected "gone" status, preventing duplicate protection orders.
     *
     * [skip] — type to skip (e.g., the fired order).
     */
    suspend fun cancelProtectionOrders(
        pos: Position,
        skip: String? = null,
    ) {
        if (!protectionOrdersEnabled) return
        val positionId = pos.id ?: return
        val slId = pos.slOrderId
        if (slId != null && skip != "SL" && !pos.slCancelPending) {
            orderOutboxService.placeCancelOrder(positionId, slId, accountId = pos.accountId)
            logger.info { "Exchange SL cancel scheduled for ${pos.ticker} (order=$slId)" }
            pos.slCancelPending = true
            pos.slPendingReplace = false
            // NOTE: cumulativeSlFillQty is NOT reset here — the old order is still live on
            // the exchange and could produce late WS fills. Reset only after the order is
            // confirmed terminal (checkProtectionFills → isGoneStatus → cumulativeSlFillQty=0).
        }
        val tpId = pos.tpOrderId
        if (tpId != null && skip != "TP" && !pos.tpCancelPending) {
            orderOutboxService.placeCancelOrder(positionId, tpId, accountId = pos.accountId)
            logger.info { "Exchange TP cancel scheduled for ${pos.ticker} (order=$tpId)" }
            pos.tpCancelPending = true
            pos.tpPendingReplace = false
            // NOTE: cumulativeTpFillQty is NOT reset here — same rationale as SL above.
        }
    }

    // ─── Internal ────────────────────────────────────────────────────────

    private suspend fun resolveProtectionOutbox(pos: Position) {
        val positionId = pos.id ?: return
        var dirty = false
        if (pos.slOrderId == null) {
            orderOutboxRepo.findLatestByPositionId(positionId, "sl")?.let { row ->
                if (row.status == OutboxStatus.SENT && row.alorOrderId != null) {
                    val cancelled = orderOutboxRepo.findLatestConfirmedCancel(positionId, row.alorOrderId)
                    if (cancelled == null) {
                        pos.slOrderId = row.alorOrderId
                        pos.slOrderPrice = ExitRules.effectiveSl(pos)
                        dirty = true
                        logger.info { "Exchange SL id resolved ${pos.ticker} -> ${row.alorOrderId}" }
                    } else {
                        logger.info { "Exchange SL ${row.alorOrderId} for ${pos.ticker} was cancelled — not re-arming" }
                    }
                } else if (row.status == OutboxStatus.FAILED && row.retryCount >= alorConfig.maxOrderRetries) {
                    logger.warn {
                        "Exchange SL for ${pos.ticker} permanently failed (${row.errorMessage}); triggering SL protection failure"
                    }
                    onSlProtectionFailed(pos)
                } else if (row.status == OutboxStatus.BLOCKED) {
                    logger.warn {
                        "Exchange SL for ${pos.ticker} BLOCKED (${row.errorMessage}); triggering SL protection failure"
                    }
                    onSlProtectionFailed(pos)
                } else if (row.status == OutboxStatus.REJECTED) {
                    logger.warn {
                        "Exchange SL for ${pos.ticker} REJECTED by exchange (${row.errorMessage}); triggering SL protection failure"
                    }
                    onSlProtectionFailed(pos)
                }
            }
        }
        if (pos.tpOrderId == null) {
            orderOutboxRepo.findLatestByPositionId(positionId, "tp")?.let { row ->
                if (row.status == OutboxStatus.SENT && row.alorOrderId != null) {
                    val cancelled = orderOutboxRepo.findLatestConfirmedCancel(positionId, row.alorOrderId)
                    if (cancelled == null) {
                        pos.tpOrderId = row.alorOrderId
                        pos.tpOrderPrice = pos.takeProfit
                        dirty = true
                        logger.info { "Exchange TP id resolved ${pos.ticker} -> ${row.alorOrderId}" }
                    } else {
                        logger.info { "Exchange TP ${row.alorOrderId} for ${pos.ticker} was cancelled — not re-arming" }
                    }
                } else if (row.status == OutboxStatus.FAILED && row.retryCount >= alorConfig.maxOrderRetries) {
                    logger.warn {
                        "Exchange TP for ${pos.ticker} permanently failed (${row.errorMessage}); triggering SL protection failure"
                    }
                    onSlProtectionFailed(pos)
                } else if (row.status == OutboxStatus.BLOCKED) {
                    logger.warn {
                        "Exchange TP for ${pos.ticker} BLOCKED (${row.errorMessage}); triggering SL protection failure"
                    }
                    onSlProtectionFailed(pos)
                } else if (row.status == OutboxStatus.REJECTED) {
                    logger.warn {
                        "Exchange TP for ${pos.ticker} REJECTED by exchange (${row.errorMessage}); triggering SL protection failure"
                    }
                    onSlProtectionFailed(pos)
                }
            }
        }
        if (dirty) positionRepo.save(pos)
    }

    private suspend fun checkProtectionFills(pos: Position) {
        pos.slOrderId?.let { id ->
            val ex = alorClient.verifyOrder(id, portfolio = portfolioResolver(pos.accountId)) ?: return@let
            if (isFilledStatus(ex)) {
                applyExchangeProtectionClose(pos, ex, CloseReason.STOP_LOSS)
                return
            }
            if (isGoneStatus(ex)) {
                pos.slOrderId = null
                pos.slOrderPrice = null
                pos.slPendingReplace = false
                pos.slCancelPending = false
                // Old order replaced — reset its fill counter
                pos.cumulativeSlFillQty = 0
                logger.warn { "Exchange SL order $id for ${pos.ticker} gone (${ex.status}); will re-place" }
            }
        }
        pos.tpOrderId?.let { id ->
            if (pos.status != PositionStatus.OPEN) return@let
            val ex = alorClient.verifyOrder(id, portfolio = portfolioResolver(pos.accountId)) ?: return@let
            if (isFilledStatus(ex)) {
                applyExchangeProtectionClose(pos, ex, CloseReason.TAKE_PROFIT)
                return
            }
            if (isGoneStatus(ex)) {
                pos.tpOrderId = null
                pos.tpOrderPrice = null
                pos.tpPendingReplace = false
                pos.tpCancelPending = false
                // Old order replaced — reset its fill counter
                pos.cumulativeTpFillQty = 0
                logger.warn { "Exchange TP order $id for ${pos.ticker} gone (${ex.status}); will re-place" }
            }
        }
        positionRepo.save(pos)
    }

    /**
     * Finalize close by exchange protection order execution. Public for WS SL/TP dispatch.
     */
    suspend fun applyExchangeProtectionClosePublic(
        pos: Position,
        execution: AlorClient.OrderExecution,
        reason: CloseReason,
    ) = applyExchangeProtectionClose(pos, execution, reason)

    /**
     * Finalize close by exchange protection order execution. Clears fired order IDs
     * atomically, cancels the pending close order and the other protection order,
     * applies fill, and re-arms protection for the remainder.
     */
    private suspend fun applyExchangeProtectionClose(
        pos: Position,
        execution: AlorClient.OrderExecution,
        reason: CloseReason,
    ) {
        // Delta model: only apply the increment since last protection fill for this order.
        // filledQuantity is cumulative from the exchange — without delta, a second partial
        // fill report would re-close the position.
        val prevFill =
            when (reason) {
                CloseReason.STOP_LOSS -> pos.cumulativeSlFillQty
                CloseReason.TAKE_PROFIT -> pos.cumulativeTpFillQty
                else -> 0
            }
        val delta = execution.filledQuantity - prevFill
        if (delta <= 0) {
            // Duplicate or out-of-order event — skip
            return
        }
        val filled = delta.coerceAtMost(pos.quantity)

        // Track cumulative fill for delta model
        when (reason) {
            CloseReason.STOP_LOSS -> {
                pos.cumulativeSlFillQty = execution.filledQuantity
            }

            CloseReason.TAKE_PROFIT -> {
                pos.cumulativeTpFillQty = execution.filledQuantity
            }

            else -> {}
        }

        when (reason) {
            CloseReason.STOP_LOSS -> {
                pos.slOrderId = null
                pos.slOrderPrice = null
                pos.slPendingReplace = false
                pos.slCancelPending = false
            }

            CloseReason.TAKE_PROFIT -> {
                pos.tpOrderId = null
                pos.tpOrderPrice = null
                pos.tpPendingReplace = false
                pos.tpCancelPending = false
            }

            else -> {}
        }

        if (pos.pendingClose && !pos.closeCancelPending) {
            val closeId = pos.closeOrderId
            val positionId = pos.id
            if (closeId != null && positionId != null) {
                orderOutboxService.placeCancelOrder(positionId, closeId, accountId = pos.accountId)
                pos.closeCancelPending = true
                logger.info {
                    "Protection $reason fired for ${pos.ticker} — cancelling pending close order $closeId, " +
                        "waiting for cancel confirmation before allowing fresh close"
                }
            }
            // DO NOT clear closeOrderId / pendingClose / closeReason here.
            // They are preserved so the delta model (handlePendingCloseReport) can still
            // apply late WS fills for the old close order. State is cleared by the reconciler
            // (resolveCloseCancel) after the old order is confirmed terminal.
        }
        cancelProtectionOrders(pos)
        applyCloseExecution(pos, filled, execution.avgPrice ?: pos.currentPrice ?: pos.entryPrice, reason)
        if (pos.status == PositionStatus.OPEN && !pos.closeCancelPending) {
            attachProtectionOrders(pos)
        }
    }

    private suspend fun finishProtectionReplacement(pos: Position) {
        var dirty = false

        if (pos.slPendingReplace) {
            val oldId = pos.slOrderId
            if (oldId == null) {
                pos.slPendingReplace = false
                dirty = true
            } else {
                val cancelIdem = "prot-cancel-$oldId"
                val result =
                    try {
                        alorClient.cancelOrder(oldId, cancelIdem, portfolio = portfolioResolver(pos.accountId))
                    } catch (e: Exception) {
                        logger.warn(e) {
                            "SL replacement cancel UNKNOWN for ${pos.ticker} (order=$oldId idem=$cancelIdem) — retry next cycle"
                        }
                        return
                    }
                if (result == AlorClient.CancelResult.REJECTED) {
                    val ex = alorClient.verifyOrder(oldId, portfolio = portfolioResolver(pos.accountId))
                    when {
                        ex != null && isFilledStatus(ex) -> {
                            applyExchangeProtectionClose(pos, ex, CloseReason.STOP_LOSS)
                            return
                        }

                        ex != null && isGoneStatus(ex) -> {
                            logger.info { "SL replaced: old order $oldId confirmed gone (${ex.status}) for ${pos.ticker}" }
                        }

                        else -> {
                            val status = ex?.status ?: "null"
                            logger.warn {
                                "SL cancel rejected but order state UNKNOWN ($status) for ${pos.ticker}, order=$oldId — retry next cycle"
                            }
                            return
                        }
                    }
                }
                if (result == AlorClient.CancelResult.UNCERTAIN) return
                pos.slOrderId = null
                pos.slOrderPrice = null
                pos.slPendingReplace = false
                pos.slCancelPending = false
                // Old SL replaced — reset its fill counter for the new order
                pos.cumulativeSlFillQty = 0
                dirty = true
                if (result == AlorClient.CancelResult.CONFIRMED) {
                    logger.info { "SL replacement confirmed for ${pos.ticker} (old order $oldId cancelled)" }
                }
            }
        }

        if (pos.tpPendingReplace) {
            val oldId = pos.tpOrderId
            if (oldId == null) {
                pos.tpPendingReplace = false
                dirty = true
            } else {
                val cancelIdem = "prot-cancel-$oldId"
                val result =
                    try {
                        alorClient.cancelOrder(oldId, cancelIdem, portfolio = portfolioResolver(pos.accountId))
                    } catch (e: Exception) {
                        logger.warn(e) {
                            "TP replacement cancel UNKNOWN for ${pos.ticker} (order=$oldId idem=$cancelIdem) — retry next cycle"
                        }
                        return
                    }
                if (result == AlorClient.CancelResult.REJECTED) {
                    val ex = alorClient.verifyOrder(oldId, portfolio = portfolioResolver(pos.accountId))
                    when {
                        ex != null && isFilledStatus(ex) -> {
                            applyExchangeProtectionClose(pos, ex, CloseReason.TAKE_PROFIT)
                            return
                        }

                        ex != null && isGoneStatus(ex) -> {
                            logger.info { "TP replaced: old order $oldId confirmed gone (${ex.status}) for ${pos.ticker}" }
                        }

                        else -> {
                            val status = ex?.status ?: "null"
                            logger.warn {
                                "TP cancel rejected but order state UNKNOWN ($status) for ${pos.ticker}, order=$oldId — retry next cycle"
                            }
                            return
                        }
                    }
                }
                if (result == AlorClient.CancelResult.UNCERTAIN) return
                pos.tpOrderId = null
                pos.tpOrderPrice = null
                pos.tpPendingReplace = false
                pos.tpCancelPending = false
                // Old TP replaced — reset its fill counter for the new order
                pos.cumulativeTpFillQty = 0
                dirty = true
                if (result == AlorClient.CancelResult.CONFIRMED) {
                    logger.info { "TP replacement confirmed for ${pos.ticker} (old order $oldId cancelled)" }
                }
            }
        }

        if (dirty) positionRepo.save(pos)
    }

    private suspend fun protectionOutboxActive(
        positionId: Long,
        purpose: String,
    ): Boolean {
        val row = orderOutboxRepo.findLatestByPositionId(positionId, purpose) ?: return false
        if (row.status == OutboxStatus.PENDING) return true
        if (row.status == OutboxStatus.FAILED && row.retryCount < alorConfig.maxOrderRetries) return true
        if (row.status == OutboxStatus.SENT && row.alorOrderId != null) {
            return orderOutboxRepo.findLatestConfirmedCancel(positionId, row.alorOrderId) == null
        }
        return false
    }

    private fun isFilledStatus(execution: AlorClient.OrderExecution): Boolean =
        when (execution.status.uppercase()) {
            "FILLED",
            "PARTIALLY_FILLED",
            -> execution.filledQuantity > 0

            else -> false
        }

    private fun isGoneStatus(execution: AlorClient.OrderExecution): Boolean =
        when (execution.status.uppercase()) {
            "CANCELED",
            "CANCELLED",
            "REJECTED",
            "EXPIRED",
            -> true

            else -> false
        }
}
