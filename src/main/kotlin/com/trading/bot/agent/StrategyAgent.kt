package com.trading.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.client.LlmClient
import com.trading.bot.model.*
import com.trading.bot.repository.AgentLogRepository
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class StrategyAgent(
    private val llmClient: LlmClient,
    private val agentLogRepository: AgentLogRepository,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}

    data class Draft(
        val action: StrategyAction,
        val targetPrice: BigDecimal,
        val quantity: Int,
        val stopLoss: BigDecimal?,
        val takeProfit: BigDecimal?,
        val trailingStop: Boolean,
        val confidence: Double,
        val reasoning: String
    )

    private val systemPrompt = """
        Ты — стратег алгоритмического торгового бота для Мосбиржи.
        На основе технического и фундаментального анализа прими решение.
        Правила:
        1. Торгуй только при явном согласии анализа (обе стороны BULLISH/BEARISH или одна сторона очень уверена).
        2. При сомнении — HOLD. Лучше пропустить сделку, чем войти в плохую.
        3. quantity — количество лотов.
        Ответь СТРОГО JSON: {"action":"BUY|SELL|HOLD","targetPrice":0.0,"quantity":0,"stopLoss":0.0,"takeProfit":0.0,"trailingStop":false,"confidence":0.0,"reasoning":"string"}.
    """.trimIndent()

    suspend fun formulate(
        ticker: String,
        tech: TechnicalReport,
        fund: FundamentalReport,
        snapshot: MarketSnapshot,
        cycleId: String
    ): Draft {
        val start = System.currentTimeMillis()

        if (tech.conclusion == "INSUFFICIENT_DATA" || tech.confidence < 0.5) {
            return logAndReturn(hold(snapshot.currentPrice, "Insufficient technical data (conf=${tech.confidence})"), ticker, cycleId, start, "{}")
        }

        val prompt = """
            ТИКЕР: $ticker
            Текущая цена: ${snapshot.currentPrice}
            Технический анализ: conclusion=${tech.conclusion}, confidence=${tech.confidence}, trend=${tech.trend}, RSI=${tech.rsi}, reasoning=${tech.reasoning}
            Фундаментальный анализ: conclusion=${fund.conclusion}, confidence=${fund.confidence}, reasoning=${fund.reasoning}
        """.trimIndent()

        val resp = llmClient.chat(systemPrompt, prompt, temperature = 0.15)
        if (resp.isFallback) {
            logger.info { "LLM unavailable for $ticker, HOLD" }
            return logAndReturn(hold(snapshot.currentPrice, "LLM unavailable"), ticker, cycleId, start, resp.content)
        }

        return try {
            val cleaned = resp.content.replace("```json", "").replace("```", "").trim()
            val j = objectMapper.readTree(cleaned)
            val action = StrategyAction.values().firstOrNull {
                it.name == j.path("action").asText("HOLD").uppercase()
            } ?: StrategyAction.HOLD

            val rawPrice = j.path("targetPrice").asText().toBigDecimalOrNull() ?: snapshot.currentPrice
            val targetPrice = guardPrice(rawPrice, snapshot.currentPrice, ticker)

            val draft = Draft(
                action = action,
                targetPrice = targetPrice,
                quantity = if (action == StrategyAction.HOLD) 0 else j.path("quantity").asInt(0).coerceIn(1, 10000),
                stopLoss = j.path("stopLoss").asText().toBigDecimalOrNull(),
                takeProfit = j.path("takeProfit").asText().toBigDecimalOrNull(),
                trailingStop = j.path("trailingStop").asBoolean(false),
                confidence = j.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0),
                reasoning = j.path("reasoning").asText("")
            )
            logAndReturn(draft, ticker, cycleId, start, resp.content)
        } catch (e: Exception) {
            logger.warn(e) { "Strategy LLM parse error for $ticker" }
            meterRegistry.counter("strategy.agent.parse.error", io.micrometer.core.instrument.Tags.of("ticker", ticker)).increment()
            logAndReturn(hold(snapshot.currentPrice, "Parse error: ${e.message}"), ticker, cycleId, start, resp.content)
        }
    }

    private fun guardPrice(proposed: BigDecimal, market: BigDecimal, ticker: String): BigDecimal {
        val deviation = proposed.subtract(market).abs().divide(market, 4, RoundingMode.HALF_UP)
        return if (deviation > BigDecimal("0.02")) {
            logger.warn { "Guardrail: price deviation ${deviation}% too high for $ticker, using market price" }
            market
        } else proposed
    }

    private fun hold(marketPrice: BigDecimal, reason: String): Draft =
        Draft(StrategyAction.HOLD, marketPrice, 0, null, null, false, 0.0, reason)

    private fun logAndReturn(draft: Draft, ticker: String, cycleId: String, startMs: Long, raw: String): Draft {
        agentLogRepository.save(
            AgentLog(
                cycleId = cycleId,
                agentName = "Agent-3-Strategist",
                ticker = ticker,
                action = draft.action.name,
                confidence = draft.confidence,
                reasoning = draft.reasoning,
                rawOutput = raw,
                latencyMs = System.currentTimeMillis() - startMs
            )
        )
        return draft
    }
}
