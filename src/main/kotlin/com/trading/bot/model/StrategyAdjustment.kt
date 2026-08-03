package com.trading.bot.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "strategy_adjustments")
data class StrategyAdjustment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(nullable = false)
    val ticker: String,
    @Column(nullable = false, length = 50)
    val adjustmentType: String,
    @Column(precision = 19, scale = 6)
    val oldValue: BigDecimal? = null,
    @Column(precision = 19, scale = 6)
    val newValue: BigDecimal? = null,
    @Column(nullable = false, length = 50)
    val triggeredBy: String,
    @Column(nullable = false, length = 4000)
    val reason: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
