package com.trading.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.client.LlmClient
import com.trading.bot.model.*
import com.trading.bot.repository.AgentLogRepository
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ContrarianAgent(
    private val llmClient: LlmClient,
    private val agentLogRepository: AgentLogRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}

    data class ChallengeReport(
        val isValid: Boolean,
        val riskLevel: String,
        val critique: String,
        val confidence: Double
    )

    private val systemPrompt = """
        Ты — контрариан (адвокат дьявола) в торговой системе.
        Твоя задача — найти слабые места в предлагаемой сделке и оценить её риск.
        riskLevel: LOW | MEDIUM | HIGH | CRITICAL.
        Ответь СТРОГО JSON: {"isValid":true,"riskLevel":"LOW","critique":"string","confidence":0.0}.
    """.trimIndent()

    suspend fun challenge(
        draft: StrategyAgent.Draft,
        tech: TechnicalReport,
        fund: FundamentalReport,
        snapshot: MarketSnapshot,
        cycleId: String
    ): ChallengeReport {
        val start = System.currentTimeMillis()

        if (draft.action == StrategyAction.HOLD) {
            return logAndReturn(
                ChallengeReport(isValid = true, riskLevel = "LOW", critique = "No position proposed", confidence = 1.0),
                snapshot.ticker, cycleId, start, "{}"
            )
        }

        val prompt = """
            Предлагаемая сделка: ${draft.action} ${draft.quantity} лотов по ${draft.targetPrice}
            Reasoning стратега: ${draft.reasoning}
            Технический анализ: ${tech.conclusion} (conf=${tech.confidence}), reasoning=${tech.reasoning}
            Фундаментальный анализ: ${fund.conclusion} (conf=${fund.confidence})
            Рыночная цена: ${snapshot.currentPrice}
            Критикуй сделку. Если есть высокие риски — isValid=false и riskLevel=HIGH/CRITICAL.
        """.trimIndent()

        val resp = llmClient.chat(systemPrompt, prompt, temperature = 0.1)
        val report = if (resp.isFallback) {
            logger.info { "LLM unavailable for challenge, allowing trade" }
            ChallengeReport(isValid = true, riskLevel = "LOW", critique = "LLM unavailable", confidence = 0.5)
        } else {
            try {
                val j = objectMapper.readTree(resp.content)
                ChallengeReport(
                    isValid = j.path("isValid").asBoolean(true),
                    riskLevel = j.path("riskLevel").asText("LOW").uppercase().let {
                        if (it in setOf("LOW", "MEDIUM", "HIGH", "CRITICAL")) it else "LOW"
                    },
                    critique = j.path("critique").asText(""),
                    confidence = j.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0)
                )
            } catch (e: Exception) {
                logger.warn(e) { "Contrarian LLM parse error" }
                ChallengeReport(isValid = true, riskLevel = "LOW", critique = "Parse error", confidence = 0.5)
            }
        }

        return logAndReturn(report, snapshot.ticker, cycleId, start, resp.content)
    }

    private fun logAndReturn(report: ChallengeReport, ticker: String, cycleId: String, startMs: Long, raw: String): ChallengeReport {
        agentLogRepository.save(
            AgentLog(
                cycleId = cycleId,
                agentName = "Agent-4-Contrarian",
                ticker = ticker,
                action = "CHALLENGE:${report.riskLevel}",
                confidence = report.confidence,
                reasoning = report.critique,
                rawOutput = raw,
                latencyMs = System.currentTimeMillis() - startMs
            )
        )
        return report
    }
}
