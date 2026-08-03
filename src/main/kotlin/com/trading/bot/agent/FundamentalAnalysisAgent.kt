package com.trading.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.client.LlmClient
import com.trading.bot.model.AgentLog
import com.trading.bot.model.FundamentalReport
import com.trading.bot.repository.AgentLogRepository
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class FundamentalAnalysisAgent(
    private val llmClient: LlmClient,
    private val agentLogRepository: AgentLogRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}

    private val systemPrompt = """
        Ты — фундаментальный аналитик российского фондового рынка.
        Дай оценку на основе известных тебе макроэкономических факторов, отчётности и новостей.
        Будь консервативен: без свежих данных по конкретному тикеру отвечай NEUTRAL.
        Ответь СТРОГО JSON: {"conclusion":"BULLISH|BEARISH|NEUTRAL","confidence":0.0,"reasoning":"string"}.
    """.trimIndent()

    suspend fun analyze(ticker: String, cycleId: String): FundamentalReport {
        val start = System.currentTimeMillis()
        val prompt = """
            ТИКЕР: $ticker (Московская биржа, акции).
            Если у тебя есть актуальная информация по данному тикеру — оцени её.
            Если данных нет — conclusion=NEUTRAL, confidence=0.2.
        """.trimIndent()

        val resp = llmClient.chat(systemPrompt, prompt, temperature = 0.1)
        val report = if (resp.isFallback) {
            logger.info { "LLM unavailable for fundamental analysis of $ticker" }
            FundamentalReport(conclusion = "NEUTRAL", confidence = 0.0, reasoning = "LLM unavailable")
        } else {
            try {
                val j = objectMapper.readTree(resp.content)
                FundamentalReport(
                    conclusion = j.path("conclusion").asText("NEUTRAL").uppercase().let {
                        if (it in setOf("BULLISH", "BEARISH", "NEUTRAL")) it else "NEUTRAL"
                    },
                    confidence = j.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0),
                    reasoning = j.path("reasoning").asText("")
                )
            } catch (e: Exception) {
                logger.warn(e) { "Fundamental LLM parse error for $ticker" }
                FundamentalReport(conclusion = "NEUTRAL", confidence = 0.0, reasoning = "Parse error")
            }
        }

        agentLogRepository.save(
            AgentLog(
                cycleId = cycleId,
                agentName = "Agent-2-Fundamental",
                ticker = ticker,
                action = report.conclusion,
                confidence = report.confidence,
                reasoning = report.reasoning,
                rawOutput = resp.content,
                latencyMs = System.currentTimeMillis() - start
            )
        )
        return report
    }
}
