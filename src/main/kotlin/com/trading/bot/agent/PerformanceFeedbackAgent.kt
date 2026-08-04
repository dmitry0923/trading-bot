package com.trading.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.model.*
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.StrategyAdjustmentRepository
import com.trading.bot.service.RedisCacheService
import com.trading.bot.service.TradeAnalysisService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.security.MessageDigest

@Component
class PerformanceFeedbackAgent(
    private val llmClient: ResilientLlmClient,
    private val tradeAnalysisService: TradeAnalysisService,
    private val agentLogRepo: AgentLogRepository,
    private val adjustmentRepo: StrategyAdjustmentRepository,
    private val redisCache: RedisCacheService,
    private val promptRegistry: PromptRegistry,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}

    data class StrategyFeedback(
        val ticker: String,
        val confidenceAdjustment: Double,
        val slAdjustmentPercent: Double,
        val tpAdjustmentPercent: Double,
        val contextPrompt: String,
        val agentSpecificNotes: Map<String, String>,
        val shouldPauseTrading: Boolean,
        val rawJson: String
    )

    suspend fun generateFeedback(
        ticker: String,
        version: String = PromptRegistry.DEFAULT_VERSION
    ): StrategyFeedback = coroutineScope {
        val stats = tradeAnalysisService.analyzeLastNDays(14)[ticker]

        // GUARDRAIL: недостаточно сделок (< 5) → rule-based feedback без LLM
        if (stats == null || stats.totalTrades < 5) {
            meterRegistry.counter("feedback.rule_based", Tags.of("ticker", ticker, "reason", "LOW_TRADES")).increment()
            return@coroutineScope ruleBasedFeedback(ticker, stats)
        }

        val statsHash = hashStats(stats)

        val cached = redisCache.getFeedback(ticker, statsHash)
        if (cached != null) {
            logger.info { "Using cached feedback for $ticker" }
            meterRegistry.counter("feedback.cache.hit", Tags.of("ticker", ticker)).increment()
            return@coroutineScope parseFeedback(cached, ticker, stats)
        }

        meterRegistry.counter("feedback.cache.miss", Tags.of("ticker", ticker)).increment()

        val variables = mapOf(
            "ticker" to ticker,
            "totalTrades" to stats.totalTrades,
            "winRate" to String.format("%.1f", stats.winRate * 100),
            "profitFactor" to String.format("%.2f", stats.profitFactor),
            "avgWin" to stats.avgWin,
            "avgLoss" to stats.avgLoss,
            "slHitRate" to String.format("%.1f", stats.slHitRate * 100),
            "tpHitRate" to String.format("%.1f", stats.tpHitRate * 100),
            "maxConsecutiveLosses" to stats.maxConsecutiveLosses,
            "bestEntryHour" to (stats.bestEntryHour ?: "N/A"),
            "worstEntryHour" to (stats.worstEntryHour ?: "N/A"),
            "blindSpots" to stats.blindSpots.joinToString("; ") { it.conditionPattern }
        )

        val prompt = promptRegistry.getTemplate("performance-feedback", version)
        val feedback = try {
            val resp = llmClient.complete(
                agent = "feedback",
                ticker = ticker,
                prompt = prompt,
                variables = variables,
                temperature = 0.1
            )
            if (resp.isFallback) {
                logger.warn { "LLM error for $ticker feedback, using rule-based" }
                ruleBasedFeedback(ticker, stats)
            } else {
                parseFeedback(resp.content, ticker, stats)
            }
        } catch (e: Exception) {
            logger.error(e) { "Feedback LLM call failed for $ticker, using rule-based" }
            meterRegistry.counter("feedback.llm.error", Tags.of("ticker", ticker)).increment()
            ruleBasedFeedback(ticker, stats)
        }

        redisCache.saveFeedback(ticker, feedback.rawJson, statsHash)
        saveAdjustments(feedback, stats)
        agentLogRepo.save(
            AgentLog(
                cycleId = "META",
                agentName = "Agent-6-Performance",
                ticker = ticker,
                action = if (feedback.shouldPauseTrading) "PAUSE" else "ADJUST",
                confidence = stats.winRate,
                reasoning = "confAdj=${feedback.confidenceAdjustment}, slAdj=${feedback.slAdjustmentPercent}, tpAdj=${feedback.tpAdjustmentPercent}",
                rawOutput = feedback.rawJson
            )
        )
        feedback
    }

    /**
     * Rule-based feedback (без LLM) по детерминированным правилам:
     * - maxConsecutiveLosses >= 3 → shouldPauseTrading = true
     * - winRate < 35% → confidenceAdjustment = +0.15 (повышаем порог входа)
     * - slHitRate > 60% → slAdjustmentPercent = +0.20 (расширяем стоп)
     */
    fun ruleBasedFeedback(ticker: String, stats: TradeStats?): StrategyFeedback {
        val shouldPause = (stats?.maxConsecutiveLosses ?: 0) >= 3
        val confidenceAdjustment = if ((stats?.winRate ?: 1.0) < 0.35) 0.15 else 0.0
        val slAdjustment = if ((stats?.slHitRate ?: 0.0) > 0.60) 0.20 else 0.0
        val tpAdjustment = 0.0

        val notes = mapOf(
            "reason" to "RULE_BASED" +
                (if (shouldPause) "; consecutive losses >= 3 -> pause" else "") +
                (if (confidenceAdjustment > 0) "; winRate < 35% -> confAdj +0.15" else "") +
                (if (slAdjustment > 0) "; slHitRate > 60% -> slAdj +0.20" else "")
        )
        val raw = "{\"confidenceAdjustment\":$confidenceAdjustment,\"slAdjustmentPercent\":$slAdjustment," +
            "\"tpAdjustmentPercent\":0.0,\"contextPrompt\":\"\",\"shouldPauseTrading\":$shouldPause}"
        return StrategyFeedback(
            ticker = ticker,
            confidenceAdjustment = confidenceAdjustment,
            slAdjustmentPercent = slAdjustment,
            tpAdjustmentPercent = tpAdjustment,
            contextPrompt = "",
            agentSpecificNotes = notes,
            shouldPauseTrading = shouldPause,
            rawJson = raw
        )
    }

    private fun hashStats(stats: TradeStats): String {
        val raw = "${stats.ticker}:${stats.totalTrades}:${stats.winRate}:${stats.slHitRate}:${stats.tpHitRate}"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    private fun parseFeedback(content: String, ticker: String, stats: TradeStats): StrategyFeedback {
        return try {
            val clean = content.replace("```json", "").replace("```", "").trim()
            val j = objectMapper.readTree(clean)
            StrategyFeedback(
                ticker = ticker,
                confidenceAdjustment = j["confidenceAdjustment"]?.asDouble() ?: 0.0,
                slAdjustmentPercent = j["slAdjustmentPercent"]?.asDouble() ?: 0.0,
                tpAdjustmentPercent = j["tpAdjustmentPercent"]?.asDouble() ?: 0.0,
                contextPrompt = j["contextPrompt"]?.asText() ?: "",
                agentSpecificNotes = mapOf(
                    "techAgentNote" to (j["techAgentNote"]?.asText() ?: ""),
                    "fundAgentNote" to (j["fundAgentNote"]?.asText() ?: ""),
                    "strategistNote" to (j["strategistNote"]?.asText() ?: ""),
                    "contrarianNote" to (j["contrarianNote"]?.asText() ?: "")
                ),
                shouldPauseTrading = j["shouldPauseTrading"]?.asBoolean() ?: false,
                rawJson = clean
            )
        } catch (e: Exception) {
            logger.error(e) { "Feedback parse error for $ticker" }
            ruleBasedFeedback(ticker, stats)
        }
    }

    private fun saveAdjustments(feedback: StrategyFeedback, stats: TradeStats) {
        if (feedback.confidenceAdjustment != 0.0) {
            adjustmentRepo.save(
                StrategyAdjustment(
                    ticker = feedback.ticker,
                    adjustmentType = "CONFIDENCE",
                    oldValue = BigDecimal.ZERO,
                    newValue = BigDecimal(feedback.confidenceAdjustment),
                    triggeredBy = "META_AGENT",
                    reason = "Win Rate ${String.format("%.0f", stats.winRate * 100)}%, PF ${String.format("%.2f", stats.profitFactor)}"
                )
            )
        }
        if (feedback.slAdjustmentPercent != 0.0) {
            adjustmentRepo.save(
                StrategyAdjustment(
                    ticker = feedback.ticker,
                    adjustmentType = "SL_PERCENT",
                    oldValue = BigDecimal.ZERO,
                    newValue = BigDecimal(feedback.slAdjustmentPercent),
                    triggeredBy = "META_AGENT",
                    reason = "SL hit rate ${String.format("%.0f", stats.slHitRate * 100)}%"
                )
            )
        }
        if (feedback.tpAdjustmentPercent != 0.0) {
            adjustmentRepo.save(
                StrategyAdjustment(
                    ticker = feedback.ticker,
                    adjustmentType = "TP_PERCENT",
                    oldValue = BigDecimal.ZERO,
                    newValue = BigDecimal(feedback.tpAdjustmentPercent),
                    triggeredBy = "META_AGENT",
                    reason = "TP hit rate ${String.format("%.0f", stats.tpHitRate * 100)}%"
                )
            )
        }
    }
}
