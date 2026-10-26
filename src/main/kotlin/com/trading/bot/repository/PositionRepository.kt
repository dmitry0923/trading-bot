package com.trading.bot.repository

import com.trading.bot.infrastructure.db.bindOrNull
import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.CloseReason
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * R2DBC-репозиторий позиций. Все методы suspend: вызываются из корутин.
 *
 * Время: наивные `TIMESTAMP` в БД — бизнес-время МСК (как `DrawdownProtectionService`).
 * [transitionToClosed] пишет `closed_at` по [clock] (по умолчанию `Clock.system(Europe/Moscow)`),
 * иначе `LocalDateTime.now()` брал бы зону JVM (в Docker — UTC) и «свежие» закрытия выпадали
 * бы из окна авто-стопа и из «сегодня» дневных лимитов.
 */
@Repository
class PositionRepository(
    private val databaseClient: DatabaseClient,
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Moscow")),
) {
    private fun toPosition(row: Row): Position =
        Position(
            id = row.get("id", Long::class.javaObjectType),
            cumulativeSlFillQty = row.get("cumulative_sl_fill_qty", Int::class.javaObjectType) ?: 0,
            cumulativeTpFillQty = row.get("cumulative_tp_fill_qty", Int::class.javaObjectType) ?: 0,
            ticker = row.require("ticker", String::class.java),
            direction = enumValueOf(row.require("direction", String::class.java)),
            quantity = row.require("quantity", Int::class.javaObjectType),
            entryPrice = row.require("entry_price", BigDecimal::class.java),
            currentPrice = row.get("current_price", BigDecimal::class.java),
            closePrice = row.get("close_price", BigDecimal::class.java),
            stopLoss = row.get("stop_loss", BigDecimal::class.java),
            takeProfit = row.get("take_profit", BigDecimal::class.java),
            instrumentType = enumValueOf(row.require("instrument_type", String::class.java)),
            leverage = row.get("leverage", BigDecimal::class.java),
            goPerContract = row.get("go_per_contract", BigDecimal::class.java),
            marginUsed = row.get("margin_used", BigDecimal::class.java),
            liquidationPrice = row.get("liquidation_price", BigDecimal::class.java),
            variationMargin = row.get("variation_margin", BigDecimal::class.java) ?: BigDecimal.ZERO,
            stopLossPoints = row.get("stop_loss_points", Int::class.javaObjectType),
            trailingStopPrice = row.get("trailing_stop_price", BigDecimal::class.java),
            pnl = row.get("pnl", BigDecimal::class.java),
            status = enumValueOf(row.require("status", String::class.java)),
            alorOrderId = row.get("alor_order_id", String::class.java),
            closeOrderId = row.get("close_order_id", String::class.java),
            slOrderId = row.get("sl_order_id", String::class.java),
            tpOrderId = row.get("tp_order_id", String::class.java),
            slOrderPrice = row.get("sl_order_price", BigDecimal::class.java),
            tpOrderPrice = row.get("tp_order_price", BigDecimal::class.java),
            slPendingReplace = row.get("sl_pending_replace", Boolean::class.javaObjectType) ?: false,
            tpPendingReplace = row.get("tp_pending_replace", Boolean::class.javaObjectType) ?: false,
            slCancelPending = row.get("sl_cancel_pending", Boolean::class.javaObjectType) ?: false,
            tpCancelPending = row.get("tp_cancel_pending", Boolean::class.javaObjectType) ?: false,
            closeCancelPending = row.get("close_cancel_pending", Boolean::class.javaObjectType) ?: false,
            pendingClose = row.get("pending_close", Boolean::class.javaObjectType) ?: false,
            pendingEntry = row.get("pending_entry", Boolean::class.javaObjectType) ?: false,
            realizedPnl = row.get("realized_pnl", BigDecimal::class.java) ?: BigDecimal.ZERO,
            closeReason = CloseReason.from(row.get("close_reason", String::class.java)),
            cumulativeCloseFillQty = row.get("cumulative_close_fill_qty", Int::class.javaObjectType) ?: 0,
            openedAt = row.require("opened_at", LocalDateTime::class.java),
            closedAt = row.get("closed_at", LocalDateTime::class.java),
            cycleId = row.get("cycle_id", String::class.java),
            accountId = row.get("account_id", Long::class.javaObjectType),
            fundingUnknown = row.get("funding_unknown", Boolean::class.javaObjectType) ?: false,
        )

    suspend fun findByStatus(status: PositionStatus): List<Position> {
        val sql = "SELECT * FROM positions WHERE status = :status ORDER BY opened_at DESC"
        return databaseClient
            .sql(sql)
            .bind("status", status.name)
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /**
     * Возвращает только уникальные тикеры открытых позиций (без загрузки всех строк).
     * Используется в pollMarketData для определения тикеров, требующих опроса.
     */
    suspend fun findOpenTickersDistinct(): List<String> {
        val sql = "SELECT DISTINCT ticker FROM positions WHERE status = 'OPEN'"
        return databaseClient
            .sql(sql)
            .map { row, _ -> row.get("ticker", String::class.java) ?: error("DISTINCT ticker column is null") }
            .all()
            .collectList()
            .awaitSingle()
    }

    /**
     * Есть ли хотя бы одна открытая позиция тикера (execution interlock, P1-a):
     * risk-reducing ордеры (close/SL/TP) разрешены при наличии открытой позиции,
     * даже если тикер revoked / build SHA сменился.
     */
    suspend fun hasOpenPosition(ticker: String): Boolean {
        val sql = "SELECT EXISTS(SELECT 1 FROM positions WHERE status = 'OPEN' AND ticker = :ticker) AS present"
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .map { row, _ -> row.get("present", Boolean::class.javaObjectType) ?: false }
            .one()
            .awaitSingleOrNull()
            ?: false
    }

    /**
     * Открытые позиции конкретного тикера (без загрузки ВСЕХ открытых позиций).
     * Используется в onPriceChanged для мониторинга одного тикера за тик.
     */
    suspend fun findByStatusAndTicker(
        status: PositionStatus,
        ticker: String,
    ): List<Position> {
        val sql = "SELECT * FROM positions WHERE status = :status AND ticker = :ticker ORDER BY opened_at DESC"
        return databaseClient
            .sql(sql)
            .bind("status", status.name)
            .bind("ticker", ticker)
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /**
     * Только pending-позиции (pendingEntry или pendingClose) — для реконсиляции.
     * При 1000+ OPEN-позиций подмножество pending обычно ≤ 5-10 строк.
     */
    suspend fun findPending(): List<Position> {
        val sql =
            """
            SELECT * FROM positions
            WHERE status = 'OPEN' AND (pending_entry = true OR pending_close = true)
            ORDER BY opened_at DESC
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /**
     * Открытые акции/валюты (исключая фьючерсы) — для forceCloseAll.
     * Фильтрация по instrument_type выполняется на стороне БД,
     * а не в памяти.
     */
    suspend fun findOpenStocks(): List<Position> {
        val sql = "SELECT * FROM positions WHERE status = 'OPEN' AND instrument_type != 'FUTURES' ORDER BY opened_at DESC"
        return databaseClient
            .sql(sql)
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun findOpenCount(): Int {
        val sql = "SELECT COUNT(*) AS cnt FROM positions WHERE status = 'OPEN'"
        return databaseClient
            .sql(sql)
            .map { row, _ -> row.get("cnt", Long::class.javaObjectType)?.toInt() ?: 0 }
            .one()
            .awaitSingleOrNull()
            ?: 0
    }

    /** Количество открытых позиций аккаунта (multi-account); accountId = null → legacy без привязки. */
    suspend fun findOpenCountByAccount(accountId: Long?): Int {
        val sql =
            if (accountId == null) {
                "SELECT COUNT(*) AS cnt FROM positions WHERE status = 'OPEN' AND account_id IS NULL"
            } else {
                "SELECT COUNT(*) AS cnt FROM positions WHERE status = 'OPEN' AND account_id = :accountId"
            }
        val spec = databaseClient.sql(sql)
        val finalSpec = if (accountId != null) spec.bind("accountId", accountId) else spec
        return finalSpec
            .map { row, _ -> row.get("cnt", Long::class.javaObjectType)?.toInt() ?: 0 }
            .one()
            .awaitSingleOrNull()
            ?: 0
    }

    /** Открытые позиции аккаунта (multi-account); accountId = null → legacy без привязки. */
    suspend fun findOpenByAccount(accountId: Long?): List<Position> {
        val sql =
            if (accountId == null) {
                "SELECT * FROM positions WHERE status = 'OPEN' AND account_id IS NULL ORDER BY opened_at DESC"
            } else {
                "SELECT * FROM positions WHERE status = 'OPEN' AND account_id = :accountId ORDER BY opened_at DESC"
            }
        val spec = databaseClient.sql(sql)
        val finalSpec = if (accountId != null) spec.bind("accountId", accountId) else spec
        return finalSpec
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /**
     * Все позиции аккаунта (включая закрытые). FK fk_positions_account ссылается на
     * trading_accounts без ON DELETE CASCADE — удаление аккаунта с любой позицией
     * нарушает целостность, поэтому блокируется на уровне API.
     */
    suspend fun countByAccount(accountId: Long): Int =
        databaseClient
            .sql("SELECT COUNT(*) AS cnt FROM positions WHERE account_id = :accountId")
            .bind("accountId", accountId)
            .map { row, _ -> row.get("cnt", Long::class.javaObjectType)?.toInt() ?: 0 }
            .one()
            .awaitSingleOrNull()
            ?: 0

    suspend fun findById(id: Long): Position {
        val sql = "SELECT * FROM positions WHERE id = :id"
        return databaseClient
            .sql(sql)
            .bind("id", id)
            .map { row, _ -> toPosition(row) }
            .one()
            .awaitSingle()
    }

    /**
     * Все позиции цикла (lineage): открытие ведётся на signal цикла, поэтому
     * cycle_id связывает сделку с полной цепочкой решения. Используется
     * реконструкцией по cycleId ([com.trading.bot.service.LineageService]).
     */
    suspend fun findByCycleId(cycleId: String): List<Position> =
        databaseClient
            .sql("SELECT * FROM positions WHERE cycle_id = :cycleId ORDER BY opened_at ASC")
            .bind("cycleId", cycleId)
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()

    /**
     * Атомарный claim позиции на закрытие (EXEC-001).
     *
     * Одиночный UPDATE с условиями `status = 'OPEN' AND pending_close = false` —
     * PostgreSQL сериализует конкурентные UPDATE'ы по одной строке (row lock) и
     * пере-проверяет WHERE после блокировки: только один поток получит
     * rowsUpdated == 1 и право ставить close-ордер, остальные — 0 и сразу выходят
     * (без создания второго ордера).
     *
     * @return true — claim получен (1 строка), false — позиция уже закрывается
     *   другим потоком / уже закрыта.
     */
    suspend fun claimForClose(id: Long): Boolean {
        val sql =
            """
            UPDATE positions SET pending_close = true
            WHERE id = :id AND status = 'OPEN' AND pending_close = false
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L
    }

    /**
     * Атомарный переход позиции в закрытое состояние (EXEC-001, защита от двойной
     * финализации одного close-ордера). Конкурентные [transitionToClosed]-вызовы
     * (claim-поток и сверяющие потоки) за один UPDATE переводят строку только один раз:
     * rowsUpdated == 1 только у первого, остальные получают 0 и пропускают побочные
     * эффекты (recordPositionClosed / onPositionClosed / снятие защитных заявок).
     *
     * @return true — переход выполнен этим вызовом (1 строка), false — позиция уже
     *   не в состоянии OPEN (закрыта другим потоком).
     */
    suspend fun transitionToClosed(
        id: Long,
        status: PositionStatus,
        closePrice: BigDecimal,
        closeReason: CloseReason,
        pnl: BigDecimal,
        cumulativeCloseFillQty: Int = 0,
    ): Boolean {
        val sql =
            """
            UPDATE positions SET
                status = :status, closed_at = :closedAt, close_price = :closePrice,
                close_reason = :closeReason, pnl = :pnl, pending_close = false, close_order_id = NULL,
                cumulative_close_fill_qty = :cumulativeCloseFillQty
            WHERE id = :id AND status = 'OPEN'
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("id", id)
            .bind("status", status.name)
            .bind("closedAt", LocalDateTime.now(clock))
            .bind("closePrice", closePrice)
            .bind("closeReason", closeReason.code)
            .bind("pnl", pnl)
            .bind("cumulativeCloseFillQty", cumulativeCloseFillQty)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L
    }

    /**
     * Освобождение claim после ОПРЕДЕЛЁННОГО отказа биржи (close-ордер не создан):
     * позиция снова становится закрываемой. Условие `close_order_id IS NULL`
     * гарантирует, что при UNCERTAIN-доставке (ордер мог дойти до биржи, closeOrderId
     * ещё не известен) claim НЕ снимается — реконсилятор доводит состояние до конца.
     */
    suspend fun releaseCloseClaim(id: Long): Boolean {
        val sql =
            """
            UPDATE positions SET pending_close = false
            WHERE id = :id AND status = 'OPEN' AND pending_close = true AND close_order_id IS NULL
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L
    }

    suspend fun findByAlorOrderId(alorOrderId: String): Position? {
        val sql = "SELECT * FROM positions WHERE alor_order_id = :alorOrderId"
        return databaseClient
            .sql(sql)
            .bind("alorOrderId", alorOrderId)
            .map { row, _ -> toPosition(row) }
            .one()
            .awaitSingleOrNull()
    }

    suspend fun findByCloseOrderId(closeOrderId: String): Position? {
        val sql = "SELECT * FROM positions WHERE close_order_id = :closeOrderId"
        return databaseClient
            .sql(sql)
            .bind("closeOrderId", closeOrderId)
            .map { row, _ -> toPosition(row) }
            .one()
            .awaitSingleOrNull()
    }

    suspend fun findBySlOrderId(slOrderId: String): Position? {
        val sql = "SELECT * FROM positions WHERE sl_order_id = :slOrderId"
        return databaseClient
            .sql(sql)
            .bind("slOrderId", slOrderId)
            .map { row, _ -> toPosition(row) }
            .one()
            .awaitSingleOrNull()
    }

    suspend fun findByTpOrderId(tpOrderId: String): Position? {
        val sql = "SELECT * FROM positions WHERE tp_order_id = :tpOrderId"
        return databaseClient
            .sql(sql)
            .bind("tpOrderId", tpOrderId)
            .map { row, _ -> toPosition(row) }
            .one()
            .awaitSingleOrNull()
    }

    suspend fun findAll(): List<Position> =
        databaseClient
            .sql("SELECT * FROM positions ORDER BY opened_at DESC")
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()

    /**
     * Закрытые позиции (status != OPEN) для экспорта ML-датасета (roadmap v2.4).
     * Необязательные фильтры: [ticker], [since] (по closedAt).
     */
    suspend fun findClosed(
        ticker: String? = null,
        since: LocalDateTime? = null,
    ): List<Position> {
        val conditions = mutableListOf("status != 'OPEN'")
        val params = mutableMapOf<String, Any>()
        ticker?.takeIf { it.isNotBlank() }?.let {
            conditions += "ticker = :ticker"
            params["ticker"] = it
        }
        since?.let {
            conditions += "closed_at >= :since"
            params["since"] = it
        }
        val where = conditions.joinToString(" AND ")
        val sql = "SELECT * FROM positions WHERE $where ORDER BY opened_at DESC"
        var spec = databaseClient.sql(sql)
        params.forEach { (name, value) -> spec = spec.bind(name, value) }
        return spec
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /**
     * Маркер FUNDING_UNKNOWN (P0/P1-аудит funding): LIVE-снапшот MOEX на один из
     * пережитых позицией клирингов отсутствовал → P&L посчитан без авторитетного
     * funding. Флаг пишется отдельным UPDATE (не через [transitionToClosed]) —
     * сигнатура закрытия стабильна для 15+ call-site'ов.
     */
    suspend fun setFundingUnknown(
        id: Long,
        fundingUnknown: Boolean,
    ) {
        val sql =
            """
            UPDATE positions SET funding_unknown = :fundingUnknown
            WHERE id = :id
            """.trimIndent()
        databaseClient
            .sql(sql)
            .bind("id", id)
            .bind("fundingUnknown", fundingUnknown)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun findClosedSince(since: LocalDateTime): List<Position> {
        val sql = "SELECT * FROM positions WHERE status != 'OPEN' AND closed_at >= :since ORDER BY closed_at DESC"
        return databaseClient
            .sql(sql)
            .bind("since", since)
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /** Закрытые позиции аккаунта с момента [since] (multi-account; accountId = null → legacy). */
    suspend fun findClosedByAccountSince(
        accountId: Long?,
        since: LocalDateTime,
    ): List<Position> {
        val sql =
            if (accountId == null) {
                "SELECT * FROM positions WHERE status != 'OPEN' AND account_id IS NULL AND closed_at >= :since ORDER BY closed_at DESC"
            } else {
                "SELECT * FROM positions WHERE status != 'OPEN' AND account_id = :accountId AND closed_at >= :since ORDER BY closed_at DESC"
            }
        val spec = databaseClient.sql(sql).bind("since", since)
        val finalSpec = if (accountId != null) spec.bind("accountId", accountId) else spec
        return finalSpec
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /**
     * Агрегаты по закрытым позициям за ВСЮ историю (без загрузки строк):
     * - [totalRealized] — суммарный реализованный P&L;
     * - [peakRealized] — максимальное кумулятивное значение реализованного P&L
     *   в хронологическом порядке закрытий (для просадки от пика).
     *
     * Позиции с NULL pnl не влияют на сумму (аналог sumOf { pnl ?: ZERO }).
     * Один агрегирующий проход в БД вместо загрузки всех строк в память.
     * (multi-account) accountId = null → только legacy-позиции без привязки.
     */
    data class ClosedPositionAggregates(
        val totalRealized: BigDecimal,
        val peakRealized: BigDecimal,
    )

    suspend fun findClosedAggregates(accountId: Long?): ClosedPositionAggregates {
        val accountClause = if (accountId == null) "account_id IS NULL" else "account_id = :accountId"
        val sql =
            """
            SELECT COALESCE(SUM(pnl), 0) AS total,
                   COALESCE(MAX(cum), 0) AS peak
            FROM (
                SELECT pnl,
                       SUM(pnl) OVER (
                           ORDER BY closed_at ASC NULLS FIRST
                           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                       ) AS cum
                FROM positions
                WHERE status != 'OPEN' AND $accountClause
            ) s
            """.trimIndent()
        val spec = databaseClient.sql(sql)
        val finalSpec = if (accountId != null) spec.bind("accountId", accountId) else spec
        return finalSpec
            .map { row, _ ->
                ClosedPositionAggregates(
                    totalRealized = row.get("total", BigDecimal::class.java) ?: BigDecimal.ZERO,
                    peakRealized = row.get("peak", BigDecimal::class.java) ?: BigDecimal.ZERO,
                )
            }.one()
            .awaitSingle()
    }

    suspend fun findClosedByTickerSince(
        ticker: String,
        since: LocalDateTime,
    ): List<Position> {
        val sql =
            """
            SELECT * FROM positions
            WHERE status != 'OPEN' AND ticker = :ticker AND closed_at >= :since
            ORDER BY closed_at DESC
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .bind("since", since)
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun findClosedByTickerAndAccountSince(
        ticker: String,
        accountId: Long,
        since: LocalDateTime,
    ): List<Position> {
        val sql =
            """
            SELECT * FROM positions
            WHERE status != 'OPEN' AND ticker = :ticker
              AND account_id = :accountId AND closed_at >= :since
            ORDER BY closed_at DESC
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .bind("accountId", accountId)
            .bind("since", since)
            .map { row, _ -> toPosition(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun save(position: Position): Position =
        if (position.id == null) {
            insert(position)
        } else {
            update(position)
            position
        }

    private fun DatabaseClient.GenericExecuteSpec.bindPosition(position: Position): DatabaseClient.GenericExecuteSpec =
        this
            .bind("ticker", position.ticker)
            .bind("direction", position.direction.name)
            .bind("quantity", position.quantity)
            .bind("entryPrice", position.entryPrice)
            .bindOrNull("currentPrice", position.currentPrice)
            .bindOrNull("closePrice", position.closePrice)
            .bindOrNull("stopLoss", position.stopLoss)
            .bindOrNull("takeProfit", position.takeProfit)
            .bind("instrumentType", position.instrumentType.name)
            .bindOrNull("leverage", position.leverage)
            .bindOrNull("goPerContract", position.goPerContract)
            .bindOrNull("marginUsed", position.marginUsed)
            .bindOrNull("liquidationPrice", position.liquidationPrice)
            .bind("variationMargin", position.variationMargin)
            .bindOrNull("stopLossPoints", position.stopLossPoints)
            .bindOrNull("trailingStopPrice", position.trailingStopPrice)
            .bindOrNull("pnl", position.pnl)
            .bind("status", position.status.name)
            .bindOrNull("alorOrderId", position.alorOrderId)
            .bindOrNull("closeOrderId", position.closeOrderId)
            .bindOrNull("slOrderId", position.slOrderId)
            .bindOrNull("tpOrderId", position.tpOrderId)
            .bindOrNull("slOrderPrice", position.slOrderPrice)
            .bindOrNull("tpOrderPrice", position.tpOrderPrice)
            .bind("slPendingReplace", position.slPendingReplace)
            .bind("tpPendingReplace", position.tpPendingReplace)
            .bind("slCancelPending", position.slCancelPending)
            .bind("tpCancelPending", position.tpCancelPending)
            .bind("closeCancelPending", position.closeCancelPending)
            .bind("pendingClose", position.pendingClose)
            .bind("pendingEntry", position.pendingEntry)
            .bind("realizedPnl", position.realizedPnl)
            .bindOrNull("closeReason", position.closeReason?.code)
            .bind("cumulativeCloseFillQty", position.cumulativeCloseFillQty)
            .bind("cumulativeSlFillQty", position.cumulativeSlFillQty)
            .bind("cumulativeTpFillQty", position.cumulativeTpFillQty)
            .bind("openedAt", position.openedAt)
            .bindOrNull("closedAt", position.closedAt)
            .bindOrNull("cycleId", position.cycleId)
            .bindOrNull("accountId", position.accountId)
            .bind("fundingUnknown", position.fundingUnknown)

    private suspend fun insert(position: Position): Position {
        val sql =
            """
            INSERT INTO positions (ticker, direction, quantity, entry_price, current_price, close_price,
                stop_loss, take_profit, instrument_type, leverage, go_per_contract, margin_used,
                liquidation_price, variation_margin, stop_loss_points, trailing_stop_price, pnl, status,
                alor_order_id, close_order_id, sl_order_id, tp_order_id, sl_order_price, tp_order_price,
                sl_pending_replace, tp_pending_replace, sl_cancel_pending, tp_cancel_pending,
                close_cancel_pending, pending_close, pending_entry, realized_pnl, close_reason,
                cumulative_close_fill_qty, cumulative_sl_fill_qty, cumulative_tp_fill_qty,
                opened_at, closed_at, cycle_id, account_id, funding_unknown)
            VALUES (:ticker, :direction, :quantity, :entryPrice, :currentPrice, :closePrice,
                :stopLoss, :takeProfit, :instrumentType, :leverage, :goPerContract, :marginUsed,
                :liquidationPrice, :variationMargin, :stopLossPoints, :trailingStopPrice, :pnl, :status,
                :alorOrderId, :closeOrderId, :slOrderId, :tpOrderId, :slOrderPrice, :tpOrderPrice,
                :slPendingReplace, :tpPendingReplace, :slCancelPending, :tpCancelPending,
                :closeCancelPending, :pendingClose, :pendingEntry, :realizedPnl, :closeReason,
                :cumulativeCloseFillQty, :cumulativeSlFillQty, :cumulativeTpFillQty,
                :openedAt, :closedAt, :cycleId, :accountId, :fundingUnknown)
            RETURNING id
            """.trimIndent()
        val id =
            databaseClient
                .sql(sql)
                .bindPosition(position)
                .map { row, _ -> row.get("id", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        return position.copy(id = id)
    }

    /**
     * Сброс счётчика филов ЗАЩИТНОЙ заявки (SL/TP) при переармировании.
     *
     * `save()` пишет счётчики через `GREATEST` (защита от stale-копии), поэтому
     * легитимное обнуление делается отдельным узким UPDATE (скан-9 P2).
     */
    suspend fun resetProtectionFillQty(
        id: Long,
        side: ProtectionSide,
    ) {
        val column = if (side == ProtectionSide.SL) "cumulative_sl_fill_qty" else "cumulative_tp_fill_qty"
        databaseClient
            .sql("UPDATE positions SET $column = 0 WHERE id = :id")
            .bind("id", id)
            .then()
            .awaitSingleOrNull()
    }

    /** Сброс счётчика закрывающих филов (новый close-attempt). */
    suspend fun resetCloseFillQty(id: Long) {
        databaseClient
            .sql("UPDATE positions SET cumulative_close_fill_qty = 0 WHERE id = :id")
            .bind("id", id)
            .then()
            .awaitSingleOrNull()
    }

    /** Какая защитная заявка переармируется (см. [resetProtectionFillQty]). */
    enum class ProtectionSide { SL, TP }

    /**
     * Узкое mark-to-market обновление: только `current_price`/`pnl` у OPEN-позиции.
     *
     * Монитор акций держит detached-сущность (репозиторий возвращает новый объект
     * на каждый тик), full-row `save()` без изменения SL/TP не вызывался — unrealized
     * P&L не попадал в БД, и дневной лимит/просадка не видели просадку по акции.
     */
    suspend fun updateMarkToMarket(
        id: Long,
        currentPrice: BigDecimal,
        pnl: BigDecimal?,
    ) {
        databaseClient
            .sql("UPDATE positions SET current_price = :currentPrice, pnl = :pnl WHERE id = :id AND status = 'OPEN'")
            .bind("currentPrice", currentPrice)
            .bindOrNull("pnl", pnl)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun update(position: Position) {
        val sql =
            """
            UPDATE positions SET
                ticker = :ticker, direction = :direction,
                -- Терминальное состояние побеждает: закрытую позицию нельзя «воскресить»
                -- устаревшим save() из монитора/менеджера защиты (скан-1 P1-4).
                -- Объём существующей позиции: пока строка помечена pending_entry, объём может
                -- РАСТИ (частичный вход догоняется до cumulative из сверки), после подтверждения
                -- входа — только убывает (частичные филы/закрытия). Иначе stale-копия монитора
                -- возвращает старый размер и закрытие закроет больше, чем открыто (скан-5).
                quantity =
                    CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.quantity
                         -- Уменьшение применяем всегда (частичный фил/закрытие).
                         WHEN :quantity < positions.quantity THEN :quantity
                         -- Увеличение — только пока вход не подтверждён (догон до cumulative).
                         WHEN positions.pending_entry THEN :quantity
                         ELSE positions.quantity END,
                entry_price = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.entry_price ELSE :entryPrice END,
                current_price = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.current_price ELSE :currentPrice END,
                close_price = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.close_price ELSE :closePrice END,
                stop_loss = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.stop_loss ELSE :stopLoss END,
                take_profit = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.take_profit ELSE :takeProfit END, instrument_type = :instrumentType, leverage = :leverage,
                go_per_contract = :goPerContract, margin_used = :marginUsed, liquidation_price = :liquidationPrice,
                variation_margin = :variationMargin, stop_loss_points = :stopLossPoints,
                trailing_stop_price = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.trailing_stop_price ELSE :trailingStopPrice END,
                pnl = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.pnl ELSE :pnl END,
                status = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.status ELSE :status END,
                alor_order_id = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.alor_order_id ELSE :alorOrderId END,
                close_order_id = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.close_order_id ELSE :closeOrderId END,
                sl_order_id = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.sl_order_id ELSE :slOrderId END,
                tp_order_id = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.tp_order_id ELSE :tpOrderId END,
                sl_order_price = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.sl_order_price ELSE :slOrderPrice END,
                tp_order_price = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.tp_order_price ELSE :tpOrderPrice END,
                sl_pending_replace = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.sl_pending_replace ELSE :slPendingReplace END,
                tp_pending_replace = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.tp_pending_replace ELSE :tpPendingReplace END,
                sl_cancel_pending = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.sl_cancel_pending ELSE :slCancelPending END,
                tp_cancel_pending = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.tp_cancel_pending ELSE :tpCancelPending END,
                close_cancel_pending = :closeCancelPending,
                pending_close = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN FALSE ELSE :pendingClose END,
                pending_entry = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN FALSE ELSE :pendingEntry END,
                realized_pnl = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.realized_pnl ELSE :realizedPnl END,
                close_reason = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.close_reason ELSE :closeReason END,
                -- Счётчики филов монотонны в рамках одной заявки: GREATEST не даёт stale-копии
                -- откатить их (иначе дельта применится повторно — пере-закрытие, скан-9 P2).
                -- Легитимный сброс при переармировании — явные reset*-методы ниже.
                cumulative_close_fill_qty =
                    CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.cumulative_close_fill_qty
                         ELSE GREATEST(positions.cumulative_close_fill_qty, :cumulativeCloseFillQty) END,
                cumulative_sl_fill_qty =
                    CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.cumulative_sl_fill_qty
                         ELSE GREATEST(positions.cumulative_sl_fill_qty, :cumulativeSlFillQty) END,
                cumulative_tp_fill_qty =
                    CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.cumulative_tp_fill_qty
                         ELSE GREATEST(positions.cumulative_tp_fill_qty, :cumulativeTpFillQty) END,
                opened_at = :openedAt,
                closed_at = CASE WHEN positions.status IN ('CLOSED', 'RECONCILIATION_REQUIRED') THEN positions.closed_at ELSE :closedAt END,
                cycle_id = :cycleId,
                account_id = :accountId, funding_unknown = :fundingUnknown
            WHERE id = :id
            """.trimIndent()
        databaseClient
            .sql(sql)
            .bindPosition(position)
            .bind("id", position.id!!)
            .then()
            .awaitSingleOrNull()
    }

    /**
     * Атомарная резервация слота входа (EXEC-002, MR-B): защита от двойного входа
     * в позицию по одному (ticker, account).
     *
     * `positions` партиционирована по RANGE(opened_at), поэтому глобальный
     * partial UNIQUE INDEX на ней невозможен — слот держится в отдельной таблице
     * `entry_reservations` с уникальным индексом (ticker, COALESCE(account_id, 0)).
     * INSERT с ON CONFLICT атомарен: из конкурентных входов побеждает один, остальные
     * получают null и НЕ создают entry-ордер.
     *
     * Дополнительно резервация не создаётся, если по тому же (ticker, account) уже
     * есть OPEN-позиция (fast-path для позиций, открытых до ввода резерваций).
     *
     * @return id резервации, либо null — слот занят (уже резервирован / есть OPEN-позиция).
     */
    suspend fun reserveEntry(
        ticker: String,
        direction: PositionDirection,
        accountId: Long?,
    ): Long? {
        val sql =
            """
            INSERT INTO entry_reservations (ticker, account_id, direction)
            SELECT :ticker, :accountId, :direction
            WHERE NOT EXISTS (
                SELECT 1 FROM entry_reservations er
                WHERE er.ticker = :ticker AND COALESCE(er.account_id, 0) = COALESCE(:accountId, 0)
            ) AND NOT EXISTS (
                SELECT 1 FROM positions p
                WHERE p.status = 'OPEN' AND p.ticker = :ticker
                  AND COALESCE(p.account_id, 0) = COALESCE(:accountId, 0)
            )
            ON CONFLICT DO NOTHING
            RETURNING id
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .bind("direction", direction.name)
            .bindOrNull("accountId", accountId)
            .map { row, _ -> row.get("id", Long::class.javaObjectType)!! }
            .one()
            .awaitSingleOrNull()
    }

    /**
     * Освобождение слота входа (EXEC-002): закрытие позиции, определённый отказ биржи,
     * abandonEntry или cleanup. Резервация одна на (ticker, account) — удаление по ключу.
     */
    suspend fun releaseEntry(
        ticker: String,
        accountId: Long?,
    ) {
        val sql =
            """
            DELETE FROM entry_reservations
            WHERE ticker = :ticker AND COALESCE(account_id, 0) = COALESCE(:accountId, 0)
            """.trimIndent()
        databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .bindOrNull("accountId", accountId)
            .then()
            .awaitSingleOrNull()
    }

    /**
     * Уборка «осиротевших» резерваций (краш бота между резервацией и созданием позиции):
     * снимает резервации старше [maxAgeMs], для которых нет открытой позиции.
     *
     * ВАЖНО (время): `entry_reservations.created_at` заполняется дефолтом БД `NOW()`,
     * поэтому порог возраста тоже считается временем БД (`now() - make_interval(...)`).
     * Прежний `:threshold` из JVM (`LocalDateTime.now()`) при другой зоне приложения и БД
     * (например, МСК в образе против UTC в Postgres) давал порог на 3 ч впереди: уборка
     * удаляла СВЕЖИЕ резервации → возможен повторный вход в позицию (двойной вход).
     */
    suspend fun cleanupStaleEntryReservations(maxAgeMs: Long = 30 * 60 * 1000): Int {
        val sql =
            """
            DELETE FROM entry_reservations er
            WHERE er.created_at < now() - make_interval(secs => :maxAgeSeconds)
              AND NOT EXISTS (
                  SELECT 1 FROM positions p
                  WHERE p.status = 'OPEN' AND p.ticker = er.ticker
                    AND COALESCE(p.account_id, 0) = COALESCE(er.account_id, 0)
              )
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("maxAgeSeconds", maxAgeMs / 1000.0)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
            .toInt()
    }

    suspend fun deleteAll() {
        databaseClient.sql("DELETE FROM positions").then().awaitSingleOrNull()
    }
}
