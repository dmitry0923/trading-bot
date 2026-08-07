package com.trading.bot.model

import java.time.LocalDateTime

data class AgentLog(
    val id: Long? = null,
    val cycleId: String,
    val agentName: String,
    val ticker: String? = null,
    val action: String,
    val confidence: Double? = null,
    val reasoning: String? = null,
    val rawOutput: String? = null,
    val latencyMs: Long? = null,
    val tokensUsed: Int? = null,
    val isCached: Boolean = false,
    val overrideReason: String? = null,
    val storageKey: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
