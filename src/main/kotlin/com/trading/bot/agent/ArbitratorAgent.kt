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
class ArbitratorAgent(private val llmClient: LlmClient, private val agentLogRepository: AgentLogRepository, private val objectMapper: ObjectMapper) {
    private val logger = KotlinLogging.logger {}
    data class Final(val action: StrategyAction, val targetPrice: BigDecimal, val quantity: Int, val stopLoss: BigDecimal?, val takeProfit: BigDecimal?, val trailingStop: Boolean, val confidence: Double, val reasoning: String)

    suspend fun adjudicate(draft: StrategyAgent.Draft, challenge: String, tech: TechnicalReport, fund: FundamentalReport, snapshot: MarketSnapshot, cycleId: String): Final = coroutineScope {
        val start = System.currentTimeMillis()
        val prompt = "СТРАТЕГИЯ: ${draft.action} @ ${draft.targetPrice} conf=${String.format("%.2f", draft.confidence)}. Обоснование: ${draft.reasoning}. КОНТРАРГУМЕНТЫ: $challenge. Тех: ${tech.trend}, RSI=${String.format("%.1f", tech.rsi)}. Фунд: ${fund.conclusion}. Цена: ${snapshot.currentPrice}. Примите ОКОНЧАТЕЛЬНОЕ решение. Если контраргументы сильны или conf<0.65 — HOLD. Ответь ТОЛЬКО JSON: action, targetPrice, quantity, stopLoss, takeProfit, trailingStop, confidence, reasoning."
        val resp = llmClient.chat("Ты — главный арбитр. Будь консервативен. Лучше пропустить сделку, чем войти в плохую.", prompt)
        val dec = parseFinal(resp.content, draft)
        agentLogRepository.save(AgentLog(cycleId = cycleId, agentName = "Agent-5-Arbitrator", ticker = "", action = dec.action.name, confidence = dec.confidence, reasoning = dec.reasoning, rawOutput = resp.content, latencyMs = System.currentTimeMillis() - start))
        logger.info { "Agent 5 FINAL: ${dec.action} @ ${dec.targetPrice} conf=${String.format("%.2f", dec.confidence)}" }
        dec
    }
    private fun parseFinal(c: String, fallback: StrategyAgent.Draft): Final {
        return try { val j = objectMapper.readTree(c.replace("```json", "").replace("```", "").trim())
            Final(StrategyAction.valueOf(j["action"]?.asText()?.uppercase() ?: "HOLD"), j["targetPrice"]?.asText()?.toBigDecimalOrNull() ?: fallback.targetPrice, j["quantity"]?.asInt() ?: fallback.quantity, j["stopLoss"]?.asText()?.toBigDecimalOrNull() ?: fallback.stopLoss, j["takeProfit"]?.asText()?.toBigDecimalOrNull() ?: fallback.takeProfit, j["trailingStop"]?.asBoolean() ?: fallback.trailingStop, j["confidence"]?.asDouble() ?: 0.0, j["reasoning"]?.asText() ?: fallback.reasoning)
        } catch (e: Exception) { logger.error(e) { "Final parse error" }; Final(StrategyAction.HOLD, fallback.targetPrice, 0, null, null, false, 0.0, "Parse error") }
    }
}
