package com.trading.bot.agent

import com.trading.bot.infrastructure.llm.Guardrails
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
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal

/**
 * Арбитр (Agent-5) — финальное решение о сделке.
 *
 * - Детерминированные overrides ДО LLM: CRITICAL challenge, HOLD стратега,
 *   низкая уверенность draft
 * - Вызов LLM с контекстом памяти о последних сделках (memoryBlock)
 * - Пост-обработка через Guardrails (адаптивный порог)
 * - Кэширует результат по сигналу + риску (SemanticCache)
 * - Пишет лог в AgentLogRepository и метрики agent.arbitrator.decision
 *
 * Final несёт ТОЛЬКО направление (BUY/SELL/HOLD), целевую цену, уверенность и
 * обоснование. Паузы/лимиты/Shadow-режим — это этап RiskEngine; размер и стопы —
 * этап Sizer/OrderBuilder (не здесь).
 */
@Component
class ArbitratorAgent(
    private val llmClient: ResilientLlmClient,
    private val promptRegistry: PromptRegistry,
    private val guardrails: Guardrails,
    private val semanticCache: SemanticCache,
    private val agentLogRepository: AgentLogRepository,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}

    data class Final(
        val action: StrategyAction,
        val targetPrice: BigDecimal,
        val confidence: Double,
        val reasoning: String,
        val overrideReason: String? = null,
    )

    /**
     * Выносит финальное решение по сделке с учётом challenge.
     *
     * @param draft черновик стратега
     * @param challenge оценка контрариан-агента
     * @param tech отчёт технического анализа
     * @param fund отчёт фундаментального анализа
     * @param snapshot текущий рыночный снапшот
     * @param cycleId идентификатор торгового цикла
     * @param contextPrompt контекст памяти о последних результатах сделок (может быть null)
     * @param adaptiveConfidence адаптивный порог уверенности
     * @param bypassCache обходит semantic cache (нужно для A/B: вариантный арбитр
     *                     с другим версией промпта не должен получать кэшированный ответ
     *                     контрольной руки — иначе эксперимент бессмыслен)
     * @param version версия LLM-шаблона промпта
     * @param temperature температура генерации (live-путь 0.1, бэктест — 0.0)
     * @param cacheNamespace изолирует semantic cache (бэктест: "backtest")
     * @return финальное решение (Final)
     */
    suspend fun adjudicate(
        draft: StrategyAgent.Draft,
        challenge: ContrarianAgent.ChallengeReport,
        tech: TechnicalReport,
        fund: FundamentalReport,
        snapshot: MarketSnapshot,
        cycleId: String,
        contextPrompt: String? = null,
        adaptiveConfidence: Double = 0.60,
        version: String = PromptRegistry.DEFAULT_VERSION,
        bypassCache: Boolean = false,
        temperature: Double = 0.1,
        cacheNamespace: String? = null,
    ): Final =
        coroutineScope {
            val start = System.currentTimeMillis()

            // ===== DETERMINISTIC OVERRIDES — выполняются ДО LLM, не подлежат обсуждению =====
            if (challenge.riskLevel == "CRITICAL") {
                meterRegistry.counter("arbitrator.deterministic.override", Tags.of("reason", "CRITICAL_CHALLENGE")).increment()
                return@coroutineScope logAndReturn(
                    hold(snapshot.currentPrice, "Blocked by Contrarian: ${challenge.critique}", "DETERMINISTIC: CRITICAL_CHALLENGE"),
                    snapshot.ticker,
                    cycleId,
                    start,
                    "{}",
                )
            }

            if (draft.action == StrategyAction.HOLD) {
                return@coroutineScope logAndReturn(
                    hold(snapshot.currentPrice, "Strategist recommended HOLD: ${draft.reasoning}", null),
                    snapshot.ticker,
                    cycleId,
                    start,
                    "{}",
                )
            }

            if (draft.confidence < adaptiveConfidence) {
                meterRegistry.counter("arbitrator.deterministic.override", Tags.of("reason", "LOW_DRAFT_CONFIDENCE")).increment()
                return@coroutineScope logAndReturn(
                    hold(
                        snapshot.currentPrice,
                        "Draft confidence ${draft.confidence} < threshold $adaptiveConfidence",
                        "DETERMINISTIC: LOW_DRAFT_CONFIDENCE",
                    ),
                    snapshot.ticker,
                    cycleId,
                    start,
                    "{}",
                )
            }

            val memoryBlock = contextPrompt?.let { "\n\nCONTEXT MEMORY (recent trades results):\n$it" } ?: ""

            val variables =
                mapOf(
                    "action" to draft.action.name,
                    "targetPrice" to draft.targetPrice.toPlainString(),
                    "confidence" to String.format("%.2f", draft.confidence),
                    "strategyReasoning" to draft.reasoning,
                    "riskLevel" to challenge.riskLevel,
                    "critique" to challenge.critique,
                    "techConclusion" to tech.conclusion,
                    "techTrend" to tech.trend,
                    "techRsi" to String.format("%.1f", tech.rsi),
                    "fundConclusion" to fund.conclusion,
                    "currentPrice" to snapshot.currentPrice.toPlainString(),
                    "adaptiveThreshold" to adaptiveConfidence,
                    "memoryBlock" to memoryBlock,
                )

            // Арбитр решает один и тот же кейс одинаково — кэшируем по сигналу + риску
            // (кроме A/B-вызова: bypassCache=true, чтобы вариантная рука не получила
            // кэшированный ответ контрольной).
            val fingerprint =
                if (bypassCache) {
                    null
                } else {
                    semanticCache.genericFingerprint(
                        draft.action.name,
                        challenge.riskLevel,
                        tech.conclusion,
                        tech.trend,
                        String.format("%.1f", tech.rsi),
                        String.format("%.2f", adaptiveConfidence),
                    )
                }

            val prompt = promptRegistry.getTemplate("arbitrator", version)
            val resp =
                llmClient.complete(
                    agent = "arbitrator",
                    ticker = snapshot.ticker,
                    prompt = prompt,
                    variables = variables,
                    fingerprint = fingerprint,
                    temperature = temperature,
                    cacheNamespace = cacheNamespace,
                )

            val dec =
                if (resp.isFallback) {
                    logger.info { "LLM unavailable for arbitration of ${snapshot.ticker}, HOLD" }
                    hold(snapshot.currentPrice, "LLM unavailable", "FALLBACK: LLM_UNAVAILABLE")
                } else {
                    try {
                        parseFinal(resp.content, draft)
                    } catch (e: Exception) {
                        logger.error(e) { "Final parse error" }
                        meterRegistry.counter("agent.arbitrator.parse.error").increment()
                        hold(snapshot.currentPrice, "Parse error: ${e.message}", "FALLBACK: PARSE_ERROR")
                    }
                }

            // ===== POST-PROCESSING GUARDRAILS =====
            val guarded =
                guardrails.apply(
                    signal =
                        Guardrails.Signal(
                            action = dec.action,
                            targetPrice = dec.targetPrice,
                            confidence = dec.confidence,
                        ),
                    marketPrice = snapshot.currentPrice,
                    adaptiveThreshold = adaptiveConfidence,
                    riskLevel = challenge.riskLevel,
                )

            val finalDec =
                if (guarded.overridden) {
                    dec.copy(
                        action = guarded.signal.action,
                        targetPrice = guarded.signal.targetPrice,
                        confidence = guarded.signal.confidence,
                        reasoning = dec.reasoning + " [GUARDRAIL: ${guarded.overrideReason}]",
                        overrideReason = guarded.overrideReason,
                    )
                } else {
                    dec
                }

            logAndReturn(finalDec, snapshot.ticker, cycleId, start, resp.content, resp.tokensUsed, resp.fromCache, resp.storageKey)
        }

    private fun parseFinal(
        c: String,
        fallback: StrategyAgent.Draft,
    ): Final {
        val cleaned = c.replace("```json", "").replace("```", "").trim()
        val j = objectMapper.readTree(cleaned)
        val action =
            StrategyAction.entries.firstOrNull {
                it.name == j.path("action").asString("HOLD").uppercase()
            } ?: StrategyAction.HOLD
        return Final(
            action = action,
            targetPrice = j.path("targetPrice").asString().toBigDecimalOrNull() ?: fallback.targetPrice,
            confidence = j.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0),
            reasoning = j.path("reasoning").asString(fallback.reasoning),
        )
    }

    private fun hold(
        price: BigDecimal,
        reason: String,
        overrideReason: String?,
    ): Final = Final(StrategyAction.HOLD, price, 0.0, reason, overrideReason)

    private suspend fun logAndReturn(
        dec: Final,
        ticker: String,
        cycleId: String,
        startMs: Long,
        raw: String,
        tokensUsed: Int = 0,
        isCached: Boolean = false,
        storageKey: String? = null,
    ): Final {
        agentLogRepository.save(
            AgentLog(
                cycleId = cycleId,
                agentName = "Agent-5-Arbitrator",
                ticker = ticker,
                action = dec.action.name,
                confidence = dec.confidence,
                reasoning = dec.reasoning,
                rawOutput = raw,
                latencyMs = System.currentTimeMillis() - startMs,
                tokensUsed = tokensUsed,
                isCached = isCached,
                overrideReason = dec.overrideReason,
                storageKey = storageKey,
            ),
        )
        meterRegistry.counter("agent.arbitrator.decision", Tags.of("action", dec.action.name)).increment()
        logger.info {
            "Agent 5 FINAL: ${dec.action} @ ${dec.targetPrice} conf=${String.format("%.2f", dec.confidence)} override=${dec.overrideReason}"
        }
        return dec
    }
}
