package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.domain.risk.ExitRules
import com.trading.bot.infrastructure.tracing.TraceContext
import com.trading.bot.model.CloseReason
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
 * - акции/FX: (exit - entry) * qty * lotSize (qty = число лотов);
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
        fun plain(): PnlCalculator = lotBased { 1L }

        /** lot-based P&L для акций и FX: Δprice × qty × lotSize. */
        fun lotBased(lotSize: (String) -> Long): PnlCalculator =
            PnlCalculator { pos, from, to, qty ->
                val lots = BigDecimal(lotSize(pos.ticker))
                when (pos.direction) {
                    PositionDirection.LONG -> to.subtract(from).multiply(qty).multiply(lots)
                    PositionDirection.SHORT -> from.subtract(to).multiply(qty).multiply(lots)
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
 * - вход ([placeEntryOrder]) с обработкой UNCERTAIN / PARTIAL / full fill;
 * - биржевые защитные заявки SL/TP ([attachProtectionOrders], [onProtectionLevelsChanged],
 *   [reconcileProtectionOrders], [cancelProtectionOrders]) — «точный контроль SL/TP»
 *   (roadmap v2.2): стоп/тейк выставляются на бирже при открытии позиции, исполняются
 *   биржей, перевыставляются при сдвиге trailing/стратегии и снимаются при закрытии.
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
    private val onSlProtectionFailed: (Position) -> Unit = {},
    private val protectionOrdersEnabled: Boolean = false,
    private val portfolioResolver: suspend (Long?) -> String = { alorConfig.portfolio },
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
        accountId: Long? = null,
        buildPosition: (orderId: String?, pending: Boolean, fillPrice: BigDecimal, qty: Int) -> Position,
    ): Position? {
        if (qty <= 0) {
            logger.error { "Entry rejected $ticker: qty=$qty must be positive" }
            meterRegistry.counter("$metricPrefix.entry.rejected", Tags.of("ticker", ticker, "reason", "INVALID_QTY")).increment()
            return null
        }

        // Атомарная резервация слота (EXEC-002, MR-B): ДО отправки ордера. Уникальный
        // индекс entry_reservations (ticker, account) гарантирует, что из конкурентных
        // входов по одному слоту выигрывает один — остальные не создают второй
        // entry-ордер (distributed lock выключен по умолчанию, см. DistributedLockConfig).
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
                // Заявка могла дойти до Alor — создаём позицию в статусе pendingEntry;
                // факт исполнения подтвердит реконсилятор (State Reconciliation).
                logger.warn { "Entry for $ticker UNCERTAIN (outbox=${placed.outboxId}); position created as pendingEntry" }
                val pos = buildPosition(null, true, entryPrice, qty).also { it.accountId = accountId }
                positionRepo.save(pos)
                meterRegistry.counter("$metricPrefix.entry.uncertain", Tags.of("ticker", ticker)).increment()
            } else {
                logger.error { "Order failed for $ticker" }
                // Определённый отказ — ордер НЕ создан: освобождаем слот входа.
                positionRepo.releaseEntry(ticker, accountId)
                meterRegistry.counter("$metricPrefix.order.failed", Tags.of("ticker", ticker)).increment()
            }
            return null
        }

        val orderId = placed.alorOrderId
        val execution = alorClient.verifyOrder(orderId, portfolio = portfolioResolver(accountId))
        if (execution == null) {
            // EXEC-3 (roadmap 13.27): verifyOrder недоступен — факт исполнения входа НЕ
            // подтверждён. Раньше сбой трактовался как полное исполнение (non-pending OPEN
            // позиция по цене входа): локальный стейт завышался, SL/TP армовались на полный
            // qty, а пришедший позже fill уходил в ложное закрытие (EXEC-1). Теперь позиция
            // создаётся в pendingEntry (как при UNCERTAIN-доставке): fill подтвердит
            // handleExecutionReport (WS), отмену/режект/незаполненность — resolveEntryViaOutbox
            // и State Reconciliation.
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
            // Частичное исполнение входа: остаток лимитки ещё «висит» на бирже.
            // Позиция создаётся в pendingEntry — реконсилятор (resolveEntryViaOutbox)
            // после entryPartialFillCancelAfterMs отменит остаток и зафиксирует
            // фактический объём (защита от скрытого роста позиции без ведома бота).
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
        attachProtectionOrders(savedPos)
        meterRegistry.counter("$metricPrefix.position.opened", Tags.of("ticker", ticker, "direction", direction.name)).increment()
        logger.info { "Opened $ticker ${direction.name} $qty @ $fillPrice" }
        return savedPos
    }

    /**
     * Закрытие позиции (стейт-машина, защита от double execution).
     *
     * Атомарный claim (EXEC-001): [PositionRepository.claimForClose] делает одиночный
     * `UPDATE ... SET pending_close = true WHERE status='OPEN' AND pending_close=false`
     * и возвращает затронул ли он 1 строку. PostgreSQL row lock сериализует
     * конкурентные закрытия (монитор ликвидации, стоп-лосс, стратегия, реконсилятор) —
     * второй поток получит false и НЕ создаст close-ордер, а только свернёт состояние
     * уже существующего (confirmCloseFill / resolveCloseViaOutbox). Раньше между
     * чтением `pendingClose` и записью было окно гонки, в котором два потока могли
     * поставить два независимых close-ордера (два idempotency key, двойное закрытие).
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

        // Атомарный claim: только один поток получает право создавать close-ордер.
        if (!positionRepo.claimForClose(positionId)) {
            val current = positionRepo.findById(positionId)
            if (current.pendingClose) {
                if (current.closeOrderId != null) {
                    confirmCloseFill(current, price, reason)
                } else {
                    resolveCloseViaOutbox(current)
                }
            }
            return
        }

        val current = positionRepo.findById(positionId)
        // Повторная попытка закрытия при уже существующем close-ордере (например,
        // после UNCERTAIN-доставки без closeOrderId реконсилятор мог проставить id) —
        // новый ордер НЕ создаём, только сверяем исполнение.
        if (current.closeOrderId != null) {
            confirmCloseFill(current, price, reason)
            return
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
                // Claim уже поставил pending_close=true; позиция остаётся открытой,
                // ордер мог дойти до биржи — реконсилятор outbox доведёт до конца.
                current.pendingClose = true
                current.closeOrderId = null
                current.closeReason = reason
                positionRepo.save(current)
                meterRegistry.counter("$metricPrefix.close.uncertain", Tags.of("ticker", current.ticker)).increment()
            } else {
                logger.error { "Close order NOT accepted for ${current.ticker} ($reason); position stays OPEN" }
                meterRegistry.counter("$metricPrefix.close.rejected", Tags.of("ticker", current.ticker)).increment()
                // Определённый отказ биржи — ордер НЕ создан: освобождаем claim,
                // позиция снова закрываема. Guard `close_order_id IS NULL` защищает от
                // затирания closeOrderId, который реконсилятор мог успеть проставить.
                positionRepo.releaseCloseClaim(positionId)
            }
            return
        }

        current.closeOrderId = placed.alorOrderId
        current.pendingClose = true
        current.closeReason = reason
        positionRepo.save(current)
        confirmCloseFill(current, price, reason)
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
                    confirmCloseFill(pos, pos.currentPrice ?: pos.entryPrice, pos.closeReason ?: CloseReason.RECONCILIATION)
                } else {
                    resolveCloseViaOutbox(pos)
                }
            }

            else -> {
                // Открытая позиция без pending-состояния: сверяем биржевые защитные
                // заявки SL/TP (пропагация id из outbox, детект исполнения/отмены,
                // завершение перевыставлений, выставление недостающих).
                reconcileProtectionOrders(pos)
            }
        }
    }

    // ===================== Биржевые защитные заявки (SL/TP) =====================
    //
    // «Точный контроль SL/TP в лимитных заявках» (roadmap v2.2): при подтверждении
    // входа выставляем на бирже стоп- и тейк-заявки через outbox (гарантия доставки).
    // - [attachProtectionOrders] — выставить недостающие заявки на текущих уровнях;
    // - [onProtectionLevelsChanged] — сдвиг уровня (trailing/стратегия) → перевыставление;
    // - [reconcileProtectionOrders] — фоновое обслуживание: пропагация id из outbox,
    //   детект исполнения/отмены заявок, завершение перевыставлений;
    // - [cancelProtectionOrders] — снять защитные заявки при закрытии позиции.
    //
    // Уровни: SL = [ExitRules.effectiveSl] (жёсткий stopLoss либо trailing, если он
    // «строже»), TP = takeProfit. Пока старая заявка не снята (slPendingReplace/
    // tpPendingReplace) — НОВАЯ не выставляется (защита от двойного стопа/тейка).

    private fun protectionCloseSide(pos: Position): String =
        when (pos.direction) {
            PositionDirection.LONG -> "sell"
            PositionDirection.SHORT -> "buy"
        }

    /**
     * Выставляет недостающие биржевые SL/TP-заявки (идемпотентно). Вызывается при
     * подтверждении входа (полное исполнение / WS fill / resolve через outbox) и
     * из [reconcileProtectionOrders].
     */
    suspend fun attachProtectionOrders(pos: Position) {
        if (!protectionOrdersEnabled) return
        if (pos.status != PositionStatus.OPEN) return
        val positionId =
            pos.id ?: run {
                logger.error { "Cannot attach protection orders for ${pos.ticker}: position has no id" }
                return
            }
        var dirty = false

        // SL: эффективный уровень (жёсткий стоп либо trailing, если строже).
        val effSl = ExitRules.effectiveSl(pos)
        if (effSl != null && pos.slOrderId == null && !pos.slPendingReplace && !protectionOutboxActive(positionId, "sl")) {
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
                dirty = true
                logger.info { "Exchange SL placed ${pos.ticker} @ $effSl qty=${pos.quantity} -> ${placed.alorOrderId}" }
            } else {
                logger.warn { "Exchange SL for ${pos.ticker} @ $effSl not confirmed (uncertain/rejected) — reconcile will resolve" }
            }
        }

        // TP: тейк-профит на takeProfit.
        val tp = pos.takeProfit
        if (tp != null && pos.tpOrderId == null && !pos.tpPendingReplace && !protectionOutboxActive(positionId, "tp")) {
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
                dirty = true
                logger.info { "Exchange TP placed ${pos.ticker} @ $tp qty=${pos.quantity} -> ${placed.alorOrderId}" }
            } else {
                logger.warn { "Exchange TP for ${pos.ticker} @ $tp not confirmed (uncertain/rejected) — reconcile will resolve" }
            }
        }

        if (dirty) positionRepo.save(pos)
    }

    /**
     * Сдвиг SL/TP-уровня (trailing-стоп, обновление стратегией): если уровень
     * изменился относительно выставленного — планируем перевыставление
     * ([Position.slPendingReplace]/[Position.tpPendingReplace]); фактическую
     * отмену+перестановку выполнит [finishProtectionReplacement]. Недостающие
     * заявки выставляются сразу.
     */
    suspend fun onProtectionLevelsChanged(pos: Position) {
        if (!protectionOrdersEnabled) return
        if (pos.status != PositionStatus.OPEN) return
        var dirty = false

        val effSl = ExitRules.effectiveSl(pos)
        if (effSl != null && pos.slOrderId != null && pos.slOrderPrice != null &&
            effSl.compareTo(pos.slOrderPrice) != 0 && !pos.slPendingReplace
        ) {
            pos.slPendingReplace = true
            dirty = true
            logger.info { "SL level changed ${pos.ticker}: ${pos.slOrderPrice} -> $effSl (replace scheduled)" }
        }

        val tp = pos.takeProfit
        if (tp != null && pos.tpOrderId != null && pos.tpOrderPrice != null &&
            tp.compareTo(pos.tpOrderPrice) != 0 && !pos.tpPendingReplace
        ) {
            pos.tpPendingReplace = true
            dirty = true
            logger.info { "TP level changed ${pos.ticker}: ${pos.tpOrderPrice} -> $tp (replace scheduled)" }
        }

        if (dirty) positionRepo.save(pos)
        attachProtectionOrders(pos)
    }

    /**
     * Фоновое обслуживание защитных заявок открытой позиции (цикл State Reconciliation):
     * пропагация orderId из outbox, детект исполнения/отмены, завершение
     * перевыставлений, выставление недостающих заявок.
     */
    private suspend fun reconcileProtectionOrders(pos: Position) {
        if (!protectionOrdersEnabled) return
        if (pos.status != PositionStatus.OPEN) return
        resolveProtectionOutbox(pos)
        if (pos.status != PositionStatus.OPEN) return
        checkProtectionFills(pos)
        if (pos.status != PositionStatus.OPEN) return
        finishProtectionReplacement(pos)
        attachProtectionOrders(pos)
    }

    /**
     * Переносит подтверждённый orderId из outbox-строки в позицию (доставка могла
     * быть UNCERTAIN — id вернулся только на повторном цикле outbox worker).
     * НЕ пере-армирует заявку, если по этому orderId уже есть подтверждённая отмена.
     */
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
                    logger.warn { "Exchange SL for ${pos.ticker} permanently failed (${row.errorMessage}); triggering SL protection failure" }
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
                    logger.warn { "Exchange TP for ${pos.ticker} permanently failed (${row.errorMessage}); triggering SL protection failure" }
                    onSlProtectionFailed(pos)
                }
            }
        }
        if (dirty) positionRepo.save(pos)
    }

    /**
     * Проверяет статус выставленных защитных заявок через verifyOrder: исполнена →
     * финализация закрытия (STOP_LOSS/TAKE_PROFIT); отменена/отклонена → заявки
     * больше нет, уровень будет перевыставлен.
     */
    private suspend fun checkProtectionFills(pos: Position) {
        pos.slOrderId?.let { id ->
            val ex = alorClient.verifyOrder(id, portfolio = portfolioResolver(pos.accountId)) ?: return@let
            if (isFilledStatus(ex)) {
                pos.slOrderId = null
                pos.slOrderPrice = null
                pos.slPendingReplace = false
                positionRepo.save(pos)
                applyExchangeProtectionClose(pos, ex, CloseReason.STOP_LOSS)
                return
            }
            if (isGoneStatus(ex)) {
                pos.slOrderId = null
                pos.slOrderPrice = null
                pos.slPendingReplace = false
                logger.warn { "Exchange SL order $id for ${pos.ticker} gone (${ex.status}); will re-place" }
            }
        }
        pos.tpOrderId?.let { id ->
            if (pos.status != PositionStatus.OPEN) return@let
            val ex = alorClient.verifyOrder(id, portfolio = portfolioResolver(pos.accountId)) ?: return@let
            if (isFilledStatus(ex)) {
                pos.tpOrderId = null
                pos.tpOrderPrice = null
                pos.tpPendingReplace = false
                positionRepo.save(pos)
                applyExchangeProtectionClose(pos, ex, CloseReason.TAKE_PROFIT)
                return
            }
            if (isGoneStatus(ex)) {
                pos.tpOrderId = null
                pos.tpOrderPrice = null
                pos.tpPendingReplace = false
                logger.warn { "Exchange TP order $id for ${pos.ticker} gone (${ex.status}); will re-place" }
            }
        }
        positionRepo.save(pos)
    }

    /**
     * Финализация закрытия по исполнению биржевой защитной заявки: если локальный
     * close-ордер был «в полёте» — снимаем его (позицию уже закрыла защитная
     * заявка), снимаем контр-заявку и применяем исполнение.
     */
    private suspend fun applyExchangeProtectionClose(
        pos: Position,
        execution: AlorClient.OrderExecution,
        reason: CloseReason,
    ) {
        val filled = execution.filledQuantity.coerceIn(0, pos.quantity)
        if (filled <= 0) return
        if (pos.pendingClose && pos.closeOrderId != null) {
            val positionId = pos.id
            if (positionId != null) {
                orderOutboxService.placeCancelOrder(positionId, pos.closeOrderId!!, accountId = pos.accountId)
                logger.info {
                    "Protection $reason closed ${pos.ticker} first — cancelling pending close order ${pos.closeOrderId}"
                }
            }
            pos.closeOrderId = null
            pos.pendingClose = false
            pos.closeReason = null
        }
        cancelProtectionOrders(pos)
        applyCloseExecution(pos, filled, execution.avgPrice ?: pos.currentPrice ?: pos.entryPrice, reason)
    }

    /**
     * Завершает перевыставление заявки: снимает старую (только после подтверждения
     * отмены!) и очищает флаг — новую выставят [attachProtectionOrders]/[reconcileProtectionOrders].
     * При UNCERTAIN отмены — ждёт следующего цикла (защита от двойного стопа/тейка).
     */
    private suspend fun finishProtectionReplacement(pos: Position) {
        var dirty = false

        if (pos.slPendingReplace) {
            val oldId = pos.slOrderId
            if (oldId == null) {
                pos.slPendingReplace = false
                dirty = true
            } else {
                // P1: стабильный idempotency-ключ — ретраи при UNKNOWN/UNCERTAIN идут с
                // тем же ключом, биржа дедуплицирует повторную отмену (CANCEL_UNKNOWN
                // безопасен: либо отменится, либо REJECTED -> verifyOrder разрешит).
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
                    if (ex != null && isFilledStatus(ex)) {
                        pos.slOrderId = null
                        pos.slOrderPrice = null
                        pos.slPendingReplace = false
                        positionRepo.save(pos)
                        applyExchangeProtectionClose(pos, ex, CloseReason.STOP_LOSS)
                        return
                    }
                }
                if (result == AlorClient.CancelResult.UNCERTAIN) return
                pos.slOrderId = null
                pos.slOrderPrice = null
                pos.slPendingReplace = false
                dirty = true
                logger.info { "SL replacement confirmed for ${pos.ticker} (old order $oldId cancelled)" }
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
                    if (ex != null && isFilledStatus(ex)) {
                        pos.tpOrderId = null
                        pos.tpOrderPrice = null
                        pos.tpPendingReplace = false
                        positionRepo.save(pos)
                        applyExchangeProtectionClose(pos, ex, CloseReason.TAKE_PROFIT)
                        return
                    }
                }
                if (result == AlorClient.CancelResult.UNCERTAIN) return
                pos.tpOrderId = null
                pos.tpOrderPrice = null
                pos.tpPendingReplace = false
                dirty = true
                logger.info { "TP replacement confirmed for ${pos.ticker} (old order $oldId cancelled)" }
            }
        }

        if (dirty) positionRepo.save(pos)
    }

    /**
     * Снимает биржевые защитные заявки позиции (через outbox — гарантированная
     * доставка отмены). Вызывается при любом закрытии позиции; [skip] — тип заявки,
     * которую НЕ снимаем (например, сработавшую — она уже исполнена).
     */
    suspend fun cancelProtectionOrders(
        pos: Position,
        skip: String? = null,
    ) {
        if (!protectionOrdersEnabled) return
        val positionId = pos.id ?: return
        if (pos.slOrderId != null && skip != "SL") {
            orderOutboxService.placeCancelOrder(positionId, pos.slOrderId!!, accountId = pos.accountId)
            logger.info { "Exchange SL cancel scheduled for ${pos.ticker} (order=${pos.slOrderId})" }
            pos.slOrderId = null
            pos.slOrderPrice = null
            pos.slPendingReplace = false
        }
        if (pos.tpOrderId != null && skip != "TP") {
            orderOutboxService.placeCancelOrder(positionId, pos.tpOrderId!!, accountId = pos.accountId)
            logger.info { "Exchange TP cancel scheduled for ${pos.ticker} (order=${pos.tpOrderId})" }
            pos.tpOrderId = null
            pos.tpOrderPrice = null
            pos.tpPendingReplace = false
        }
    }

    /**
     * Жива ли outbox-строка защитной заявки (нельзя выставлять дубликат):
     * - PENDING / FAILED с запасом ретраев — доставка ещё «в полёте»;
     * - SENT без подтверждённой отмены — заявка жива на бирже;
     * - SENT с подтверждённой отменой / FAILED с исчерпанными ретраями — заявки
     *   больше нет, новую выставлять можно.
     */
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
        execution.status.contains("fill", ignoreCase = true) && execution.filledQuantity > 0

    private fun isGoneStatus(execution: AlorClient.OrderExecution): Boolean =
        execution.status.contains("cancel", ignoreCase = true) ||
            execution.status.contains("reject", ignoreCase = true) ||
            execution.status.contains("expire", ignoreCase = true)

    /**
     * Применяет ExecutionReport из WebSocket: фиксация фактического исполнения
     * входа (pendingEntry), закрытия (pendingClose) и биржевых защитных заявок
     * (SL/TP-фил исполняется биржей — позиция финализируется с reason
     * STOP_LOSS / TAKE_PROFIT), с учётом partial fills.
     *
     * @return true, если отчёт обработан ядром (вход/закрытие/защитная заявка);
     *   false — отчёт не относится к pending-состоянию (например, обычный fill
     *   акции — обрабатывает TradingBotService).
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

        // Исполнение биржевой защитной заявки (SL/TP) — позицию закрыла биржа.
        // Проверяем РАНЬШЕ pendingClose: стоп-заявка могла сработать, пока
        // локальный close-ордер ещё «в полёте».
        if (orderId == pos.slOrderId || orderId == pos.tpOrderId) {
            val reason = if (orderId == pos.slOrderId) CloseReason.STOP_LOSS else CloseReason.TAKE_PROFIT
            if (orderId == pos.slOrderId) {
                pos.slOrderId = null
                pos.slOrderPrice = null
                pos.slPendingReplace = false
            } else {
                pos.tpOrderId = null
                pos.tpOrderPrice = null
                pos.tpPendingReplace = false
            }
            positionRepo.save(pos)
            applyExchangeProtectionClose(
                pos,
                AlorClient.OrderExecution(report.status.name, report.filledQty, fillPrice),
                reason,
            )
            return true
        }

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
                attachProtectionOrders(pos)
                logger.info { "WS entry fill applied for ${pos.ticker}: order=$orderId qty=${pos.quantity} @ $fillPrice" }
            }
            // PARTIALLY_FILLED вход — оставляем реконсилятору (verifyOrder даст кумулятивный fill).
            return true
        }

        // Подтверждение закрытия (pendingClose).
        if (pos.pendingClose) {
            applyCloseExecution(pos, report.filledQty, fillPrice, pos.closeReason ?: CloseReason.EXECUTION_FILL)
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
        reason: CloseReason,
    ) {
        val orderId = pos.closeOrderId ?: return
        val execution = alorClient.verifyOrder(orderId, expectedPrice = expectedPrice, portfolio = portfolioResolver(pos.accountId))
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
        when (val result = alorClient.getPositions(portfolio = portfolioResolver(pos.accountId))) {
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
        reason: CloseReason,
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
        // Защитные SL/TP стояли на полный объём — снимаем и перевыставляем на остаток.
        cancelProtectionOrders(pos)
        positionRepo.save(pos)
        attachProtectionOrders(pos)
        meterRegistry.counter("$metricPrefix.partial_close", Tags.of("ticker", pos.ticker)).increment()
        logger.warn {
            "PARTIAL close ${pos.ticker}: closed=$filled remainder=${pos.quantity} @ $avg " +
                "realized=$partialPnl ₽ (cumulative=${pos.realizedPnl}); remainder will be re-closed"
        }
    }

    /**
     * Полное закрытие: P&L = realizedPnl (partial) + P&L остатка.
     *
     * Атомарный переход в закрытое состояние ([PositionRepository.transitionToClosed]):
     * из конкурирующих вызовов (claim-поток + сверяющие потоки по тому же close-ордеру)
     * побочные эффекты (recordPositionClosed / onPositionClosed / снятие защитных заявок)
     * выполняет только тот, чей UPDATE перевёл строку из OPEN.
     */
    private suspend fun finalizeClosePosition(
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
        if (!positionRepo.transitionToClosed(positionId, targetStatus, closePrice, reason, totalPnl)) {
            logger.warn { "Finalize skip ${pos.ticker}: position already closed by another path" }
            return
        }
        pos.status = targetStatus
        pos.closedAt = LocalDateTime.now()
        pos.closePrice = closePrice
        pos.closeReason = reason
        pos.pnl = totalPnl
        pos.pendingClose = false
        pos.closeOrderId = null
        cancelProtectionOrders(pos)
        tradeEventService.recordPositionClosed(pos, reason.code)
        onPositionClosed(pos)
        positionRepo.releaseEntry(pos.ticker, pos.accountId)
        meterRegistry.counter("$metricPrefix.position.closed", Tags.of("ticker", pos.ticker, "reason", reason.code)).increment()
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
                val execution = alorClient.verifyOrder(outbox.alorOrderId, portfolio = portfolioResolver(pos.accountId)) ?: return
                if (execution.status.contains("reject") || execution.status.contains("cancel")) {
                    abandonEntry(pos, CloseReason.ENTRY_REJECTED)
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
                    if (cancelled != AlorClient.CancelResult.CONFIRMED) return // отмена не подтверждена → ждём следующего цикла
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

                // Полное исполнение → фиксируем вход.
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
                confirmCloseFill(pos, pos.currentPrice ?: pos.entryPrice, pos.closeReason ?: CloseReason.RECONCILIATION)
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
        reason: CloseReason,
    ) {
        logger.warn { "Entry for ${pos.ticker} abandoned: $reason" }
        pos.pendingEntry = false
        pos.status = PositionStatus.CLOSED
        pos.closeReason = reason
        pos.closedAt = LocalDateTime.now()
        positionRepo.save(pos)
        positionRepo.releaseEntry(pos.ticker, pos.accountId)
        meterRegistry.counter("$metricPrefix.entry.abandoned", Tags.of("ticker", pos.ticker, "reason", reason.code)).increment()
    }
}
