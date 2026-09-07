package com.trading.bot.application.decision

import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import com.trading.bot.service.DistributedLockService.LeaseFence
import java.math.BigDecimal

/**
 * Гейт исполнения входа: обёртка над
 * [com.trading.bot.application.OrderExecutionEngine.placeEntryOrder].
 *
 * Отдельный fun-интерфейс — [DecisionEngine] не знает об OrderExecutionEngine,
 * а тесты могут подставить фейк без БД/outbox.
 *
 * [LeaseFence] — маркер живой lease распределённого лока входа (см.
 * [com.trading.bot.service.DistributedLockService.runExclusiveFenced]); передаётся
 * из критической секции входа, чтобы [OrderExecutionEngine] мог проверить владение
 * непосредственно перед необратимыми действиями (create outbox → order). Может быть
 * null, если вход выполняется без распределённого лока (disabled/fail-open, unit-тесты).
 */
fun interface ExecutionGateway {
    suspend fun placeEntryOrder(
        ticker: String,
        direction: PositionDirection,
        qty: Int,
        entryPrice: BigDecimal,
        accountId: Long?,
        buildPosition: (orderId: String?, pending: Boolean, fillPrice: BigDecimal, qty: Int) -> Position,
        fence: LeaseFence?,
    ): Position?
}
