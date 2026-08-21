package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.infrastructure.tracing.TraceContext
import com.trading.bot.model.CloseReason
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.dto.ExecutionReport
import com.trading.bot.model.dto.OrderStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.OrderOutboxService
import com.trading.bot.service.TradeEventService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal

/**
 * Расчёт P&L закрытой сделки. Различие инструментов:
 * - акции/FX: (exit - entry) * qty * lotSize − qty × commissionRub × 2 (qty = число лотов);
 * - фьючерсы: (exit - entry) * pointValue * qty.
 */
fun interface PnlCalculator {
    fun pnl(
        pos: Position,
        from: BigDecimal,
        to: BigDecimal,
        qty: BigDecimal,
    ): BigDecimal

    companion object {
        fun plain(): PnlCalculator = lotBased(lotSize = { 1L })

        /**
         * lot-based P&L для акций и FX: Δprice × qty × lotSize − round-trip commission.
         *
         * @param lotSize количество базовых единиц в лоте (ticker → lotSize)
         * @param commissionRub комиссия за лот за сторону в RUB (ticker → commissionRub).
         *        Вычитается как qty × commissionRub × 2 (вход + выход).
         */
        fun lotBased(
            lotSize: (String) -> Long,
            commissionRub: (String) -> BigDecimal? = { null },
        ): PnlCalculator =
            PnlCalculator { pos, from, to, qty ->
                val lots = BigDecimal(lotSize(pos.ticker))
                val pricePnl =
                    when (pos.direction) {
                        PositionDirection.LONG -> to.subtract(from).multiply(qty).multiply(lots)
                        PositionDirection.SHORT -> from.subtract(to).multiply(qty).multiply(lots)
                    }
                val commPerSide = commissionRub(pos.ticker) ?: BigDecimal.ZERO
                val totalCommission = commPerSide.multiply(qty).multiply(BigDecimal(2))
                pricePnl.subtract(totalCommission)
            }

        fun futures(pointValue: (String) -> BigDecimal): PnlCalculator =
            PnlCalculator { pos, from, to, qty ->
                val pv = pointValue(pos.ticker)
                when (pos.direction) {
                    PositionDirection.LONG -> to.subtract(from).multiply(pv).multiply(qty)
                    PositionDirection.SHORT -> from.subtract(to).multiply(pv).multiply(qty)
                }
            }
    }
}

/**
 * Общее ядро исполнения ордеров (акции и фьючерсы) — orchestrator.
 *
 * Делегирует:
 * - Delta-model close fills → [CloseFillProcessor];
 * - SL/TP protection orders → [ProtectionOrderManager];
 * - State Reconciliation → [ExecutionReconciler].
 *
 * Оркестрирует:
 * - Entry ([placeEntryOrder]);
 * - Close state machine ([closePosition]);
 * - WS ExecutionReport dispatch ([handleExecutionReport]).
 */
