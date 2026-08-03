package com.trading.bot.agent
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.client.LlmClient
import com.trading.bot.model.*
import com.trading.bot.repository.AgentLogRepository
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class FundamentalAnalysisAgent(private val llmClient: LlmClient, private val agentLogRepository: AgentLogRepository, private val objectMapper: ObjectMapper) {
    private val logger = KotlinLogging.logger {}
    suspend fun analyze(ticker: String, cycleId: String): FundamentalReport = coroutineScope {
        val start = System.currentTimeMillis()
        val macro = mapOf("keyRate" to 21.0, "inflation" to 8.6, "usdRub" to 92.5, "oilPrice" to 78.0, "gdpGrowth" to 3.6)
        val sector = mapOf("SBER" to "Финансы", "GAZP" to "Нефтегаз", "LKOH" to "Нефтегаз", "YNDX" to "Технологии", "MGNT" to "Ритейл", "NVTK" to "Нефтегаз", "ROSN" to "Нефтегаз", "TATN" to "Нефтегаз", "VTBR" to "Финансы", "ALRS" to "Металлургия")
        val prompt = "Фундаментальный анализ $ticker (ММВБ). Макро: ${macro.entries.joinToString(", ")}. Сектор: ${sector[ticker] ?: "Разное"}. Учти бизнес-модель, P/E, P/B, EPS, ROE, дивиденды, долг, рост, санкции. Дай JSON: pe, pb, eps, dividendYield, revenueGrowth, profitMargin, debtToEquity, roe, sectorOutlook, macroFactors[], conclusion(UNDERVALUED/OVERVALUED/FAIR), confidence(0-1), reasoning(русский)"
        val resp = llmClient.chat("Ты — фундаментальный аналитик ММВБ. Ответь ТОЛЬКО JSON.", prompt)
        val report = parseFund(resp.content)
        agentLogRepository.save(AgentLog(cycleId = cycleId, agentName = "Agent-2-Fundamental", ticker = ticker, action = report.conclusion, confidence = report.confidence, reasoning = report.reasoning, rawOutput = resp.content, latencyMs = System.currentTimeMillis() - start))
        report.copy(rawOutput = resp.content)
    }
    private fun parseFund(content: String): FundamentalReport {
        return try { val j = objectMapper.readTree(content.replace("```json", "").replace("```", "").trim())
            FundamentalReport(j["pe"]?.takeIf { !it.isNull }?.asDouble(), j["pb"]?.takeIf { !it.isNull }?.asDouble(), j["eps"]?.takeIf { !it.isNull }?.asDouble(), j["dividendYield"]?.takeIf { !it.isNull }?.asDouble(), j["revenueGrowth"]?.takeIf { !it.isNull }?.asDouble(), j["profitMargin"]?.takeIf { !it.isNull }?.asDouble(), j["debtToEquity"]?.takeIf { !it.isNull }?.asDouble(), j["roe"]?.takeIf { !it.isNull }?.asDouble(), j["sectorOutlook"]?.asText() ?: "NEUTRAL", j["macroFactors"]?.map { it.asText() } ?: emptyList(), j["conclusion"]?.asText() ?: "FAIR", j["confidence"]?.asDouble() ?: 0.0, j["reasoning"]?.asText() ?: "", content)
        } catch (e: Exception) { logger.error(e) { "Fund parse error" }; FundamentalReport(null, null, null, null, null, null, null, null, "NEUTRAL", emptyList(), "FAIR", 0.0, "Parse error", content) }
    }
}
