package com.trading.bot.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "strategies")
data class Strategy(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    @Column(nullable = false) val ticker: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val action: StrategyAction,
    @Column(nullable = false, precision = 19, scale = 6) val targetPrice: BigDecimal,
    @Column(nullable = false) val quantity: Int,
    @Column(precision = 19, scale = 6) val stopLoss: BigDecimal? = null,
    @Column(precision = 19, scale = 6) val takeProfit: BigDecimal? = null,
    @Column(nullable = false) val trailingStop: Boolean = false,
    @Column(nullable = false) val confidence: Double,
    @Column(length = 4000) val reasoning: String = "",
    @Column(nullable = false) val cycleId: String = "",
    @Column(nullable = false) val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(nullable = false) val validUntil: LocalDateTime = LocalDateTime.now().plusMinutes(10)
)

enum class StrategyAction { BUY, SELL, HOLD, CLOSE }
