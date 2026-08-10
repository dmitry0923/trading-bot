package com.trading.bot.application.advisor

import com.trading.bot.domain.advisor.AdvisorRiskLevel
import com.trading.bot.domain.advisor.AdvisorVerdict
import com.trading.bot.domain.advisor.AdvisorVerdictType
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.infrastructure.llm.SemanticCache
import com.trading.bot.model.entity.AgentLog
import com.trading.bot.repository.AgentLogRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * LLM-советник (advisory layer, C-001).
 *
 * НЕ является источником торгового сигнала: направление сделки (BUY/SELL/HOLD)
 * всегда определяется детерминированными стратегиями. Советник оценивает уже
 * принятое решение и возвращает [AdvisorVerdict]:
 *  - AGREE — подтверждение с поправкой уверенности (в ограниченном диапазоне);
 *  - NEUTRAL — нет мнения, сигнал идёт без изменений;
 *  - VETO — блокировка входа (только при CRITICAL-уровне риска).
 *
 * Безопасность (fail-safe):
 *  - LLM недоступен / parse error -> [AdvisorVerdict.fallback] (NEUTRAL, fail-open);
 *  - LLM не может бесконтрольно повышать уверенность: поправка ограничена
 *    [confidenceAdjustmentRange] и обрезается на этапе [guardrail];
 *  - VETO из LLM без CRITICAL-риска не является блокировкой — понижается до
 *    NEUTRAL (блокировать вход может только детерминированный CRITICAL).
 *
 * Решение для журналирования пишется в AgentLogRepository, метрики — в
 * `advisor.*`. Цепочка агентов (DiscretionaryStrategy) остаётся для A/B-эксперимента
 * и аналитики, но вне критического исполнительного пути.
 */
