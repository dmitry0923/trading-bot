package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorClient.ReconcileResult
import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsConnectionEvent
import com.trading.bot.client.WsConnectionStatus
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.event.TradingHaltedEvent
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Полная State Reconciliation локального стейта с биржей (Alor REST).
 *
 * Потребляет [WsConnectionEvent] из [WebSocketManager]:
 * - при КАЖДОМ переподключении (CONNECTED любого потока) сверяет открытые заявки,
 *   позиции и сделки через [AlorClient.getOpenOrders]/[AlorClient.getPositions]/[AlorClient.getRecentTrades].
 *   Это закрывает окно потери данных при обрыве WS: бот узнаёт о fill'ах, которых
 *   не видел по WebSocket, и не торгует на «фантомных» позициях.
 * - при DISCONNECTED фиксирует метрику окна рассинхрона (данные уже частично
 *   устарели — остальная система может переключиться на fallback-поллинг).
 *
 * Правила сверки (fail-safe — при недоступности REST локальный стейт НЕ мутируется):
 * - [ReconcileResult.Failed] по любой из трёх выборок → состояние биржи НЕИЗВЕСТНО →
 *   сверка прерывается И торговля останавливается (hard halt, EXEC-006): нельзя
 *   открывать новые позиции вслепую.
 * - Локальная OPEN-позиция без позиции на бирже и без «рабочих» заявок по тикеру —
 *   «фантомная»: закрыта на бирже во время разрыва, WS-fill потерян → помечается CLOSED.
 * - Локальная OPEN-позиция с расхождением qty (например, частичное закрытие в окне
 *   разрыва) → quantity приводится к биржевому значению.
 * - Расхождение направления (локальный LONG, на бирже SHORT) — критический
 *   рассинхрон: позиция реально открыта на бирже в противоположную сторону.
 *   НЕ закрывается локально (мы не можем подтвердить исполнение) → статус
 *   [PositionStatus.RECONCILIATION_REQUIRED] + hard halt (EXEC-007) до ручного
 *   вмешательства.
 * - Позиция на бирже, которой нет в локальном OPEN-наборе — критический рассинхрон
 *   (бот мог открыть второй вход по тому же тикеру) → алерт [TradingHaltedEvent].
 * - pendingEntry/pendingClose позиции со «живыми» заявками на бирже не трогаются —
 *   их разрешает существующий outbox-реконсилятор ([TradingBotService]/[com.trading.bot.application.FuturesTradingBotService]).
 *
 * Метрики:
 * - alor.reconcile.run{closed,adjusted,unknown} — завершённая сверка
 * - alor.reconcile.aborted{reason=FETCH_FAILED} — REST недоступен (+ hard halt)
 * - alor.reconcile.discrepancy{kind, ticker} — PHANTOM / DIRECTION_MISMATCH / UNKNOWN_POSITION
 * - alor.reconcile.fetch_error{kind} — ошибка отдельной выборки (в AlorClient)
 * - alor.ws.disconnect_window{stream} — зафиксирован разрыв WS
 */
