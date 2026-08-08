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
 */
data class ExecutionReport(
    val orderId: String,
    val status: OrderStatus,
    val filledQty: Int,
    val avgPrice: BigDecimal?,
    val ticker: String? = null,
    val side: String? = null,
    val timestamp: Instant = Instant.now(),
)
