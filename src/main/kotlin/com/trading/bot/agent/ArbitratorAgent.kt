package com.trading.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.client.LlmClient
import com.trading.bot.model.*
import com.trading.bot.repository.AgentLogRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class ArbitratorAgent(
    private val llmClient: LlmClient,
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

    private val systemPrompt = """
        Ты — главный арбитр торговой системы. Финальное решение.
        Будь консервативен: лучше пропустить сделку, чем войти в плохую.
        Учитывай контраргументы контрариана.
        Ответь СТРОГО JSON: {"action":"BUY|SELL|HOLD","targetPrice":0.0,"quantity":0,"stopLoss":0.0,"takeProfit":0.0,"trailingStop":false,"confidence":0.0,"reasoning":"string"}.
    """.trimIndent()

    suspend fun adjudicate(
        draft: StrategyAgent.Draft,
        challenge: ContrarianAgent.ChallengeReport,
        tech: TechnicalReport,
        fund: FundamentalReport,
        snapshot: MarketSnapshot,
        cycleId: String,
        contextPrompt: String? = null,
        adaptiveConfidence: Double = 0.60
    ): Final = coroutineScope {
        val start = System.currentTimeMillis()

        // DETERMINISTIC OVERRIDES — не подлежат обсуждению
        if (challenge.riskLevel == "CRITICAL") {
            meterRegistry.counter("arbitrator.deterministic.override", Tags.of("reason", "CRITICAL_CHALLENGE")).increment()
            return@coroutineScope logAndReturn(
                hold(snapshot.currentPrice, "Blocked by Contrarian: ${challenge.critique}", "DETERMINISTIC: CRITICAL_CHALLENGE"),
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

        val prompt = """
            STRATEGY: ${draft.action} @ ${draft.targetPrice} qty=${draft.quantity} conf=${String.format("%.2f", draft.confidence)}.
            Reasoning: ${draft.reasoning}.
            COUNTERARGUMENTS: ${challenge.riskLevel} — ${challenge.critique}.
            Tech: ${tech.conclusion} (${tech.trend}, RSI=${String.format("%.1f", tech.rsi)}).
            Fund: ${fund.conclusion}.
            Price: ${snapshot.currentPrice}.
            Adaptive confidence threshold: $adaptiveConfidence.
            $memoryBlock

            RULES:
            1. Если counterarguments сильные (riskLevel HIGH) или конфиденс ниже $adaptiveConfidence — HOLD.
            2. Если сделка всё же оправдана — подтверди action с обоснованием.
        """.trimIndent()

        val resp = llmClient.chat(systemPrompt, prompt, temperature = 0.1)
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

        val guarded = applyGuardrails(dec, snapshot, adaptiveConfidence)
        logAndReturn(guarded, snapshot.ticker, cycleId, start, resp.content)
    }

    private fun applyGuardrails(dec: Final, snapshot: MarketSnapshot, threshold: Double): Final {
        if (dec.action != StrategyAction.HOLD && dec.confidence < threshold) {
            return dec.copy(
                action = StrategyAction.HOLD,
                quantity = 0,
                reasoning = dec.reasoning + " [GUARDRAIL: confidence ${dec.confidence} < $threshold]",
                overrideReason = "GUARDRAIL: LOW_CONFIDENCE"
            )
        }
        val deviation = dec.targetPrice.subtract(snapshot.currentPrice).abs()
            .divide(snapshot.currentPrice, 4, RoundingMode.HALF_UP)
        if (deviation > BigDecimal("0.03")) {
            return dec.copy(
                targetPrice = snapshot.currentPrice,
                reasoning = dec.reasoning + " [GUARDRAIL: price adjusted to market]",
                overrideReason = "GUARDRAIL: PRICE_DEVIATION"
            )
        }
        return dec
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

    private fun logAndReturn(dec: Final, ticker: String, cycleId: String, startMs: Long, raw: String): Final {
        agentLogRepository.save(
            AgentLog(
                cycleId = cycleId,
                agentName = "Agent-5-Arbitrator",
                ticker = ticker,
                action = dec.action.name,
                confidence = dec.confidence,
                reasoning = dec.reasoning,
                rawOutput = raw,
                latencyMs = System.currentTimeMillis() - startMs
            )
        )
        meterRegistry.counter("agent.arbitrator.decision", Tags.of("action", dec.action.name)).increment()
        logger.info { "Agent 5 FINAL: ${dec.action} @ ${dec.targetPrice} conf=${String.format("%.2f", dec.confidence)} override=${dec.overrideReason}" }
        return dec
    }
}
