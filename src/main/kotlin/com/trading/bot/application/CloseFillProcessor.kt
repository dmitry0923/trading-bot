package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.infrastructure.tracing.TraceContext
import com.trading.bot.model.CloseReason
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.dto.ExecutionReport
import com.trading.bot.model.dto.OrderStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.TradeEventService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Delta-model close fill processor: applies WS ExecutionReports and REST
 * verifyOrder results to close positions using the cumulative delta model.
 *
 * Invariants:
 * - delta = cumulativeFilledQty - cumulativeCloseFillQty
 * - delta <= 0 → skip (duplicate or out-of-order event)
 * - delta > 0 && delta < quantity → partial close (P&L for closed part)
 * - delta >= quantity → full finalize (status=CLOSED/TAKE_PROFIT)
 *
 * Thread safety: per-position [Mutex] via [closeFillMutexes] serializes
 * concurrent WS + REST close fills for the same position.
 *
 * Cross-cutting callbacks:
 * - [onPositionClosed] — side effect on finalize (e.g., record event, free entry slot)
 * - [cancelProtectionOrders] — cancel exchange SL/TP when finalize removes the position
 * - [attachProtectionOrders] — re-arm SL/TP for the remaining position after partial close
 */
class CloseFillProcessor(
    private val positionRepo: PositionRepository,
    private val alorClient: AlorClient,
    private val pnlCalculator: PnlCalculator,
    private val tradeEventService: TradeEventService,
    private val meterRegistry: MeterRegistry,
    private val metricPrefix: String,
    private val portfolioResolver: suspend (Long?) -> String,
    private val onPositionClosed: (Position) -> Unit,
    private val cancelProtectionOrders: suspend (Position) -> Unit,
    private val attachProtectionOrders: suspend (Position) -> Unit,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Per-position mutex: serializes close-fill processing (WS handleExecutionReport
     * vs REST confirmCloseFill) for the same position.
     */
    val closeFillMutexes = ConcurrentHashMap<Long, Mutex>()

    /**
     * Delta-model close fill for fallback-path (handleRegularStockFill).
     *
     * Used when handleExecutionReport() returned false (pendingClose=false,
     * but closeOrderId is set — e.g., after releaseCloseClaim).
     */
    suspend fun handleCloseFill(
        pos: Position,
        report: ExecutionReport,
    ) {
        val fillPrice = report.avgPrice ?: return
        val positionId = pos.id ?: return
        val mutex = closeFillMutexes.getOrPut(positionId) { Mutex() }
        mutex.withLock {
            val fresh = positionRepo.findById(positionId)
            if (fresh.status != PositionStatus.OPEN) return
            if (fresh.closeOrderId == null) return
            if (fresh.closeOrderId != report.orderId) {
                logger.warn {
                    "Ignoring stale close fill: " +
                        "position=${fresh.id}, ticker=${fresh.ticker}, " +
                        "currentOrder=${fresh.closeOrderId}, reportOrder=${report.orderId}"
                }
                meterRegistry.counter("$metricPrefix.close.stale_order", Tags.of("ticker", fresh.ticker)).increment()
                return
            }
            val prevApplied = fresh.cumulativeCloseFillQty
            val delta = report.cumulativeFilledQty - prevApplied
            if (delta <= 0) {
                logger.debug {
                    "handleCloseFill delta=0 for ${fresh.ticker}: cumulative=${report.cumulativeFilledQty} " +
                        "already_applied=$prevApplied — skipping"
                }
                return
            }
            if (delta > fresh.quantity) {
                logger.error {
                    "Impossible close delta: delta=$delta > positionQuantity=${fresh.quantity}, " +
                        "positionId=${fresh.id}, ticker=${fresh.ticker}"
                }
                meterRegistry.counter("$metricPrefix.close.impossible_delta", Tags.of("ticker", fresh.ticker)).increment()
                handleImpossibleClose(fresh)
                return
            }
            fresh.cumulativeCloseFillQty = report.cumulativeFilledQty
            applyCloseExecution(fresh, delta, fillPrice, fresh.closeReason ?: CloseReason.EXECUTION_FILL)
        }
    }

    /**
     * REST confirmCloseFill: verifyOrder + delta model.
     *
     * Mutex-protected against concurrent WS handleExecutionReport.
     */
    suspend fun confirmCloseFill(
        pos: Position,
        expectedPrice: BigDecimal,
        reason: CloseReason,
    ) {
        val positionId = pos.id ?: return
        val mutex = closeFillMutexes.getOrPut(positionId) { Mutex() }
        mutex.withLock {
            val fresh = positionRepo.findById(positionId)
            if (fresh.status != PositionStatus.OPEN) return

            val orderId = fresh.closeOrderId ?: return
            val execution =
                alorClient.verifyOrder(
                    orderId,
                    expectedPrice = expectedPrice,
                    portfolio = portfolioResolver(fresh.accountId),
                )
            if (execution == null) {
                if (closeConfirmedByPositionDelta(fresh)) {
                    logger.warn {
                        "Close order $orderId for ${fresh.ticker} confirmed by position delta " +
                            "(exchange position reduced) — finalizing at $expectedPrice"
                    }
                    applyCloseExecution(fresh, fresh.quantity, expectedPrice, reason)
                } else {
                    logger.warn { "Close order $orderId for ${fresh.ticker} state UNKNOWN; pending reconciliation" }
                }
                return
            }
            val avg = execution.avgPrice ?: expectedPrice
            val cumulativeFill = execution.filledQuantity
            val prevApplied = fresh.cumulativeCloseFillQty
            val delta = cumulativeFill - prevApplied
            if (delta <= 0) {
                logger.debug {
                    "confirmCloseFill delta=0 for ${fresh.ticker}: REST cumulative=$cumulativeFill " +
                        "already_applied=$prevApplied — skipping"
                }
                return
            }
            if (delta > fresh.quantity) {
                logger.error {
                    "Impossible close delta: delta=$delta > positionQuantity=${fresh.quantity}, " +
                        "positionId=${fresh.id}, ticker=${fresh.ticker}"
                }
                meterRegistry.counter("$metricPrefix.close.impossible_delta", Tags.of("ticker", fresh.ticker)).increment()
                handleImpossibleClose(fresh)
                return
            }
            fresh.cumulativeCloseFillQty = cumulativeFill
            applyCloseExecution(fresh, delta, avg, reason)
        }
    }

    /**
     * WS ExecutionReport for pendingClose — delta model.
     *
     * @return true if the report was handled (pendingClose=true);
     *   false if the report doesn't match pending state.
     */
    suspend fun handlePendingCloseReport(
        pos: Position,
        report: ExecutionReport,
    ): Boolean {
        if (pos.pendingClose) {
            val positionId = pos.id ?: return false
            val mutex = closeFillMutexes.getOrPut(positionId) { Mutex() }
            mutex.withLock {
                val fresh = positionRepo.findById(positionId)
                if (fresh.status != PositionStatus.OPEN) return true
                if (fresh.closeOrderId != null && fresh.closeOrderId != report.orderId) {
                    logger.warn {
                        "Ignoring stale pending-close fill: " +
                            "position=${fresh.id}, ticker=${fresh.ticker}, " +
                            "currentOrder=${fresh.closeOrderId}, reportOrder=${report.orderId}"
                    }
                    meterRegistry.counter("$metricPrefix.close.stale_order", Tags.of("ticker", fresh.ticker)).increment()
                    return true
                }
                val prevApplied = fresh.cumulativeCloseFillQty
                val delta = report.cumulativeFilledQty - prevApplied
                if (delta <= 0) {
                    logger.debug {
                        "Close fill delta=0 for ${fresh.ticker}: cumulative=${report.cumulativeFilledQty} " +
                            "already_applied=$prevApplied — skipping"
                    }
                    return true
                }
                if (delta > fresh.quantity) {
                    logger.error {
                        "Impossible close delta: delta=$delta > positionQuantity=${fresh.quantity}, " +
                            "positionId=${fresh.id}, ticker=${fresh.ticker}"
                    }
                    meterRegistry.counter("$metricPrefix.close.impossible_delta", Tags.of("ticker", fresh.ticker)).increment()
                    handleImpossibleClose(fresh)
                    return true
                }
                fresh.cumulativeCloseFillQty = report.cumulativeFilledQty
                applyCloseExecution(fresh, delta, report.avgPrice!!, fresh.closeReason ?: CloseReason.EXECUTION_FILL)
            }
            return true
        }
        return false
    }

    /**
     * Applies close execution result: full → finalize, partial → applyPartialClose.
     */
    internal suspend fun applyCloseExecution(
        pos: Position,
        filled: Int,
        avg: BigDecimal,
        reason: CloseReason,
    ) {
        if (filled <= 0) return
        if (filled > pos.quantity) {
            logger.error {
                "Impossible close fill: filled=$filled > positionQuantity=${pos.quantity}, " +
                    "positionId=${pos.id}, ticker=${pos.ticker}"
            }
            meterRegistry.counter("$metricPrefix.close.impossible_delta", Tags.of("ticker", pos.ticker)).increment()
            handleImpossibleClose(pos)
            return
        }
        if (filled == pos.quantity) {
            finalizeClosePosition(pos, avg, reason)
        } else {
            applyPartialClose(pos, filled, avg)
        }
    }

    /**
     * Handle impossible close delta: cancel the live order, then clear state.
     *
     * [closeOrderId] is only cleared AFTER the exchange confirms the order is terminal.
     * This prevents over-sell: StockPositionMonitor skips positions with pendingClose=true
     * and closeOrderId set, so a new close order cannot be created while the old one
     * is still live.
     *
     * Flow:
     * 1. Keep pendingClose=true (prevent monitor from creating new close);
     * 2. Cancel the live order on exchange;
     * 3. CONFIRMED → clear state (clean OPEN);
     * 4. UNCERTAIN → leave for reconciler (will call confirmCloseFill → verifyOrder);
     * 5. REJECTED (already terminal) → clear state.
     */
    internal suspend fun handleImpossibleClose(pos: Position) {
        val orderId = pos.closeOrderId
        if (orderId == null) {
            resetCloseState(pos)
            return
        }

        pos.pendingClose = true
        positionRepo.save(pos)

        val cancelResult =
            try {
                alorClient.cancelOrder(
                    orderId = orderId,
                    idempotencyKey = "impossible-cancel-${pos.id}-$orderId",
                    portfolio = portfolioResolver(pos.accountId),
                )
            } catch (e: Exception) {
                logger.error(e) { "Impossible close: cancel failed for ${pos.ticker} order=$orderId — leaving for reconciler" }
                AlorClient.CancelResult.UNCERTAIN
            }

        when (cancelResult) {
            AlorClient.CancelResult.CONFIRMED -> {
                logger.warn { "Impossible close: order $orderId for ${pos.ticker} cancelled — clearing close state" }
                resetCloseState(pos)
            }

            AlorClient.CancelResult.REJECTED -> {
                logger.warn {
                    "Impossible close: order $orderId for ${pos.ticker} cancel rejected (already terminal) — clearing close state"
                }
                resetCloseState(pos)
            }

            AlorClient.CancelResult.UNCERTAIN -> {
                logger.warn {
                    "Impossible close: order $orderId for ${pos.ticker} cancel uncertain — leaving pendingClose=true for reconciler"
                }
                meterRegistry.counter("$metricPrefix.close.impossible_uncertain", Tags.of("ticker", pos.ticker)).increment()
            }
        }
    }

    /**
     * Reset close state to clean OPEN. Only called AFTER exchange order is confirmed terminal.
     */
    private suspend fun resetCloseState(pos: Position) {
        pos.pendingClose = false
        pos.closeOrderId = null
        pos.closeReason = null
        positionRepo.save(pos)
        logger.info { "Close state reset for ${pos.ticker} (${pos.id}) — position back to clean OPEN" }
    }

    /**
     * Partial fill: P&L for closed part, quantity reduced, remainder re-close.
     *
     * CRITICAL INVARIANT: pendingClose stays TRUE after partial fill.
     * The close order is still LIVE on the exchange — subsequent cumulative fills
     * of the same order must route through handlePendingCloseReport (which requires
     * pendingClose=true). Setting pendingClose=false would cause:
     *   1. StockPositionMonitor to call closePosition() → cancel live order → create new
     *   2. Late fills of the cancelled order lost (findByCloseOrderId returns null)
     *   3. Local position quantity diverges from exchange
     *
     * pendingClose is only cleared when the close order becomes terminal:
     * - Full close: finalizeClosePosition() sets pendingClose=false
     * - Order cancelled/rejected/expired: resetCloseState() sets pendingClose=false
     * - Impossible delta: handleImpossibleClose() waits for terminal before reset
     *
     * Protection orders deferred to reconciliation via PendingReplace flags.
     */
    private suspend fun applyPartialClose(
        pos: Position,
        filled: Int,
        avg: BigDecimal,
    ) {
        val partialPnl = pnlCalculator.pnl(pos, pos.entryPrice, avg, BigDecimal(filled))
        pos.realizedPnl = pos.realizedPnl.add(partialPnl)
        pos.quantity -= filled
        pos.currentPrice = avg
        if (pos.slOrderId != null) pos.slPendingReplace = true
        if (pos.tpOrderId != null) pos.tpPendingReplace = true
        positionRepo.save(pos)
        meterRegistry.counter("$metricPrefix.partial_close", Tags.of("ticker", pos.ticker)).increment()
        logger.warn {
            "PARTIAL close ${pos.ticker}: closed=$filled remainder=${pos.quantity} @ $avg " +
                "realized=$partialPnl ₽ (cumulative=${pos.realizedPnl}); " +
                "pendingClose=TRUE (close order still live), protection replacement deferred to reconciliation"
        }
    }

    /**
     * Full close: atomic transition to CLOSED/TAKE_PROFIT.
     *
     * [PositionRepository.transitionToClosed] ensures only one coroutine succeeds
     * in transitioning the row from OPEN.
     */
    internal suspend fun finalizeClosePosition(
        pos: Position,
        closePrice: BigDecimal,
        reason: CloseReason,
    ) {
        val positionId =
            pos.id ?: run {
                logger.error { "Cannot finalize close for ${pos.ticker}: position has no id" }
                return
            }
        val targetStatus =
            when (reason) {
                CloseReason.TAKE_PROFIT -> PositionStatus.TAKE_PROFIT
                else -> PositionStatus.CLOSED
            }
        val remainderPnl = pnlCalculator.pnl(pos, pos.entryPrice, closePrice, BigDecimal(pos.quantity))
        val totalPnl = pos.realizedPnl.add(remainderPnl)
        if (!positionRepo.transitionToClosed(positionId, targetStatus, closePrice, reason, totalPnl, pos.cumulativeCloseFillQty)) {
            logger.warn { "Finalize skip ${pos.ticker}: position already closed by another path" }
            return
        }
        pos.status = targetStatus
        pos.closedAt = java.time.LocalDateTime.now()
        pos.closePrice = closePrice
        pos.closeReason = reason
        pos.pnl = totalPnl
        pos.pendingClose = false
        pos.closeOrderId = null
        cancelProtectionOrders(pos)
        tradeEventService.recordPositionClosed(pos, reason.code)
        onPositionClosed(pos)
        positionRepo.releaseEntry(pos.ticker, pos.accountId)
        closeFillMutexes.remove(positionId)
        meterRegistry.counter("$metricPrefix.position.closed", Tags.of("ticker", pos.ticker, "reason", reason.code)).increment()
        logger.info { "Closed ${pos.ticker} reason=$reason P&L=$totalPnl" }
    }

    /**
     * Secondary reconciliation: exchange position disappeared → close confirmed.
     */
    internal suspend fun closeConfirmedByPositionDelta(pos: Position): Boolean =
        when (val result = alorClient.getPositions(portfolio = portfolioResolver(pos.accountId))) {
            is AlorClient.ReconcileResult.Failed -> {
                false
            }

            is AlorClient.ReconcileResult.Ok -> {
                val exchangeQty =
                    result.items
                        .firstOrNull { it.ticker.equals(pos.ticker, ignoreCase = true) }
                        ?.qty ?: 0L
                exchangeQty == 0L
            }
        }

    /**
     * Filled order statuses — order has been (at least partially) executed.
     */
    fun isFilledStatus(execution: AlorClient.OrderExecution): Boolean =
        when (execution.status.uppercase()) {
            "FILLED",
            "PARTIALLY_FILLED",
            -> execution.filledQuantity > 0

            else -> false
        }

    /**
     * Terminal order statuses — order is definitively gone from the exchange.
     */
    fun isGoneStatus(execution: AlorClient.OrderExecution): Boolean =
        when (execution.status.uppercase()) {
            "CANCELED",
            "CANCELLED",
            "REJECTED",
            "EXPIRED",
            -> true

            else -> false
        }
}
