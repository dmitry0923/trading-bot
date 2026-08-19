package com.trading.bot.model.dto

import java.math.BigDecimal
import java.time.Instant

enum class OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    REJECTED,
    UNKNOWN,
}

/**
 * Отчёт об исполнении ордера (из WebSocket Alor или REST verifyOrder).
 *
 * [cumulativeFilledQty] — накопительное количество исполненных **лот** (Alor: filledQtyBatch).
 * Для определения дельты к позиции используется
 * [cumulativeFilledQty] - [com.trading.bot.model.entity.Position.cumulativeCloseFillQty],
 * а не сам cumulativeFilledQty напрямую.
 *
 * [requestedQty] — исходное количество лотов в заявке (Alor: qtyBatch).
 */
data class ExecutionReport(
    val orderId: String,
    val status: OrderStatus,
    val cumulativeFilledQty: Int,
    val avgPrice: BigDecimal?,
    val ticker: String? = null,
    val side: String? = null,
    val timestamp: Instant = Instant.now(),
    val requestedQty: Int = 0,
)
