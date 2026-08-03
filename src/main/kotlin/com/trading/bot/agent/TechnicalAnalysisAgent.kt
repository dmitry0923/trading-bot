package com.trading.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.client.LlmClient
import com.trading.bot.model.AgentLog
import com.trading.bot.model.Candle
import com.trading.bot.model.MarketSnapshot
import com.trading.bot.model.TechnicalReport
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.service.IndicatorCalculator
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class TechnicalAnalysisAgent(
    private val llmClient: LlmClient,
    private val agentLogRepository: AgentLogRepository,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}

    private val systemPrompt = """
        Ты — ведущий технический аналитик Московской биржи.
        Опирайся ТОЛЬКО на предоставленные индикаторы.
        Ответь СТРОГО JSON: {"conclusion":"BULLISH|BEARISH|NEUTRAL","confidence":0.0,"reasoning":"string"}.
    """.trimIndent()

    suspend fun analyze(ticker: String, candles: List<Candle>, snapshot: MarketSnapshot, cycleId: String): TechnicalReport {
        val start = System.currentTimeMillis()
        val indicators = IndicatorCalculator.calculate(candles)

        if (indicators == null) {
            return logAndReturn(
                TechnicalReport(
                    trend = "NEUTRAL", rsi = 50.0, atr = 0.0,
                    conclusion = "INSUFFICIENT_DATA", confidence = 0.0,
                    reasoning = "Not enough candles (need >= 30, got ${candles.size})"
                ),
                ticker, cycleId, start, "{}"
            )
        }

        val baseline = TechnicalReport(
            trend = indicators.trend,
            rsi = round2(indicators.rsi),
            atr = round2(indicators.atr),
            macd = round2(indicators.macdHistogram),
            bbUpper = indicators.bbUpper,
            bbLower = indicators.bbLower,
            conclusion = indicators.conclusion,
            confidence = 0.55,
            reasoning = "RSI=${round2(indicators.rsi)}, MACD=${round2(indicators.macdHistogram)}, " +
                "BB=[${indicators.bbLower}..${indicators.bbUpper}], trend=${indicators.trend}"
        )

        val prompt = """
            ТИКЕР: $ticker
            Текущая цена: ${snapshot.currentPrice}
            Индикаторы:
            - RSI(14): ${round2(indicators.rsi)}
            - ATR(14): ${round2(indicators.atr)}
            - MACD histogram: ${round2(indicators.macdHistogram)}
            - Bollinger: lower=${indicators.bbLower}, middle=${indicators.bbMiddle}, upper=${indicators.bbUpper}
            - Тренд (EMA 12/26): ${indicators.trend}
        """.trimIndent()

        val resp = llmClient.chat(systemPrompt, prompt, temperature = 0.1)
        if (resp.isFallback) {
            logger.info { "LLM unavailable for $ticker, using deterministic technical analysis" }
            return logAndReturn(baseline, ticker, cycleId, start, resp.content)
        }

        return try {
            val j = objectMapper.readTree(resp.content)
            val enhanced = baseline.copy(
                conclusion = j.path("conclusion").asText("NEUTRAL").uppercase().let {
                    if (it in setOf("BULLISH", "BEARISH", "NEUTRAL")) it else "NEUTRAL"
                },
                confidence = j.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0),
                reasoning = j.path("reasoning").asText(baseline.reasoning)
            )
            logAndReturn(enhanced, ticker, cycleId, start, resp.content)
        } catch (e: Exception) {
            logger.warn(e) { "Technical LLM parse error for $ticker" }
            logAndReturn(baseline, ticker, cycleId, start, resp.content)
        }
    }

    private fun logAndReturn(
        report: TechnicalReport,
        ticker: String,
        cycleId: String,
        startMs: Long,
        raw: String
    ): TechnicalReport {
        agentLogRepository.save(
            AgentLog(
                cycleId = cycleId,
                agentName = "Agent-1-Technical",
                ticker = ticker,
                action = report.conclusion,
                confidence = report.confidence,
                reasoning = report.reasoning,
                rawOutput = raw,
                latencyMs = System.currentTimeMillis() - startMs
            )
        )
        meterRegistry.counter("agent.technical.decision").increment()
        return report
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100) / 100.0
}
