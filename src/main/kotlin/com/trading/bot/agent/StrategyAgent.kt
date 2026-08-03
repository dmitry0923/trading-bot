package com.trading.bot.agent
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.client.LlmClient
import com.trading.bot.model.*
import com.trading.bot.repository.AgentLogRepository
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class StrategyAgent(private val llmClient: LlmClient, private val agentLogRepository: AgentLogRepository, private val objectMapper: ObjectMapper) {
    private val logger = KotlinLogging.logger {}
    data class Draft(val action: StrategyAction, val targetPrice: BigDecimal, val quantity: Int, val stopLoss: BigDecimal?, val takeProfit: BigDecimal?, val trailingStop: Boolean, val confidence: Double, val reasoning: String)

    suspend fun formulate(ticker: String, tech: TechnicalReport, fund: FundamentalReport, snapshot: MarketSnapshot, cycleId: String): Draft = coroutineScope {
        val start = System.currentTimeMillis()
        val prompt = "$ticker | Цена: ${snapshot.currentPrice} | Тех: тренд=${tech.trend}, RSI=${String.format("%.1f", tech.rsi)}, MACD=${String.format("%.4f", tech.macd)}, BB=${String.format("%.2f", tech.bbPosition)} | Фунд: P/E=${fund.pe ?: "N/A"}, сектор=${fund.sectorOutlook}, вывод=${fund.conclusion} | Сформулируй стратегию JSON: action(BUY/SELL/HOLD), targetPrice, quantity, stopLoss, takeProfit, trailingStop(boolean), confidence(0-1), reasoning(русский). Для краткосрочного таймфрейма приоритет теханализу."
        val resp = llmClient.chat("Ты — стратег ММВБ. Синтезируй анализы. Ответь ТОЛЬКО JSON.", prompt)
        val draft = parseDraft(resp.content)
        agentLogRepository.save(AgentLog(cycleId = cycleId, agentName = "Agent-3-Strategist", ticker = ticker, action = draft.action.name, confidence = draft.confidence, reasoning = draft.reasoning, rawOutput = resp.content, latencyMs = System.currentTimeMillis() - start))
        draft
    }
    private fun parseDraft(c: String): Draft {
        return try { val j = objectMapper.readTree(c.replace("```json", "").replace("```", "").trim())
            Draft(StrategyAction.valueOf(j["action"]?.asText()?.uppercase() ?: "HOLD"), j["targetPrice"]?.asText()?.toBigDecimalOrNull() ?: BigDecimal.ZERO, j["quantity"]?.asInt() ?: 0, j["stopLoss"]?.asText()?.toBigDecimalOrNull(), j["takeProfit"]?.asText()?.toBigDecimalOrNull(), j["trailingStop"]?.asBoolean() ?: false, j["confidence"]?.asDouble() ?: 0.0, j["reasoning"]?.asText() ?: "")
        } catch (e: Exception) { logger.error(e) { "Draft parse error" }; Draft(StrategyAction.HOLD, BigDecimal.ZERO, 0, null, null, false, 0.0, "Parse error") }
    }
}