@Component
class LlmAdvisor(
    private val llmClient: ResilientLlmClient,
    private val promptRegistry: PromptRegistry,
    private val semanticCache: SemanticCache,
    private val agentLogRepository: AgentLogRepository,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}

    /** Допустимая аддитивная поправка уверенности от LLM: -0.30..+0.15. */
    private val confidenceAdjustmentRange = -0.30..0.15

    /**
     * Оценивает детерминированное решение [decision] через LLM-советника.
     *
     * @param context контекст стратегического этапа (свечи, индикаторы, режим)
     * @param decision решение детерминированной стратегии (победитель StrategyRunner)
     * @param adaptiveConfidence адаптивный порог уверенности по тикеру
     * @param version версия LLM-шаблона промпта
     * @return [AdvisorVerdict] — никогда не бросает исключений (fail-open)
     */
    suspend fun advise(
        context: StrategyContext,
        decision: StrategyDecision,
        adaptiveConfidence: Double,
        version: String = PromptRegistry.DEFAULT_VERSION,
    ): AdvisorVerdict =
        coroutineScope {
            // HOLD не требует совета: нет входа — нечего подтверждать/блокировать.
            if (decision.action == com.trading.bot.model.StrategyAction.HOLD) {
                meterRegistry
                    .counter("advisor.skipped", Tags.of("ticker", context.ticker, "reason", "HOLD"))
                    .increment()
                return@coroutineScope AdvisorVerdict.NEUTRAL
            }

            val start = System.currentTimeMillis()
            val indicators = context.indicators
            val regime = context.regime

            val variables =
                mapOf(
                    "ticker" to context.ticker,
                    "signal" to decision.action.name,
                    "targetPrice" to decision.targetPrice.toPlainString(),
                    "signalConfidence" to String.format("%.2f", decision.confidence),
                    "signalReasoning" to decision.reasoning,
                    "currentPrice" to context.snapshot.currentPrice.toPlainString(),
                    "regime" to (regime?.describe() ?: "UNKNOWN"),
                    "trend" to (indicators?.trend ?: "UNKNOWN"),
                    "techConclusion" to (indicators?.conclusion ?: "UNKNOWN"),
                    "rsi" to String.format("%.1f", indicators?.rsi ?: 50.0),
                    "adaptiveThreshold" to String.format("%.2f", adaptiveConfidence),
                    "contextPrompt" to (context.contextPrompt ?: ""),
                )

            // Инвариантность кэша: сигнал + режим + бакеты RSI/тренда. Одинаковый
            // рыночный случай -> одинаковый ответ (стабильность совета).
            val fingerprint =
                semanticCache.genericFingerprint(
                    decision.action.name,
                    regime?.describe(),
                    indicators?.trend,
                    indicators?.conclusion,
                    String.format("%.1f", indicators?.rsi ?: 50.0),
                    String.format("%.2f", adaptiveConfidence),
                )

            val prompt = promptRegistry.getTemplate("advisor", version)
            val resp =
                llmClient.complete(
                    agent = "advisor",
                    ticker = context.ticker,
                    prompt = prompt,
                    variables = variables,
                    fingerprint = fingerprint,
                    temperature = 0.1,
                )

            val verdict =
                if (resp.isFallback) {
                    logger.info { "LLM unavailable for advisory on ${context.ticker}, signal proceeds unchanged" }
                    AdvisorVerdict.fallback("LLM_UNAVAILABLE")
                } else {
                    try {
                        guardrail(parseVerdict(resp.content))
                    } catch (e: Exception) {
                        logger.error(e) { "Advisor parse error for ${context.ticker}" }
                        meterRegistry.counter("advisor.parse.error").increment()
                        AdvisorVerdict.fallback("PARSE_ERROR: ${e.message}")
                    }
                }

            logAndReturn(verdict, context.ticker, context.cycleId, start, resp.content, resp.tokensUsed, resp.fromCache, resp.storageKey)
        }

    /**
     * Детерминированный guardrail для вердикта LLM:
     *  1. Поправка уверенности ограничивается [confidenceAdjustmentRange];
     *  2. VETO без CRITICAL-риска понижается до NEUTRAL (LLM не может блокировать
     *     вход произвольно — только детерминированный CRITICAL);
     *  3. CRITICAL-риск всегда означает VETO (fail-safe).
     */
    private fun guardrail(raw: AdvisorVerdict): AdvisorVerdict {
        val adjustment =
            raw.confidenceAdjustment.coerceIn(confidenceAdjustmentRange.start, confidenceAdjustmentRange.endInclusive)

        return when {
            raw.riskLevel == AdvisorRiskLevel.CRITICAL -> {
                val reason = raw.explanation.ifBlank { "CRITICAL risk level" } + " [GUARDRAIL: CRITICAL_RISK]"
                AdvisorVerdict
                    .veto(reason)
                    .copy(alternativeScenarios = raw.alternativeScenarios)
            }

            raw.verdict == AdvisorVerdictType.VETO -> {
                logger.warn { "Advisor VETO without CRITICAL risk downgraded to NEUTRAL: ${raw.explanation}" }
                meterRegistry.counter("advisor.guardrail.downgrade", Tags.of("reason", "VETO_WITHOUT_CRITICAL")).increment()
                AdvisorVerdict(
                    verdict = AdvisorVerdictType.NEUTRAL,
                    confidenceAdjustment = adjustment,
                    explanation = raw.explanation + " [GUARDRAIL: VETO_DOWNGRADED]",
                    alternativeScenarios = raw.alternativeScenarios,
                    riskLevel = raw.riskLevel,
                )
            }

            else -> {
                raw.copy(confidenceAdjustment = adjustment)
            }
        }
    }

    private fun parseVerdict(content: String): AdvisorVerdict {
        val cleaned = content.replace("```json", "").replace("```", "").trim()
        val j = objectMapper.readTree(cleaned)
        val verdict =
            AdvisorVerdictType.entries.firstOrNull {
                it.name == j.path("verdict").asString("NEUTRAL").uppercase()
            } ?: AdvisorVerdictType.NEUTRAL
        val riskLevel =
            AdvisorRiskLevel.entries.firstOrNull {
                it.name == j.path("riskLevel").asString("LOW").uppercase()
            } ?: AdvisorRiskLevel.LOW
        val scenariosNode = j.path("alternativeScenarios")
        val scenarios: List<String> =
            if (scenariosNode.isArray) {
                (scenariosNode as Iterable<JsonNode>).map { it.asString() }
            } else {
                emptyList()
            }
        return AdvisorVerdict(
            verdict = verdict,
            confidenceAdjustment = j.path("confidenceAdjustment").asDouble(0.0),
            explanation = j.path("explanation").asString(),
            alternativeScenarios = scenarios,
            riskLevel = riskLevel,
        )
    }

    private suspend fun logAndReturn(
        verdict: AdvisorVerdict,
        ticker: String,
        cycleId: String,
        startMs: Long,
        raw: String,
        tokensUsed: Int = 0,
        isCached: Boolean = false,
        storageKey: String? = null,
    ): AdvisorVerdict {
        agentLogRepository.save(
            AgentLog(
                cycleId = cycleId,
                agentName = "Agent-6-Advisor",
                ticker = ticker,
                action = verdict.verdict.name,
                confidence = verdict.confidenceAdjustment,
                reasoning = verdict.explanation,
                rawOutput = raw,
                latencyMs = System.currentTimeMillis() - startMs,
                tokensUsed = tokensUsed,
                isCached = isCached,
                overrideReason = if (verdict.blocksEntry) "ADVISOR_VETO" else null,
                storageKey = storageKey,
            ),
        )
        meterRegistry
            .counter(
                "advisor.decision",
                Tags.of("ticker", ticker, "verdict", verdict.verdict.name, "riskLevel", verdict.riskLevel.name),
            ).increment()
        logger.info {
            "Advisor $ticker: ${verdict.verdict} (risk=${verdict.riskLevel}, confAdj=${verdict.confidenceAdjustment}) blocks=${
                verdict.blocksEntry
            }"
        }
        return verdict
    }
}
