package com.trading.bot.agent
import com.trading.bot.client.LlmClient
import com.trading.bot.model.*
import com.trading.bot.repository.AgentLogRepository
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ContrarianAgent(private val llmClient: LlmClient, private val agentLogRepository: AgentLogRepository) {
    private val logger = KotlinLogging.logger {}
    suspend fun challenge(draft: StrategyAgent.Draft, tech: TechnicalReport, fund: FundamentalReport, snapshot: MarketSnapshot, cycleId: String): String = coroutineScope {
        val start = System.currentTimeMillis()
        val prompt = "Стратегия: ${draft.action} ${draft.targetPrice} qty=${draft.quantity} conf=${String.format("%.2f", draft.confidence)}. Обоснование: ${draft.reasoning}. Тех: ${tech.trend}, RSI=${String.format("%.1f", tech.rsi)}. Фунд: P/E=${fund.pe ?: "N/A"}, сектор=${fund.sectorOutlook}. Найди минимум 3 критические ошибки и альтернативный сценарий."
        val resp = llmClient.chat("Ты — contrarian (дьявол адвокат). Оспорь стратегию. Будь критичен.", prompt)
        agentLogRepository.save(AgentLog(cycleId = cycleId, agentName = "Agent-4-Contrarian", ticker = "", action = "CHALLENGE", reasoning = resp.content.take(1000), rawOutput = resp.content, latencyMs = System.currentTimeMillis() - start))
        resp.content
    }
}
