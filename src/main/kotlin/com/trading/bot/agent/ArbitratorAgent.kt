package com.trading.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.infrastructure.llm.Guardrails
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.model.*
import com.trading.bot.repository.AgentLogRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class ArbitratorAgent(
    private val llmClient: ResilientLlmClient,
    private val promptRegistry: PromptRegistry,
    private val guardrails: Guardrails,
    private val agentLogRepository: AgentLogRepository,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper
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
        val overrideReason: String? = null
    )

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
        version: String = PromptRegistry.DEFAULT_VERSION
    ): Final = coroutineScope {
        val start = System.currentTimeMillis()

        // ===== DETERMINISTIC OVERRIDES — выполняются ДО LLM, не подлежат обсуждению =====
        if (challenge.riskLevel == "CRITICAL") {
            meterRegistry.counter("arbitrator.deterministic.override", Tags.of("reason", "CRITICAL_CHALLENGE")).increment()
            return@coroutineScope logAndReturn(
                hold(snapshot.currentPrice, "Blocked by Contrarian: ${challenge.critique}", "DETERMINISTIC: CRITICAL_CHALLENGE"),
                snapshot.ticker, cycleId, start, "{}"
            )
        }

        if (riskContext.shouldPause) {
            meterRegistry.counter("arbitrator.deterministic.override", Tags.of("reason", "RISK_CONTEXT_PAUSE")).increment()
            return@coroutineScope logAndReturn(
                hold(snapshot.currentPrice, "Trading paused by adaptive risk", "DETERMINISTIC: RISK_CONTEXT_PAUSE"),
                snapshot.ticker, cycleId, start, "{}"
            )
        }

        if (riskContext.dailyLossLimitReached) {
            meterRegistry.counter("arbitrator.deterministic.override", Tags.of("reason", "DAILY_LOSS_LIMIT")).increment()
            return@coroutineScope logAndReturn(
                hold(snapshot.currentPrice, "Daily loss limit reached", "DETERMINISTIC: DAILY_LOSS_LIMIT"),
                snapshot.ticker, cycleId, start, "{}"
            )
        }

        if (draft.action == StrategyAction.HOLD) {
            return@coroutineScope logAndReturn(
                hold(snapshot.currentPrice, "Strategist recommended HOLD: ${draft.reasoning}", null),
                snapshot.ticker, cycleId, start, "{}"
            )
        }

        if (draft.confidence < adaptiveConfidence) {
            meterRegistry.counter("arbitrator.deterministic.override", Tags.of("reason", "LOW_DRAFT_CONFIDENCE")).increment()
            return@coroutineScope logAndReturn(
                hold(snapshot.currentPrice, "Draft confidence ${draft.confidence} < threshold $adaptiveConfidence", "DETERMINISTIC: LOW_DRAFT_CONFIDENCE"),
                snapshot.ticker, cycleId, start, "{}"
            )
        }

        val memoryBlock = contextPrompt?.let { "\n\nCONTEXT MEMORY (recent trades results):\n$it" } ?: ""

        val variables = mapOf(
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
            "memoryBlock" to memoryBlock
        )

        val prompt = promptRegistry.getTemplate("arbitrator", version)
        val resp = llmClient.complete(
            agent = "arbitrator",
            ticker = snapshot.ticker,
            prompt = prompt,
            variables = variables,
            temperature = 0.1
        )

        val dec = if (resp.isFallback) {
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
        val guarded = guardrails.apply(
            signal = Guardrails.Signal(
                action = dec.action,
                targetPrice = dec.targetPrice,
                quantity = dec.quantity,
                stopLoss = dec.stopLoss,
                takeProfit = dec.takeProfit,
                trailingStop = dec.trailingStop,
                confidence = dec.confidence
            ),
            marketPrice = snapshot.currentPrice,
            adaptiveThreshold = adaptiveConfidence,
            riskLevel = challenge.riskLevel,
            dailyLossLimitReached = riskContext.dailyLossLimitReached
        )

        val finalDec = if (guarded.overridden) {
            dec.copy(
                action = guarded.signal.action,
                targetPrice = guarded.signal.targetPrice,
                quantity = guarded.signal.quantity,
                stopLoss = guarded.signal.stopLoss,
                takeProfit = guarded.signal.takeProfit,
                trailingStop = guarded.signal.trailingStop,
                confidence = guarded.signal.confidence,
                reasoning = dec.reasoning + " [GUARDRAIL: ${guarded.overrideReason}]",
                overrideReason = guarded.overrideReason
            )
        } else {
            dec
        }

        logAndReturn(finalDec, snapshot.ticker, cycleId, start, resp.content, resp.tokensUsed, resp.fromCache)
    }

    private fun parseFinal(c: String, fallback: StrategyAgent.Draft): Final {
        val cleaned = c.replace("```json", "").replace("```", "").trim()
        val j = objectMapper.readTree(cleaned)
        val action = StrategyAction.values().firstOrNull {
            it.name == j.path("action").asText("HOLD").uppercase()
        } ?: StrategyAction.HOLD
        return Final(
            action = action,
            targetPrice = j.path("targetPrice").asText().toBigDecimalOrNull() ?: fallback.targetPrice,
            quantity = if (action == StrategyAction.HOLD) 0 else j.path("quantity").asInt(fallback.quantity).coerceIn(1, 10000),
            stopLoss = j.path("stopLoss").asText().toBigDecimalOrNull() ?: fallback.stopLoss,
            takeProfit = j.path("takeProfit").asText().toBigDecimalOrNull() ?: fallback.takeProfit,
            trailingStop = j.path("trailingStop").asBoolean(fallback.trailingStop),
            confidence = j.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0),
            reasoning = j.path("reasoning").asText(fallback.reasoning)
        )
    }

    private fun hold(price: BigDecimal, reason: String, overrideReason: String?): Final =
        Final(StrategyAction.HOLD, price, 0, null, null, false, 0.0, reason, overrideReason)

    private fun logAndReturn(
        dec: Final,
        ticker: String,
        cycleId: String,
        startMs: Long,
        raw: String,
        tokensUsed: Int = 0,
        isCached: Boolean = false
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
                overrideReason = dec.overrideReason
            )
        )
        meterRegistry.counter("agent.arbitrator.decision", Tags.of("action", dec.action.name)).increment()
        logger.info { "Agent 5 FINAL: ${dec.action} @ ${dec.targetPrice} conf=${String.format("%.2f", dec.confidence)} override=${dec.overrideReason}" }
        return dec
    }
}
