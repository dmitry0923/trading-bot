package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.infrastructure.tracing.TraceContext
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.dto.ExecutionReport
import com.trading.bot.model.dto.OrderStatus
import com.trading.bot.model.entity.OutboxStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.OrderOutboxService
import com.trading.bot.service.TradeEventService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Расчёт P&L закрытой сделки. Различие инструментов:
 * - акции: (exit - entry) * qty;
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
        fun plain(): PnlCalculator =
            PnlCalculator { pos, from, to, qty ->
                when (pos.direction) {
                    PositionDirection.LONG -> to.subtract(from).multiply(qty)
                    PositionDirection.SHORT -> from.subtract(to).multiply(qty)
                }
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
 * Общее ядро исполнения ордеров (акции и фьючерсы).
 *
 * Единая реализация защиты от double execution / потери контроля над позицией:
 * - close-стейт-машина ([closePosition]): пока [Position.pendingClose] — новый ордер
 *   НЕ создаётся, [Position.closeOrderId] сверяется через verifyOrder / position delta /
 *   outbox-запись;
 * - partial fills (вход и закрытие): [applyPartialClose] реализует P&L закрытой части,
 *   остаток дозакрывается следующей итерацией;
 * - реконсиляция pendingEntry/pendingClose через outbox ([resolveEntryViaOutbox],
 *   [resolveCloseViaOutbox], [reconcilePosition]);
 * - применение WS ExecutionReport ([handleExecutionReport]);
 * - вход ([placeEntryOrder]) с обработкой UNCERTAIN / PARTIAL / full fill.
 *
 * Различия инструментов инкапсулированы через:
 * - [PnlCalculator] — P&L (акции без pointValue, фьючерсы с pointValue);
 * - [instrumentFilter] — какие позиции обрабатывать (акции/фьючерсы);
 * - [onEntryOpened] / [onPositionClosed] — побочные эффекты (например, публикация
 *   PositionOpened/PositionClosed для фьючерсов, учёт дневного P&L для акций);
 * - [metricPrefix] — префикс метрик (bot.* / futures.*).
 *
 * НЕ является Spring-бином: создаётся внутри TradingBotService /
 * FuturesTradingBotService из их зависимостей (стейтлесс — все данные в БД).
 */
class OrderExecutionEngine(
    private val alorClient: AlorClient,
    private val orderOutboxService: OrderOutboxService,
    private val orderOutboxRepo: OrderOutboxRepository,
    private val positionRepo: PositionRepository,
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
    private val tradeEventService: TradeEventService,
    private val meterRegistry: MeterRegistry,
    private val pnlCalculator: PnlCalculator,
    private val instrumentFilter: (Position) -> Boolean,
    private val metricPrefix: String,
    private val onEntryOpened: (Position) -> Unit = {},
    private val onPositionClosed: (Position) -> Unit = {},
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Вход: размещение limit-ордера через outbox с обработкой трёх исходов.
     *
     * - UNCERTAIN → позиция создаётся в pendingEntry (факт подтвердит реконсилятор);
     * - PARTIAL fill → позиция в pendingEntry на фактическом объёме, остаток лимитки
     *   отменяет реконсилятор после [AlorConfig.entryPartialFillCancelAfterMs];
     * - полное исполнение → фиксация входа, [TradeEventService.recordPositionOpened] +
     *   [onEntryOpened].
     *
     * @param buildPosition строит позицию инструмента (акции/фьючерсы): аргументы
     *   (orderId, pending, fillPrice, qty) — orderId=null при UNCERTAIN, fillPrice=entryPrice
     *   до подтверждения исполнения.
     * @return открытая позиция при полном исполнении, иначе null.
     */
    suspend fun placeEntryOrder(
        ticker: String,
        direction: PositionDirection,
        qty: Int,
        entryPrice: BigDecimal,
        buildPosition: (orderId: String?, pending: Boolean, fillPrice: BigDecimal, qty: Int) -> Position,
    ): Position? {
        val side = if (direction == PositionDirection.LONG) "buy" else "sell"
        val placed = orderOutboxService.placeOrder(ticker, side, qty, entryPrice, "limit")
        if (!placed.success || placed.alorOrderId == null) {
            if (placed.uncertain) {
                // Запрос мог дойти до Alor → создаём позицию в состоянии pendingEntry;
                // факт исполнения подтвердит реконсилятор (State Reconciliation).
                logger.warn { "Entry for $ticker UNCERTAIN (outbox=${placed.outboxId}); position created as pendingEntry" }
                val pos = buildPosition(null, true, entryPrice, qty)
                positionRepo.save(pos)
                meterRegistry.counter("$metricPrefix.entry.uncertain", Tags.of("ticker", ticker)).increment()
            } else {
                logger.error { "Order failed for $ticker" }
                meterRegistry.counter("$metricPrefix.order.failed", Tags.of("ticker", ticker)).increment()
            }
            return null
        }

        val orderId = placed.alorOrderId
        val execution = alorClient.verifyOrder(orderId)
        val fillPrice = execution?.avgPrice ?: entryPrice
        val filledQty = execution?.filledQuantity?.takeIf { it in 1 until qty }

        if (filledQty != null) {
            // Частичное исполнение входа: остаток лимитки ещё «висит» на бирже.
            // Позиция создаётся в pendingEntry — реконсилятор (resolveEntryViaOutbox)
            // после entryPartialFillCancelAfterMs отменит остаток и зафиксирует
            // фактический объём (защита от скрытого роста позиции без ведома бота).
            logger.warn {
                "PARTIAL entry $ticker: filled=$filledQty of $qty (order=$orderId) — " +
                    "pendingEntry until remainder cancelled/filled"
            }
            val partialPos = buildPosition(orderId, true, fillPrice, filledQty)
            positionRepo.save(partialPos)
            meterRegistry.counter("$metricPrefix.entry.partial", Tags.of("ticker", ticker)).increment()
            return null
        }

        val pos = buildPosition(orderId, false, fillPrice, qty)
        positionRepo.save(pos)
        tradeEventService.recordPositionOpened(pos)
        onEntryOpened(pos)
        meterRegistry.counter("$metricPrefix.position.opened", Tags.of("ticker", ticker, "direction", direction.name)).increment()
        logger.info { "Opened $ticker ${direction.name} $qty @ $fillPrice" }
        return pos
    }

    /**
     * Закрытие позиции (стейт-машина, защита от double execution).
     */
    suspend fun closePosition(
        pos: Position,
        price: BigDecimal,
        reason: String,
    ) {
        // Уже идёт закрытие — НЕ создаём второй ордер, сверяем состояние текущего.
        if (pos.pendingClose) {
            if (pos.closeOrderId != null) {
                confirmCloseFill(pos, price, reason)
            } else {
                resolveCloseViaOutbox(pos)
            }
            return
        }

        val side =
            when (pos.direction) {
                PositionDirection.LONG -> "sell"
                PositionDirection.SHORT -> "buy"
            }
        val placed =
            orderOutboxService.placeOrder(
                pos.ticker,
                side,
                pos.quantity,
                null,
                "market",
                positionId = pos.id,
                closeReason = reason,
            )
        if (!placed.success || placed.alorOrderId == null) {
            if (placed.uncertain) {
                logger.warn {
                    "Close for ${pos.ticker} UNCERTAIN (outbox=${placed.outboxId}); " +
                        "position stays open, pending outbox reconciliation"
                }
                pos.pendingClose = true
                pos.closeOrderId = null
                pos.closeReason = reason
                positionRepo.save(pos)
                meterRegistry.counter("$metricPrefix.close.uncertain", Tags.of("ticker", pos.ticker)).increment()
            } else {
                logger.error { "Close order NOT accepted for ${pos.ticker} ($reason); position stays OPEN" }
                meterRegistry.counter("$metricPrefix.close.rejected", Tags.of("ticker", pos.ticker)).increment()
                pos.pendingClose = false
                positionRepo.save(pos)
            }
            return
        }

        pos.closeOrderId = placed.alorOrderId
        pos.pendingClose = true
        pos.closeReason = reason
        positionRepo.save(pos)
        confirmCloseFill(pos, price, reason)
    }

    /**
     * Пер-позиционный шаг фоновой State Reconciliation (pendingEntry/pendingClose).
     */
    suspend fun reconcilePosition(pos: Position) {
        when {
            pos.pendingEntry -> {
                resolveEntryViaOutbox(pos)
            }

            pos.pendingClose -> {
                if (pos.closeOrderId != null) {
                    confirmCloseFill(pos, pos.currentPrice ?: pos.entryPrice, pos.closeReason ?: "RECONCILIATION")
                } else {
                    resolveCloseViaOutbox(pos)
                }
            }

            else -> {}
        }
    }

    /**
     * Применяет ExecutionReport из WebSocket: фиксация фактического исполнения
     * входа (pendingEntry) и закрытия (pendingClose), с учётом partial fills.
     *
     * @return true, если отчёт обработан ядром (вход/закрытие); false — отчёт не
     *   относится к pending-состоянию (например, обычный fill акции — обрабатывает
     *   TradingBotService).
     */
    suspend fun handleExecutionReport(report: ExecutionReport): Boolean {
        if (report.status != OrderStatus.FILLED && report.status != OrderStatus.PARTIALLY_FILLED) return false
        val orderId = report.orderId
        val pos = positionRepo.findByAlorOrderId(orderId) ?: positionRepo.findByCloseOrderId(orderId) ?: return false
        if (pos.status != PositionStatus.OPEN || pos.closedAt != null) return false
        if (!instrumentFilter(pos)) return false
        TraceContext.put(TraceContext.TRACE_ID, pos.cycleId)
        TraceContext.put(TraceContext.CYCLE_ID, pos.cycleId)
        val fillPrice = report.avgPrice ?: return false

        // Подтверждение входа (pendingEntry).
        if (pos.pendingEntry) {
            if (report.status == OrderStatus.FILLED) {
                pos.alorOrderId = orderId
                pos.pendingEntry = false
                pos.entryPrice = fillPrice
                pos.quantity = report.filledQty.coerceAtLeast(1)
                positionRepo.save(pos)
                tradeEventService.recordPositionOpened(pos)
                onEntryOpened(pos)
                logger.info { "WS entry fill applied for ${pos.ticker}: order=$orderId qty=${pos.quantity} @ $fillPrice" }
            }
            // PARTIALLY_FILLED вход — оставляем реконсилятору (verifyOrder даст кумулятивный fill).
            return true
        }

        // Подтверждение закрытия (pendingClose).
        if (pos.pendingClose) {
            applyCloseExecution(pos, report.filledQty, fillPrice, pos.closeReason ?: "EXECUTION_FILL")
            return true
        }

        return false
    }

    /**
     * Подтверждение исполнения close-ордера через verifyOrder.
     */
    private suspend fun confirmCloseFill(
        pos: Position,
        expectedPrice: BigDecimal,
        reason: String,
    ) {
        val orderId = pos.closeOrderId ?: return
        val execution = alorClient.verifyOrder(orderId, expectedPrice = expectedPrice)
        if (execution == null) {
            // verifyOrder недоступен → вторичная сверка по qty позиции на бирже:
            // если позиция закрыта/уменьшена, close-ордер исполнился (защита от
            // зависшего pendingClose после исчерпания REST-сверки заявок).
            if (closeConfirmedByPositionDelta(pos)) {
                logger.warn {
                    "Close order $orderId for ${pos.ticker} confirmed by position delta " +
                        "(exchange position reduced) — finalizing at $expectedPrice"
                }
                applyCloseExecution(pos, pos.quantity, expectedPrice, reason)
            } else {
                logger.warn { "Close order $orderId for ${pos.ticker} state UNKNOWN; pending reconciliation" }
            }
            return
        }
        val avg = execution.avgPrice ?: expectedPrice
        applyCloseExecution(pos, execution.filledQuantity, avg, reason)
    }

    /**
     * Вторичная State Reconciliation close-ордера: позиция на бирже закрыта
     * (qty=0) или уменьшилась в абсолюте → close исполнился, даже если
     * verifyOrder/список заявок не подтверждают (eventual consistency).
     */
    private suspend fun closeConfirmedByPositionDelta(pos: Position): Boolean =
        when (val result = alorClient.getPositions()) {
            is AlorClient.ReconcileResult.Failed -> {
                false
            }

            is AlorClient.ReconcileResult.Ok -> {
                val signed =
                    if (pos.direction == PositionDirection.LONG) {
                        pos.quantity.toLong()
                    } else {
                        -pos.quantity.toLong()
                    }
                val exchangeQty =
                    result.items
                        .firstOrNull { it.ticker.equals(pos.ticker, ignoreCase = true) }
                        ?.qty
                        ?: 0L
                exchangeQty == 0L || abs(exchangeQty) < abs(signed)
            }
        }

    /**
     * Применяет результат исполнения close-ордера (verifyOrder или WS):
     * полное → финализация, частичное → дозакрытие остатка.
     */
    private suspend fun applyCloseExecution(
        pos: Position,
        filled: Int,
        avg: BigDecimal,
        reason: String,
    ) {
        val filledQty = filled.coerceIn(0, pos.quantity)
        if (filledQty <= 0) return
        if (filledQty >= pos.quantity) {
            finalizeClosePosition(pos, avg, reason)
        } else {
            applyPartialClose(pos, filledQty, avg)
        }
    }

    /**
     * Partial fill: реализуем P&L закрытой части, уменьшаем quantity, остаток дозакрываем.
     */
    private suspend fun applyPartialClose(
        pos: Position,
        filled: Int,
        avg: BigDecimal,
    ) {
        val partialPnl = pnlCalculator.pnl(pos, pos.entryPrice, avg, BigDecimal(filled))
        pos.realizedPnl = pos.realizedPnl.add(partialPnl)
        pos.quantity -= filled
        pos.closeOrderId = null
        pos.pendingClose = false
        pos.currentPrice = avg
        positionRepo.save(pos)
        meterRegistry.counter("$metricPrefix.partial_close", Tags.of("ticker", pos.ticker)).increment()
        logger.warn {
            "PARTIAL close ${pos.ticker}: closed=$filled remainder=${pos.quantity} @ $avg " +
                "realized=$partialPnl ₽ (cumulative=${pos.realizedPnl}); remainder will be re-closed"
        }
    }

    /**
     * Полное закрытие: P&L = realizedPnl (partial) + P&L остатка.
     */
    private suspend fun finalizeClosePosition(
        pos: Position,
        closePrice: BigDecimal,
        reason: String,
    ) {
        val remainderPnl = pnlCalculator.pnl(pos, pos.entryPrice, closePrice, BigDecimal(pos.quantity))
        val totalPnl = pos.realizedPnl.add(remainderPnl)
        pos.status =
            when (reason) {
                "TAKE_PROFIT" -> PositionStatus.TAKE_PROFIT
                else -> PositionStatus.CLOSED
            }
        pos.closedAt = LocalDateTime.now()
        pos.closePrice = closePrice
        pos.closeReason = reason
        pos.pnl = totalPnl
        pos.pendingClose = false
        pos.closeOrderId = null
        positionRepo.save(pos)
        tradeEventService.recordPositionClosed(pos, reason)
        onPositionClosed(pos)
        meterRegistry.counter("$metricPrefix.position.closed", Tags.of("ticker", pos.ticker, "reason", reason)).increment()
        logger.info { "Closed ${pos.ticker} reason=$reason P&L=$totalPnl" }
    }

    /**
     * Сверка pendingEntry-позиции через outbox-запись.
     *
     * Управляет остатком лимитного входа после частичного исполнения:
     * - кумулятивный fill обновляет [Position.quantity] до полного исполнения;
     * - остаток, «висящий» на бирже дольше [AlorConfig.entryPartialFillCancelAfterMs],
     *   отменяется ([AlorClient.cancelOrder]), вход фиксируется на фактическом объёме
     *   (защита от скрытого роста позиции без ведома бота).
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
                val execution = alorClient.verifyOrder(outbox.alorOrderId) ?: return
                if (execution.status.contains("reject") || execution.status.contains("cancel")) {
                    abandonEntry(pos, "ENTRY_REJECTED")
                    return
                }
                if (execution.filledQuantity <= 0) return // лимитный ордер ещё не исполнился

                val requestedQty =
                    objectMapper
                        .readTree(outbox.payloadJson)
                        .path("qty")
                        .asInt(0)
                        .takeIf { it > 0 }
                        ?: pos.quantity
                val cumulative = execution.filledQuantity.coerceIn(1, requestedQty)
                val remainder = requestedQty - cumulative

                // Частичное исполнение с остатком на бирже.
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
                    // Порог пройден → снимаем остаток лимитки.
                    val cancelled =
                        try {
                            alorClient.cancelOrder(outbox.alorOrderId, outbox.idempotencyKey ?: "")
                        } catch (e: Exception) {
                            logger.error(e) {
                                "Entry remainder cancel FAILED for ${pos.ticker} " +
                                    "(order=${outbox.alorOrderId}) — retry next cycle"
                            }
                            false
                        }
                    if (!cancelled) return // отмена не подтверждена → ждём следующего цикла
                    val finalExec = alorClient.verifyOrder(outbox.alorOrderId)
                    val finalQty = (finalExec?.filledQuantity ?: cumulative).coerceIn(1, requestedQty)
                    pos.alorOrderId = outbox.alorOrderId
                    pos.pendingEntry = false
                    pos.entryPrice = finalExec?.avgPrice ?: execution.avgPrice ?: pos.entryPrice
                    pos.quantity = finalQty
                    positionRepo.save(pos)
                    tradeEventService.recordPositionOpened(pos)
                    onEntryOpened(pos)
                    meterRegistry.counter("$metricPrefix.entry.remainder_cancelled", Tags.of("ticker", pos.ticker)).increment()
                    logger.info {
                        "Pending entry ${pos.ticker} finalized after remainder cancel: " +
                            "qty=${pos.quantity} @ ${pos.entryPrice} (order=${outbox.alorOrderId})"
                    }
                    return
                }

                // Полное исполнение → фиксируем вход.
                pos.alorOrderId = outbox.alorOrderId
                pos.pendingEntry = false
                pos.entryPrice = execution.avgPrice ?: pos.entryPrice
                pos.quantity = cumulative
                positionRepo.save(pos)
                tradeEventService.recordPositionOpened(pos)
                onEntryOpened(pos)
                logger.info {
                    "Pending entry resolved ${pos.ticker}: order=${outbox.alorOrderId} qty=${pos.quantity} @ ${pos.entryPrice}"
                }
            }

            outbox.status == OutboxStatus.FAILED && outbox.retryCount >= alorConfig.maxOrderRetries -> {
                abandonEntry(pos, "ENTRY_NOT_CONFIRMED")
            }

            else -> {} // PENDING / FAILED (ещё ретраится) → ждём
        }
    }

    /**
     * Сверка pendingClose-позиции без closeOrderId через outbox-запись.
     */
    suspend fun resolveCloseViaOutbox(pos: Position) {
        val positionId =
            pos.id ?: run {
                logger.error { "Pending close ${pos.ticker} has no id — cannot reconcile via outbox" }
                return
            }
        val outbox = orderOutboxRepo.findLatestByPositionId(positionId)
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
                confirmCloseFill(pos, pos.currentPrice ?: pos.entryPrice, pos.closeReason ?: "RECONCILIATION")
            }

            outbox.status == OutboxStatus.FAILED && outbox.retryCount >= alorConfig.maxOrderRetries -> {
                logger.warn { "Pending close ${pos.id}/${pos.ticker} permanently failed; resetting for a fresh close order" }
                pos.pendingClose = false
                pos.closeOrderId = null
                positionRepo.save(pos)
            }

            else -> {} // PENDING / FAILED (ещё ретраится) → ждём outbox worker
        }
    }

    private suspend fun abandonEntry(
        pos: Position,
        reason: String,
    ) {
        logger.warn { "Entry for ${pos.ticker} abandoned: $reason" }
        pos.pendingEntry = false
        pos.status = PositionStatus.CLOSED
        pos.closeReason = reason
        pos.closedAt = LocalDateTime.now()
        positionRepo.save(pos)
        meterRegistry.counter("$metricPrefix.entry.abandoned", Tags.of("ticker", pos.ticker, "reason", reason)).increment()
    }
}
