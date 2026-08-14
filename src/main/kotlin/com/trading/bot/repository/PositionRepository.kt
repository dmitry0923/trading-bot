package com.trading.bot.repository

import com.trading.bot.infrastructure.db.bindOrNull
import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * R2DBC-репозиторий позиций. Все методы suspend: вызываются из корутин,
 * блокирующие JDBC-обёртки ([com.trading.bot.infrastructure.db.BlockingDb]) больше не нужны.
 */
@Repository
class PositionRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toPosition(row: Row): Position =
        Position(
            id = row.get("id", Long::class.javaObjectType),
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
            pendingClose = row.get("pending_close", Boolean::class.javaObjectType) ?: false,
            pendingEntry = row.get("pending_entry", Boolean::class.javaObjectType) ?: false,
            realizedPnl = row.get("realized_pnl", BigDecimal::class.java) ?: BigDecimal.ZERO,
            closeReason = row.get("close_reason", String::class.java),
            openedAt = row.require("opened_at", LocalDateTime::class.java),
            closedAt = row.get("closed_at", LocalDateTime::class.java),
            cycleId = row.get("cycle_id", String::class.java),
            accountId = row.get("account_id", Long::class.javaObjectType),
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
     * финализации одного close-ордера). Конкурентные [finalizeClosePosition]-вызовы
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
        closeReason: String,
        pnl: BigDecimal,
    ): Boolean {
        val sql =
            """
            UPDATE positions SET
                status = :status, closed_at = :closedAt, close_price = :closePrice,
                close_reason = :closeReason, pnl = :pnl, pending_close = false, close_order_id = NULL
            WHERE id = :id AND status = 'OPEN'
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("id", id)
            .bind("status", status.name)
            .bind("closedAt", LocalDateTime.now())
            .bind("closePrice", closePrice)
            .bind("closeReason", closeReason)
            .bind("pnl", pnl)
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
     * Необязательные фильтры: [ticker], [since] (по [closedAt]).
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
     */
    data class ClosedPositionAggregates(
        val totalRealized: BigDecimal,
        val peakRealized: BigDecimal,
    )

    suspend fun findClosedAggregates(): ClosedPositionAggregates {
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
                WHERE status != 'OPEN'
            ) s
            """.trimIndent()
        return databaseClient
            .sql(sql)
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
            .bind("pendingClose", position.pendingClose)
            .bind("pendingEntry", position.pendingEntry)
            .bind("realizedPnl", position.realizedPnl)
            .bindOrNull("closeReason", position.closeReason)
            .bind("openedAt", position.openedAt)
            .bindOrNull("closedAt", position.closedAt)
            .bindOrNull("cycleId", position.cycleId)
            .bindOrNull("accountId", position.accountId)

    private suspend fun insert(position: Position): Position {
        val sql =
            """
            INSERT INTO positions (ticker, direction, quantity, entry_price, current_price, close_price,
                stop_loss, take_profit, instrument_type, leverage, go_per_contract, margin_used,
                liquidation_price, variation_margin, stop_loss_points, trailing_stop_price, pnl, status,
                alor_order_id, close_order_id, sl_order_id, tp_order_id, sl_order_price, tp_order_price,
                sl_pending_replace, tp_pending_replace, pending_close, pending_entry, realized_pnl, close_reason, opened_at, closed_at, cycle_id, account_id)
            VALUES (:ticker, :direction, :quantity, :entryPrice, :currentPrice, :closePrice,
                :stopLoss, :takeProfit, :instrumentType, :leverage, :goPerContract, :marginUsed,
                :liquidationPrice, :variationMargin, :stopLossPoints, :trailingStopPrice, :pnl, :status,
                :alorOrderId, :closeOrderId, :slOrderId, :tpOrderId, :slOrderPrice, :tpOrderPrice,
                :slPendingReplace, :tpPendingReplace, :pendingClose, :pendingEntry, :realizedPnl, :closeReason, :openedAt, :closedAt, :cycleId, :accountId)
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

    private suspend fun update(position: Position) {
        val sql =
            """
            UPDATE positions SET
                ticker = :ticker, direction = :direction, quantity = :quantity, entry_price = :entryPrice,
                current_price = :currentPrice, close_price = :closePrice, stop_loss = :stopLoss,
                take_profit = :takeProfit, instrument_type = :instrumentType, leverage = :leverage,
                go_per_contract = :goPerContract, margin_used = :marginUsed, liquidation_price = :liquidationPrice,
                variation_margin = :variationMargin, stop_loss_points = :stopLossPoints,
                trailing_stop_price = :trailingStopPrice, pnl = :pnl, status = :status,
                alor_order_id = :alorOrderId, close_order_id = :closeOrderId,
                sl_order_id = :slOrderId, tp_order_id = :tpOrderId,
                sl_order_price = :slOrderPrice, tp_order_price = :tpOrderPrice,
                sl_pending_replace = :slPendingReplace, tp_pending_replace = :tpPendingReplace,
                pending_close = :pendingClose, pending_entry = :pendingEntry, realized_pnl = :realizedPnl,
                close_reason = :closeReason, opened_at = :openedAt, closed_at = :closedAt, cycle_id = :cycleId,
                account_id = :accountId
            WHERE id = :id
            """.trimIndent()
        databaseClient
            .sql(sql)
            .bindPosition(position)
            .bind("id", position.id!!)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun deleteAll() {
        databaseClient.sql("DELETE FROM positions").then().awaitSingleOrNull()
    }
}