class OrderExecutionEngine(
    private val alorClient: AlorClient,
    private val orderOutboxService: OrderOutboxService,
    private val orderOutboxRepo: OrderOutboxRepository,
    private val positionRepo: PositionRepository,
    private val alorConfig: AlorConfig,
    private val objectMapper: tools.jackson.databind.ObjectMapper,
    private val tradeEventService: TradeEventService,
    private val meterRegistry: MeterRegistry,
    private val pnlCalculator: PnlCalculator,
    private val instrumentFilter: (Position) -> Boolean,
    private val metricPrefix: String,
    private val onEntryOpened: (Position) -> Unit = {},
    private val onPositionClosed: (Position) -> Unit = {},
    private val onSlProtectionFailed: (Position) -> Unit = {},
    private val protectionOrdersEnabled: Boolean = false,
    private val portfolioResolver: suspend (Long?) -> String = { alorConfig.portfolio },
) {
    private val logger = KotlinLogging.logger {}

    /** Delegates set after construction to break circular dependency. */
    internal lateinit var closeFill: CloseFillProcessor
    internal lateinit var protection: ProtectionOrderManager
    internal lateinit var reconciler: ExecutionReconciler

    init {
        closeFill = CloseFillProcessor(
            positionRepo = positionRepo,
            alorClient = alorClient,
            pnlCalculator = pnlCalculator,
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            metricPrefix = metricPrefix,
            portfolioResolver = portfolioResolver,
            onPositionClosed = onPositionClosed,
            cancelProtectionOrders = { pos ->
                protection.cancelProtectionOrders(pos)
            },
            attachProtectionOrders = { pos ->
                protection.attachProtectionOrders(pos)
            },
        )
        protection = ProtectionOrderManager(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = alorConfig,
            meterRegistry = meterRegistry,
            metricPrefix = metricPrefix,
            portfolioResolver = portfolioResolver,
            onSlProtectionFailed = onSlProtectionFailed,
            protectionOrdersEnabled = protectionOrdersEnabled,
            applyCloseExecution = { pos, filled, avg, reason ->
                closeFill.applyCloseExecution(pos, filled, avg, reason)
            },
        )
        reconciler = ExecutionReconciler(
            alorClient = alorClient,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = alorConfig,
            objectMapper = objectMapper,
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            metricPrefix = metricPrefix,
            portfolioResolver = portfolioResolver,
            onEntryOpened = onEntryOpened,
            isGoneStatus = { closeFill.isGoneStatus(it) },
            isFilledStatus = { closeFill.isFilledStatus(it) },
            attachProtectionOrders = { pos -> protection.attachProtectionOrders(pos) },
            confirmCloseFill = { pos, price, reason -> closeFill.confirmCloseFill(pos, price, reason) },
            reconcileProtectionOrders = { pos -> protection.reconcileProtectionOrders(pos) },
            onAbandonCleanup = { posId -> posId?.let { closeFill.closeFillMutexes.remove(it) } },
        )
    }

    // ═══════════════════════════ DELEGATIONS ═══════════════════════════════

    suspend fun onProtectionLevelsChanged(pos: Position) = protection.onProtectionLevelsChanged(pos)

    suspend fun handleCloseFill(pos: Position, report: ExecutionReport) = closeFill.handleCloseFill(pos, report)

    suspend fun reconcilePosition(pos: Position) = reconciler.reconcilePosition(pos)

    suspend fun resolveEntryViaOutbox(pos: Position) = reconciler.resolveEntryViaOutbox(pos)

    // ═══════════════════════════ ENTRY ═══════════════════════════════════

    /**
     * Entry: place limit order via outbox with three outcomes.
     */
    suspend fun placeEntryOrder(
        ticker: String,
        direction: PositionDirection,
        qty: Int,
        entryPrice: BigDecimal,
        accountId: Long? = null,
        buildPosition: (orderId: String?, pending: Boolean, fillPrice: BigDecimal, qty: Int) -> Position,
    ): Position? {
        if (qty <= 0) {
            logger.error { "Entry rejected $ticker: qty=$qty must be positive" }
            meterRegistry.counter("$metricPrefix.entry.rejected", Tags.of("ticker", ticker, "reason", "INVALID_QTY")).increment()
            return null
        }

        val reservedId = positionRepo.reserveEntry(ticker, direction, accountId)
        if (reservedId == null) {
            logger.warn { "Duplicate entry blocked $ticker (${direction.name}) — slot already reserved or position OPEN" }
            meterRegistry.counter("$metricPrefix.entry.duplicate", Tags.of("ticker", ticker)).increment()
            return null
        }

        val side = if (direction == PositionDirection.LONG) "buy" else "sell"
        val placed = orderOutboxService.placeOrder(ticker, side, qty, entryPrice, "limit", accountId = accountId)
        if (!placed.success || placed.alorOrderId == null) {
            if (placed.uncertain) {
                logger.warn { "Entry for $ticker UNCERTAIN (outbox=${placed.outboxId}); position created as pendingEntry" }
                val pos = buildPosition(null, true, entryPrice, qty).also { it.accountId = accountId }
                positionRepo.save(pos)
                meterRegistry.counter("$metricPrefix.entry.uncertain", Tags.of("ticker", ticker)).increment()
            } else {
                logger.error { "Order failed for $ticker" }
                positionRepo.releaseEntry(ticker, accountId)
                meterRegistry.counter("$metricPrefix.order.failed", Tags.of("ticker", ticker)).increment()
            }
            return null
        }

        val orderId = placed.alorOrderId
        val execution = alorClient.verifyOrder(orderId, portfolio = portfolioResolver(accountId))
        if (execution == null) {
            logger.warn {
                "verifyOrder UNKNOWN for $ticker (order=$orderId) — entry kept as pendingEntry until confirmed"
            }
            val unknownPos = buildPosition(orderId, true, entryPrice, qty).also { it.accountId = accountId }
            positionRepo.save(unknownPos)
            meterRegistry.counter("$metricPrefix.entry.uncertain", Tags.of("ticker", ticker)).increment()
            return null
        }
        val fillPrice = execution.avgPrice ?: entryPrice
        val filledQty = execution.filledQuantity.takeIf { it in 1 until qty }

        if (filledQty != null) {
            logger.warn {
                "PARTIAL entry $ticker: filled=$filledQty of $qty (order=$orderId) — " +
                    "pendingEntry until remainder cancelled/filled"
            }
            val partialPos = buildPosition(orderId, true, fillPrice, filledQty).also { it.accountId = accountId }
            positionRepo.save(partialPos)
            meterRegistry.counter("$metricPrefix.entry.partial", Tags.of("ticker", ticker)).increment()
            return null
        }

        val pos = buildPosition(orderId, false, fillPrice, qty).also { it.accountId = accountId }
        val savedPos = positionRepo.save(pos)
        tradeEventService.recordPositionOpened(savedPos)
        onEntryOpened(savedPos)
        protection.attachProtectionOrders(savedPos)
        meterRegistry.counter("$metricPrefix.position.opened", Tags.of("ticker", ticker, "direction", direction.name)).increment()
        logger.info { "Opened $ticker ${direction.name} $qty @ $fillPrice" }
        return savedPos
    }

    // ═══════════════════════════ CLOSE ═══════════════════════════════════

    private suspend fun handleProtectionFill(
        pos: Position,
        report: ExecutionReport,
        orderId: String,
        fillPrice: BigDecimal,
    ) {
        val positionId = pos.id ?: return
        val reason = if (orderId == pos.slOrderId) CloseReason.STOP_LOSS else CloseReason.TAKE_PROFIT
        val mutex = closeFill.closeFillMutexes.getOrPut(positionId) { kotlinx.coroutines.sync.Mutex() }
        mutex.withLock {
            val fresh = positionRepo.findById(positionId)
            if (fresh.status == PositionStatus.OPEN && (orderId == fresh.slOrderId || orderId == fresh.tpOrderId)) {
                protection.applyExchangeProtectionClosePublic(
                    fresh,
                    AlorClient.OrderExecution(report.status.name, report.cumulativeFilledQty, fillPrice),
                    reason,
                )
            }
        }
    }

    /**
     * Close position (state machine, double-execution protection).
     *
     * Atomic claim: [PositionRepository.claimForClose] serializes concurrent closes.
     */
    suspend fun closePosition(
        pos: Position,
        price: BigDecimal,
        reason: CloseReason,
    ) {
        val positionId =
            pos.id ?: run {
                logger.error { "Cannot close ${pos.ticker}: position has no id" }
                return
            }

        if (!positionRepo.claimForClose(positionId)) {
            val current = positionRepo.findById(positionId)
            if (current.pendingClose) {
                if (current.closeOrderId != null) {
                    closeFill.confirmCloseFill(current, price, reason)
                } else {
                    reconciler.reconcilePosition(current)
                }
            }
            return
        }

        val current = positionRepo.findById(positionId)
        val prevCumulativeFill = current.cumulativeCloseFillQty
        current.cumulativeCloseFillQty = 0

        if (current.closeOrderId != null) {
            if (prevCumulativeFill > 0) {
                val staleCloseId = current.closeOrderId!!
                logger.info {
                    "Partial close already applied for ${current.ticker} " +
                        "(cumulativeFill=$prevCumulativeFill) — cancelling stale close " +
                        "order $staleCloseId and creating fresh close order"
                }
                orderOutboxService.placeCancelOrder(positionId, staleCloseId, accountId = current.accountId)
                current.closeOrderId = null
                current.closeReason = null
                positionRepo.save(current)
            } else {
                closeFill.confirmCloseFill(current, price, reason)
                return
            }
        }

        val side =
            when (current.direction) {
                PositionDirection.LONG -> "sell"
                PositionDirection.SHORT -> "buy"
            }
        val placed =
            orderOutboxService.placeOrder(
                current.ticker,
                side,
                current.quantity,
                null,
                "market",
                positionId = positionId,
                closeReason = reason.code,
            )
        if (!placed.success || placed.alorOrderId == null) {
            if (placed.uncertain) {
                logger.warn {
                    "Close for ${current.ticker} UNCERTAIN (outbox=${placed.outboxId}); " +
                        "position stays open, pending outbox reconciliation"
                }
                current.pendingClose = true
                current.closeOrderId = null
                current.closeReason = reason
                positionRepo.save(current)
                meterRegistry.counter("$metricPrefix.close.uncertain", Tags.of("ticker", current.ticker)).increment()
            } else {
                logger.error { "Close order NOT accepted for ${current.ticker} ($reason); position stays OPEN" }
                meterRegistry.counter("$metricPrefix.close.rejected", Tags.of("ticker", current.ticker)).increment()
                positionRepo.releaseCloseClaim(positionId)
            }
            return
        }

        current.closeOrderId = placed.alorOrderId
        current.pendingClose = true
        current.closeReason = reason
        positionRepo.save(current)
        closeFill.confirmCloseFill(current, price, reason)
    }

    // ═══════════════════════════ WS DISPATCH ═════════════════════════════

    /**
     * WS ExecutionReport: dispatch to entry close, SL/TP, or pendingClose delta model.
     *
     * @return true if the report was handled by the engine.
     */
    suspend fun handleExecutionReport(report: ExecutionReport): Boolean {
        if (report.status != OrderStatus.FILLED && report.status != OrderStatus.PARTIALLY_FILLED) return false
        val orderId = report.orderId
        val pos =
            positionRepo.findByAlorOrderId(orderId) ?: positionRepo.findByCloseOrderId(orderId)
                ?: positionRepo.findBySlOrderId(orderId) ?: positionRepo.findByTpOrderId(orderId) ?: return false
        if (pos.status != PositionStatus.OPEN || pos.closedAt != null) return false
        if (!instrumentFilter(pos)) return false
        TraceContext.put(TraceContext.TRACE_ID, pos.cycleId)
        TraceContext.put(TraceContext.CYCLE_ID, pos.cycleId)
        val fillPrice = report.avgPrice ?: return false

        // SL/TP execution
        if (orderId == pos.slOrderId || orderId == pos.tpOrderId) {
            handleProtectionFill(pos, report, orderId, fillPrice)
            return true
        }

        // Entry confirmation (pendingEntry)
        if (pos.pendingEntry) {
            if (report.status == OrderStatus.FILLED) {
                pos.alorOrderId = orderId
                pos.pendingEntry = false
                pos.entryPrice = fillPrice
                pos.quantity = report.cumulativeFilledQty.coerceAtLeast(1)
                positionRepo.save(pos)
                tradeEventService.recordPositionOpened(pos)
                onEntryOpened(pos)
                protection.attachProtectionOrders(pos)
                logger.info { "WS entry fill applied for ${pos.ticker}: order=$orderId qty=${pos.quantity} @ $fillPrice" }
            }
            return true
        }

        // Close confirmation (pendingClose) — delta model
        return closeFill.handlePendingCloseReport(pos, report)
    }

    // ═══════════════════════════ ABANDON ═════════════════════════════════

    internal suspend fun abandonEntry(pos: Position, reason: CloseReason) = reconciler.abandonEntry(pos, reason)
}
