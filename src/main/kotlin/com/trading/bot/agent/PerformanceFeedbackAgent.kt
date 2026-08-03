package com.trading.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.client.LlmClient
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
    private val llmClient: LlmClient,
    private val tradeAnalysisService: TradeAnalysisService,
    private val agentLogRepo: AgentLogRepository,
    private val adjustmentRepo: StrategyAdjustmentRepository,
    private val redisCache: RedisCacheService,
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

    suspend fun generateFeedback(ticker: String): StrategyFeedback = coroutineScope {
        val stats = tradeAnalysisService.analyzeLastNDays(14)[ticker]
        if (stats == null || stats.totalTrades < 3) {
            return@coroutineScope defaultFeedback(ticker)
        }

        val statsHash = hashStats(stats)

        val cached = redisCache.getFeedback(ticker, statsHash)
        if (cached != null) {
            logger.info { "Using cached feedback for $ticker" }
            meterRegistry.counter("feedback.cache.hit", Tags.of("ticker", ticker)).increment()
            return@coroutineScope parseFeedback(cached, ticker, stats)
        }

        meterRegistry.counter("feedback.cache.miss", Tags.of("ticker", ticker)).increment()

        val prompt = buildPrompt(ticker, stats)

        val feedback = try {
            val resp = llmClient.chat(
                "You are a Meta-Learning trading bot agent. Analyze statistics and give specific numeric adjustments. Be conservative. Reply ONLY JSON.",
                prompt
            )
            if (resp.content.startsWith("ERROR")) {
                logger.warn { "LLM error for $ticker feedback, using defaults" }
                defaultFeedback(ticker, stats)
            } else {
                parseFeedback(resp.content, ticker, stats)
            }
        } catch (e: Exception) {
            logger.error(e) { "Feedback LLM call failed for $ticker, using defaults" }
            meterRegistry.counter("feedback.llm.error", Tags.of("ticker", ticker)).increment()
            defaultFeedback(ticker, stats)
        }

        redisCache.saveFeedback(ticker, feedback.rawJson, statsHash)
        saveAdjustments(feedback, stats)
        feedback
    }

    private fun hashStats(stats: TradeStats): String {
        val raw = "${stats.ticker}:${stats.totalTrades}:${stats.winRate}:${stats.slHitRate}:${stats.tpHitRate}"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    private fun defaultFeedback(ticker: String, stats: TradeStats? = null): StrategyFeedback {
        val pause = (stats?.maxConsecutiveLosses ?: 0) >= 3
        return StrategyFeedback(
            ticker = ticker,
            confidenceAdjustment = 0.0,
            slAdjustmentPercent = 0.0,
            tpAdjustmentPercent = 0.0,
            contextPrompt = "",
            agentSpecificNotes = emptyMap(),
            shouldPauseTrading = pause,
            rawJson = "{}"
        )
    }

    private fun buildPrompt(ticker: String, stats: TradeStats): String {
        return """
            TICKER: $ticker
            14-DAY STATISTICS:
            - Total trades: ${stats.totalTrades}
            - Win Rate: ${String.format("%.1f", stats.winRate * 100)}%
            - Profit Factor: ${String.format("%.2f", stats.profitFactor)}
            - Avg win: ${stats.avgWin}
            - Avg loss: ${stats.avgLoss}
            - SL hit: ${String.format("%.1f", stats.slHitRate * 100)}%
            - TP hit: ${String.format("%.1f", stats.tpHitRate * 100)}%
            - Max loss streak: ${stats.maxConsecutiveLosses}
            - Best entry hour: ${stats.bestEntryHour ?: "N/A"}
            - Worst entry hour: ${stats.worstEntryHour ?: "N/A"}
            - Blind spots: ${stats.blindSpots.joinToString("; ") { it.conditionPattern }}

            TASK: Output JSON:
            {
              "confidenceAdjustment": double,
              "slAdjustmentPercent": double,
              "tpAdjustmentPercent": double,
              "contextPrompt": "string",
              "techAgentNote": "string",
              "fundAgentNote": "string",
              "strategistNote": "string",
              "contrarianNote": "string",
              "shouldPauseTrading": boolean
            }
        """.trimIndent()
    }

    private fun parseFeedback(content: String, ticker: String, stats: TradeStats): StrategyFeedback {
        return try {
            val clean = content.replace("\`\`\`json", "").replace("\`\`\`", "").trim()
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
            defaultFeedback(ticker, stats)
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
