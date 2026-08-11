package com.trading.bot.application.decision

import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import java.math.BigDecimal

/**
 * Гейт исполнения входа: обёртка над
 * [com.trading.bot.application.OrderExecutionEngine.placeEntryOrder].
 *
 * Отдельный fun-интерфейс — [DecisionEngine] не знает об OrderExecutionEngine,
 * а тесты могут подставить фейк без БД/outbox.
 */
fun interface ExecutionGateway {
    suspend fun placeEntryOrder(
        ticker: String,
        direction: PositionDirection,
        qty: Int,
        entryPrice: BigDecimal,
        accountId: Long?,
        buildPosition: (orderId: String?, pending: Boolean, fillPrice: BigDecimal, qty: Int) -> Position,
    ): Position?
}
