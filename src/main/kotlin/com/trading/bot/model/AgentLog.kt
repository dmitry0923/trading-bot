package com.trading.bot.model

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "agent_logs")
data class AgentLog(
    @Id val id: String = java.util.UUID.randomUUID().toString(),
    @Column(nullable = false) val cycleId: String,
    @Column(nullable = false) val agentName: String,
    @Column(nullable = false) val ticker: String,
    @Column(nullable = false, length = 20) val action: String,
    val confidence: Double? = null,
    @Column(length = 4000) val reasoning: String? = null,
    @Column(length = 8000) val rawOutput: String? = null,
    val latencyMs: Long? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
