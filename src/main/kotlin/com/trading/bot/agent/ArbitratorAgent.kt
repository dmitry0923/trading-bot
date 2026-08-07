package com.trading.bot.agent

import com.trading.bot.infrastructure.llm.Guardrails
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.infrastructure.llm.SemanticCache
import com.trading.bot.model.AgentLog
import com.trading.bot.model.FundamentalReport
import com.trading.bot.model.MarketSnapshot
import com.trading.bot.model.RiskContext
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.TechnicalReport
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
 * - Детерминированные overrides ДО LLM: CRITICAL challenge, пауза риск-менеджера,
 *   дневной лимит убытка, Shadow/Read-only режим (серия убытков), HOLD стратега,
 *   низкая уверенность draft
 * - Вызов LLM с контекстом памяти о последних сделках (memoryBlock)
 * - Пост-обработка через Guardrails (адаптивный порог, риск-уровень, лимит убытка)
 * - Кэширует результат по сигналу + риску (SemanticCache)
 * - Пишет лог в AgentLogRepository и метрики agent.arbitrator.decision
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
        val quantity: Int,
        val stopLoss: BigDecimal?,
        val takeProfit: BigDecimal?,
        val trailingStop: Boolean,
        val confidence: Double,
        val reasoning: String,
        val overrideReason: String? = null,
    )

    /**
     * Выносит финальное решение по сделке с учётом challenge и риск-контекста.
     *
     * @param draft черновик стратега
     * @param challenge оценка контрариан-агента
     * @param tech отчёт технического анализа
     * @param fund отчёт фундаментального анализа
     * @param snapshot текущий рыночный снапшот
     * @param cycleId идентификатор торгового цикла
     * @param contextPrompt контекст памяти о последних результатах сделок (может быть null)
     * @param adaptiveConfidence адаптивный порог уверенности
     * @param riskContext текущий риск-контекст (пауза, дневной лимит убытка)
     * @param bypassCache обходит semantic cache (нужно для A/B: вариантный арбитр
     *                     с другим версией промпта не должен получать кэшированный ответ
     *                     контрольной руки — иначе эксперимент бессмыслен)
     * @param version версия LLM-шаблона промпта
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
        riskContext: RiskContext = RiskContext(),
        version: String = PromptRegistry.DEFAULT_VERSION,
        bypassCache: Boolean = false,
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

            if (riskContext.shouldPause) {
                meterRegistry.counter("arbitrator.deterministic.override", Tags.of("reason", "RISK_CONTEXT_PAUSE")).increment()
                return@coroutineScope logAndReturn(
                    hold(snapshot.currentPrice, "Trading paused by adaptive risk", "DETERMINISTIC: RISK_CONTEXT_PAUSE"),
                    snapshot.ticker,
                    cycleId,
                    start,
                    "{}",
                )
            }

            if (riskContext.dailyLossLimitReached) {
                meterRegistry.counter("arbitrator.deterministic.override", Tags.of("reason", "DAILY_LOSS_LIMIT")).increment()
                return@coroutineScope logAndReturn(
                    hold(snapshot.currentPrice, "Daily loss limit reached", "DETERMINISTIC: DAILY_LOSS_LIMIT"),
                    snapshot.ticker,
                    cycleId,
                    start,
                    "{}",
                )
            }

            if (riskContext.shadowMode) {
                meterRegistry.counter("arbitrator.deterministic.override", Tags.of("reason", "SHADOW_MODE")).increment()
                return@coroutineScope logAndReturn(
                    hold(
                        snapshot.currentPrice,
                        "LLM agent in SHADOW/READ-ONLY mode (consecutive losses); decision logged, not executed",
                        "DETERMINISTIC: SHADOW_MODE",
                    ),
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
                    "quantity" to draft.quantity,
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
                    temperature = 0.1,
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
                            quantity = dec.quantity,
                            stopLoss = dec.stopLoss,
                            takeProfit = dec.takeProfit,
                            trailingStop = dec.trailingStop,
                            confidence = dec.confidence,
                        ),
                    marketPrice = snapshot.currentPrice,
                    adaptiveThreshold = adaptiveConfidence,
                    riskLevel = challenge.riskLevel,
                    dailyLossLimitReached = riskContext.dailyLossLimitReached,
                    shadowMode = riskContext.shadowMode,
                )

            val finalDec =
                if (guarded.overridden) {
                    dec.copy(
                        action = guarded.signal.action,
                        targetPrice = guarded.signal.targetPrice,
                        quantity = guarded.signal.quantity,
                        stopLoss = guarded.signal.stopLoss,
                        takeProfit = guarded.signal.takeProfit,
                        trailingStop = guarded.signal.trailingStop,
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
            quantity = if (action == StrategyAction.HOLD) 0 else j.path("quantity").asInt(fallback.quantity).coerceIn(1, 10000),
            stopLoss = j.path("stopLoss").asString().toBigDecimalOrNull() ?: fallback.stopLoss,
            takeProfit = j.path("takeProfit").asString().toBigDecimalOrNull() ?: fallback.takeProfit,
            trailingStop = j.path("trailingStop").asBoolean(fallback.trailingStop),
            confidence = j.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0),
            reasoning = j.path("reasoning").asString(fallback.reasoning),
        )
    }

    private fun hold(
        price: BigDecimal,
        reason: String,
        overrideReason: String?,
    ): Final = Final(StrategyAction.HOLD, price, 0, null, null, false, 0.0, reason, overrideReason)

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
