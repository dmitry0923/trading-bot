package com.trading.bot.agent

import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.infrastructure.llm.SemanticCache
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.dto.TechnicalReport
import com.trading.bot.model.entity.AgentLog
import com.trading.bot.repository.AgentLogRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Контрариан-агент (Agent-4) — «адвокат дьявола».
 *
 * - Оспаривает черновик стратега: валидность, уровень риска и критика
 * - Guardrail: при HOLD-черновике не вызывает LLM, риск LOW
 * - При недоступности LLM разрешает сделку (isValid=true, riskLevel=LOW)
 * - Кэширует результат по семантическому отпечатку рынка (SemanticCache)
 * - Пишет лог в AgentLogRepository и метрики agent.contrarian.decision
 */
@Component
class ContrarianAgent(
    private val llmClient: ResilientLlmClient,
    private val promptRegistry: PromptRegistry,
    private val semanticCache: SemanticCache,
    private val agentLogRepository: AgentLogRepository,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}

    data class ChallengeReport(
        val isValid: Boolean,
        val riskLevel: String,
        val critique: String,
        val confidence: Double,
    )

    /**
     * Оспаривает черновик стратега и возвращает оценку риска сделки.
     *
     * @param draft черновик стратега
     * @param tech отчёт технического анализа
     * @param fund отчёт фундаментального анализа
     * @param snapshot текущий рыночный снапшот
     * @param cycleId идентификатор торгового цикла
     * @param version версия LLM-шаблона промпта
     * @return отчёт о валидности, уровне риска и критике
     */
    suspend fun challenge(
        draft: StrategyAgent.Draft,
        tech: TechnicalReport,
        fund: FundamentalReport,
        snapshot: MarketSnapshot,
        cycleId: String,
        version: String = PromptRegistry.DEFAULT_VERSION,
    ): ChallengeReport {
        val start = System.currentTimeMillis()

        // GUARDRAIL: если стратег сказал HOLD — LLM не вызываем, риск низкий
        if (draft.action == StrategyAction.HOLD) {
            return logAndReturn(
                ChallengeReport(isValid = true, riskLevel = "LOW", critique = "No position proposed", confidence = 1.0),
                snapshot.ticker,
                cycleId,
                start,
                "{}",
            )
        }

        val variables =
            mapOf(
                "action" to draft.action.name,
                "quantity" to draft.quantity,
                "targetPrice" to draft.targetPrice.toPlainString(),
                "strategyReasoning" to draft.reasoning,
                "techConclusion" to tech.conclusion,
                "techConfidence" to tech.confidence,
                "techReasoning" to tech.reasoning,
                "fundConclusion" to fund.conclusion,
                "fundConfidence" to fund.confidence,
                "currentPrice" to snapshot.currentPrice.toPlainString(),
                "trend" to tech.trend,
                "rsi" to tech.rsi,
                "atr" to tech.atr,
            )

        // Одинаковый сигнал при том же рынке -> одинаковый challenge (кэш)
        val fingerprint =
            semanticCache.fingerprint(
                snapshot.currentPrice,
                tech.rsi,
                tech.trend,
                "contrarian",
                macdHistogram = tech.macd,
            )

        val prompt = promptRegistry.getTemplate("contrarian", version)
        val resp =
            llmClient.complete(
                agent = "contrarian",
                ticker = snapshot.ticker,
                prompt = prompt,
                variables = variables,
                fingerprint = fingerprint,
                temperature = 0.1,
            )

        val report =
            if (resp.isFallback) {
                logger.info { "LLM unavailable for challenge, allowing trade" }
                ChallengeReport(isValid = true, riskLevel = "LOW", critique = "LLM unavailable", confidence = 0.5)
            } else {
                try {
                    val j = objectMapper.readTree(resp.content)
                    ChallengeReport(
                        isValid = j.path("isValid").asBoolean(true),
                        riskLevel =
                            j.path("riskLevel").asString("LOW").uppercase().let {
                                if (it in setOf("LOW", "MEDIUM", "HIGH", "CRITICAL")) it else "LOW"
                            },
                        critique = j.path("critique").asString(""),
                        confidence = j.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0),
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Contrarian LLM parse error" }
                    ChallengeReport(isValid = true, riskLevel = "LOW", critique = "Parse error", confidence = 0.5)
                }
            }

        return logAndReturn(report, snapshot.ticker, cycleId, start, resp.content, resp.tokensUsed, resp.fromCache, resp.storageKey)
    }

    private suspend fun logAndReturn(
        report: ChallengeReport,
        ticker: String,
        cycleId: String,
        startMs: Long,
        raw: String,
        tokensUsed: Int = 0,
        isCached: Boolean = false,
        storageKey: String? = null,
    ): ChallengeReport {
        agentLogRepository.save(
            AgentLog(
                cycleId = cycleId,
                agentName = "Agent-4-Contrarian",
                ticker = ticker,
                action = "CHALLENGE:${report.riskLevel}",
                confidence = report.confidence,
                reasoning = report.critique,
                rawOutput = raw,
                latencyMs = System.currentTimeMillis() - startMs,
                tokensUsed = tokensUsed,
                isCached = isCached,
                storageKey = storageKey,
            ),
        )
        meterRegistry.counter("agent.contrarian.decision", Tags.of("riskLevel", report.riskLevel)).increment()
        return report
    }
}
