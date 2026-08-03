package com.trading.bot.repository
import com.trading.bot.model.AgentLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AgentLogRepository : JpaRepository<AgentLog, String> {
    fun findTop100ByOrderByCreatedAtDesc(): List<AgentLog>
}
