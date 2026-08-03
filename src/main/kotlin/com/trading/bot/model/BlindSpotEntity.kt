package com.trading.bot.model

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "blind_spots")
data class BlindSpotEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(nullable = false)
    val ticker: String,
    @Column(nullable = false, length = 4000)
    val conditionPattern: String,
    @Column(nullable = false)
    val lossRate: Double,
    @Column(nullable = false)
    val occurrenceCount: Int,
    @Column(nullable = false, length = 4000)
    val recommendation: String,
    var isActive: Boolean = true,
    val detectedAt: LocalDateTime = LocalDateTime.now(),
    var resolvedAt: LocalDateTime? = null
)
