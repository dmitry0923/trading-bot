package com.trading.bot.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "candles", indexes = [Index(name = "idx_cand_t_tf_t", columnList = "ticker,timeframe,time")])
data class Candle(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    @Column(nullable = false) val ticker: String,
    @Column(nullable = false) val timeframe: String,
    @Column(nullable = false) val time: LocalDateTime,
    @Column(nullable = false, precision = 19, scale = 6) val open: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 6) val high: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 6) val low: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 6) val close: BigDecimal,
    @Column(nullable = false) val volume: Long,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