@Service
class StateReconciliationService(
    private val webSocketManager: WebSocketManager,
    private val alorClient: AlorClient,
    private val positionRepo: PositionRepository,
    private val tradingConfig: TradingConfig,
    private val alorConfig: AlorConfig,
    private val eventPublisher: TradingEventPublisher,
    private val meterRegistry: MeterRegistry,
    private val tradingAccountService: TradingAccountService,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastReconcileAtMs = AtomicLong(0)

    @PostConstruct
    fun start() {
        scope.launch {
            webSocketManager.events.collect { event ->
                try {
                    handleEvent(event)
                } catch (e: Exception) {
                    logger.error(e) { "State reconciliation handler error for ${event.stream}" }
                }
            }
        }
    }

    private suspend fun handleEvent(event: WsConnectionEvent) {
        if (!alorConfig.wsReconcileOnReconnect) return
        when (event.status) {
            WsConnectionStatus.CONNECTED -> {
                maybeReconcile(event)
            }

            WsConnectionStatus.DISCONNECTED -> {
                meterRegistry.counter("alor.ws.disconnect_window", Tags.of("stream", event.stream.name)).increment()
            }
        }
    }

    /**
     * Сверка с дедупликацией: ORDERS и QUOTES переподключаются отдельными
     * соединениями — в течение короткого окна (5 c) сверка выполняется один раз.
     */
    private suspend fun maybeReconcile(event: WsConnectionEvent) {
        val now = System.currentTimeMillis()
        val last = lastReconcileAtMs.get()
        if (now - last < RECONCILE_DEBOUNCE_MS) {
            logger.info { "State reconciliation skipped (debounced ${now - last}ms ago, stream=${event.stream})" }
            return
        }
        if (!lastReconcileAtMs.compareAndSet(last, now)) return
        reconcile()
    }

    /**
     * Полная сверка: REST-портфель (заявки, позиции, сделки) против локальных позиций.
     *
     * Multi-account (roadmap v2.2): сверяется каждый портфель отдельно — конфиг-портфель
     * (legacy, accountId = null) и портфель каждого включённого аккаунта. При совпадении
     * портфелей они сверяют свои локальные наборы позиций (account_id = null vs аккаунта).
     * Сбой REST одного портфеля НЕ трогает его локальный стейт (fail-safe), остальные
     * портфели сверяются независимо.
     */
    suspend fun reconcile() {
        if (tradingConfig.mode != "LIVE") {
            logger.info { "State reconciliation skipped (mode=${tradingConfig.mode})" }
            return
        }
        val start = System.currentTimeMillis()
        val units = reconcileUnits()
        logger.info { "State reconciliation started (${units.size} portfolio(s): ${units.joinToString { it.portfolio }})" }

        var closed = 0
        var adjusted = 0
        var unknown = 0
        var halted = false
        for (unit in units) {
            val outcome = reconcilePortfolio(unit)
            closed += outcome.closed
            adjusted += outcome.adjusted
            unknown += outcome.unknown
            halted = halted || outcome.halted
        }

        if (halted) {
            eventPublisher.publishTradingHalted(TradingHaltedEvent(reason = "STATE_DESYNC"))
        }

        meterRegistry
            .timer("alor.reconcile.duration")
            .record(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS)
        meterRegistry
            .counter("alor.reconcile.run", Tags.of("closed", "$closed", "adjusted", "$adjusted", "unknown", "$unknown"))
            .increment()
        logger.info {
            "State reconciliation finished: phantom=$closed qtyAdjusted=$adjusted unknownExchange=$unknown " +
                "in ${System.currentTimeMillis() - start}ms"
        }
    }

    /**
     * Единицы сверки (multi-account): legacy конфиг-портфель (accountId = null) +
     * портфель каждого включённого аккаунта. Дубли портфелей не схлопываются —
     * каждый сверяет свой набор локальных позиций.
     */
    private suspend fun reconcileUnits(): List<ReconcileUnit> {
        val units = mutableListOf(ReconcileUnit(portfolio = alorConfig.portfolio, accountId = null))
        for (account in tradingAccountService.findEnabled()) {
            units += ReconcileUnit(portfolio = account.alorPortfolio, accountId = account.id)
        }
        return units
    }

    private suspend fun reconcilePortfolio(unit: ReconcileUnit): ReconcileOutcome {
        val portfolio = unit.portfolio
        val ordersResult = alorClient.getOpenOrders(portfolio = portfolio)
        val positionsResult = alorClient.getPositions(portfolio)
        val tradesResult = alorClient.getRecentTrades(portfolio)

        if (
            ordersResult is ReconcileResult.Failed ||
            positionsResult is ReconcileResult.Failed ||
            tradesResult is ReconcileResult.Failed
        ) {
            logger.error {
                "State reconciliation ABORTED for portfolio '$portfolio': REST fetch failed " +
                    "(orders=${ordersResult is ReconcileResult.Failed}, " +
                    "positions=${positionsResult is ReconcileResult.Failed}, " +
                    "trades=${tradesResult is ReconcileResult.Failed}); " +
                    "no local state mutated (fail-safe), trading halted (EXEC-006: exchange state UNKNOWN)"
            }
            meterRegistry.counter("alor.reconcile.aborted", Tags.of("reason", "FETCH_FAILED")).increment()
            return ReconcileOutcome(halted = true)
        }

        val orders = (ordersResult as ReconcileResult.Ok).items
        val positions = (positionsResult as ReconcileResult.Ok).items
        val trades = (tradesResult as ReconcileResult.Ok).items

        val workingOrdersByTicker =
            orders
                .filter { isWorkingOrder(it.status) }
                .groupBy { it.ticker.uppercase() }
        val exchangeQtyByTicker =
            positions
                .filter { it.qty != 0L }
                .associate { it.ticker.uppercase() to it.qty }
        val localOpen = positionRepo.findOpenByAccount(unit.accountId)

        var closed = 0
        var adjusted = 0
        var unknown = 0
        var reconciliationRequired = 0

        for (pos in localOpen) {
            try {
                val key = pos.ticker.uppercase()
                val exchangeQty = exchangeQtyByTicker[key] ?: 0L
                val workingForTicker = workingOrdersByTicker[key].orEmpty()

                if (exchangeQty == 0L) {
                    if (workingForTicker.isNotEmpty()) {
                        logger.warn {
                            "Reconcile ${pos.ticker}: no exchange position but ${workingForTicker.size} " +
                                "working order(s) — leaving as-is (outbox reconciler will settle)"
                        }
                        meterRegistry.counter("alor.reconcile.orders_open", Tags.of("ticker", key)).increment()
                        continue
                    }
                    when {
                        pos.pendingClose -> {
                            logger.error {
                                "Reconcile ${pos.ticker}: exchange flat, no working order, pendingClose — " +
                                    "missed fill during WS gap -> marking CLOSED"
                            }
                            finalizePhantom(pos, "RECONCILE_CLOSED_ON_EXCHANGE")
                            closed++
                        }

                        pos.pendingEntry -> {
                            logger.warn {
                                "Reconcile ${pos.ticker}: exchange flat, no working order, pendingEntry — " +
                                    "leaving for outbox reconciler (entry may not have happened)"
                            }
                            meterRegistry.counter("alor.reconcile.entry_unconfirmed", Tags.of("ticker", key)).increment()
                        }

                        else -> {
                            logger.error {
                                "Reconcile ${pos.ticker}: PHANTOM position (exchange flat, no working orders) — " +
                                    "closed on exchange during WS gap -> marking CLOSED"
                            }
                            finalizePhantom(pos, "RECONCILE_PHANTOM")
                            closed++
                        }
                    }
                    continue
                }

                if (pos.pendingClose || pos.pendingEntry) continue

                val exchangeDirection =
                    if (exchangeQty > 0) {
                        PositionDirection.LONG
                    } else {
                        PositionDirection.SHORT
                    }
                if (exchangeDirection != pos.direction) {
                    logger.error {
                        "Reconcile ${pos.ticker}: direction mismatch local=${pos.direction} " +
                            "exchange=$exchangeDirection (qty=$exchangeQty) -> RECONCILIATION_REQUIRED " +
                            "(not closed: exchange position real, manual intervention required)"
                    }
                    markReconciliationRequired(pos)
                    meterRegistry.counter("alor.reconcile.discrepancy", Tags.of("kind", "DIRECTION_MISMATCH", "ticker", key)).increment()
                    reconciliationRequired++
                    continue
                }
                val exchangeAbsQty = kotlin.math.abs(exchangeQty)
                if (exchangeAbsQty != pos.quantity.toLong()) {
                    logger.warn {
                        "Reconcile ${pos.ticker}: qty mismatch local=${pos.quantity} " +
                            "exchange=$exchangeAbsQty (partial close during WS gap) -> adjusting"
                    }
                    val newQty = exchangeAbsQty.toInt()
                    pos.quantity = newQty
                    // P1: риск-поля пересчитываются после изменения qty — marginUsed
                    // линейно зависит от количества (GO * qty), без рекалка exposure
                    // и drawdown-лимиты считаются от устаревшей маржи.
                    pos.goPerContract?.let { pos.marginUsed = it.multiply(BigDecimal(newQty)) }
                    positionRepo.save(pos)
                    meterRegistry.counter("alor.reconcile.discrepancy", Tags.of("kind", "QTY_ADJUSTED", "ticker", key)).increment()
                    adjusted++
                }
            } catch (e: Exception) {
                logger.error(e) { "Reconcile error for ${pos.id}/${pos.ticker}" }
            }
        }

        val localKeys = localOpen.map { it.ticker.uppercase() }.toSet()
        for ((key, qty) in exchangeQtyByTicker) {
            if (key in localKeys) continue
            logger.error {
                "Reconcile portfolio '$portfolio': UNKNOWN exchange position $key qty=$qty (not tracked locally) — " +
                    "manual intervention required (bot may double-open)"
            }
            meterRegistry.counter("alor.reconcile.discrepancy", Tags.of("kind", "UNKNOWN_POSITION", "ticker", key)).increment()
            unknown++
        }

        return ReconcileOutcome(
            closed = closed,
            adjusted = adjusted,
            unknown = unknown,
            halted = closed > 0 || unknown > 0 || reconciliationRequired > 0,
        )
    }

    /**
     * Помечает локальную позицию закрытой как «фантомную» (на бирже её больше нет).
     * Цена закрытия неизвестна — P&L не пересчитываем, только снимаем позицию с контроля.
     */
    private suspend fun finalizePhantom(
        pos: Position,
        reason: String,
    ) {
        pos.status = PositionStatus.CLOSED
        pos.closedAt = LocalDateTime.now()
        pos.closeReason = reason
        pos.pendingClose = false
        pos.pendingEntry = false
        positionRepo.save(pos)
        meterRegistry.counter("alor.reconcile.discrepancy", Tags.of("kind", "PHANTOM", "ticker", pos.ticker.uppercase())).increment()
    }

    /**
     * Direction mismatch (EXEC-007): позиция реально открыта на бирже в
     * противоположную сторону, локальное «закрытие» без подтверждённого
     * исполнения опасно (бот потеряет контроль и может удвоить позицию).
     * Статус [PositionStatus.RECONCILIATION_REQUIRED] — вне управления ботом,
     * требуется ручное вмешательство.
     */
    private suspend fun markReconciliationRequired(pos: Position) {
        pos.status = PositionStatus.RECONCILIATION_REQUIRED
        pos.pendingClose = false
        pos.pendingEntry = false
        positionRepo.save(pos)
    }

    /**
     * «Рабочая» заявка — ещё не в терминальном статусе (может исполниться позже).
     */
    private fun isWorkingOrder(status: String): Boolean {
        val s = status.lowercase()
        return !(
            s.contains("fill") ||
                s.contains("cancel") ||
                s.contains("reject") ||
                s.contains("expire") ||
                s.contains("done")
        )
    }

    private companion object {
        const val RECONCILE_DEBOUNCE_MS = 5_000L
    }

    /** Портфель + аккаунт для отдельной сверки (accountId = null → legacy). */
    private data class ReconcileUnit(
        val portfolio: String,
        val accountId: Long?,
    )

    /** Итог сверки одного портфеля. */
    private data class ReconcileOutcome(
        val closed: Int = 0,
        val adjusted: Int = 0,
        val unknown: Int = 0,
        val halted: Boolean = false,
    )
}
