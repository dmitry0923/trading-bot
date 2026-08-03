package com.trading.bot.model

import java.math.BigDecimal
import java.time.LocalDateTime

data class Position(
    val id: Long? = null,
    var ticker: String,
    var direction: PositionDirection,
    var quantity: Int,
    var entryPrice: BigDecimal,
    var currentPrice: BigDecimal? = null,
    var closePrice: BigDecimal? = null,
    var stopLoss: BigDecimal? = null,
    var takeProfit: BigDecimal? = null,
    var trailingStopPrice: BigDecimal? = null,
    var pnl: BigDecimal? = null,
    var status: PositionStatus = PositionStatus.OPEN,
    var alorOrderId: String? = null,
    var closeReason: String? = null,
    var openedAt: LocalDateTime = LocalDateTime.now(),
    var closedAt: LocalDateTime? = null
)
