package com.trading.bot.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "positions")
data class Position(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    @Column(nullable = false) val ticker: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val direction: PositionDirection,
    @Column(nullable = false) var quantity: Int,
    @Column(nullable = false, precision = 19, scale = 6) val entryPrice: BigDecimal,
    @Column(precision = 19, scale = 6) var currentPrice: BigDecimal? = null,
    @Column(precision = 19, scale = 6) var stopLoss: BigDecimal? = null,
    @Column(precision = 19, scale = 6) var takeProfit: BigDecimal? = null,
    @Column(precision = 19, scale = 6) var trailingStopPrice: BigDecimal? = null,
    @Column(nullable = false) val openedAt: LocalDateTime = LocalDateTime.now(),
    var closedAt: LocalDateTime? = null,
    @Column(precision = 19, scale = 6) var closePrice: BigDecimal? = null,
    @Column(precision = 19, scale = 6) var pnl: BigDecimal? = null,
    @Enumerated(EnumType.STRING) var status: PositionStatus = PositionStatus.OPEN,
    @Column(nullable = false) val alorOrderId: String = "",
    var closeReason: String? = null
)

enum class PositionDirection { LONG, SHORT }
enum class PositionStatus { OPEN, CLOSED, STOPPED, TAKE_PROFIT }
