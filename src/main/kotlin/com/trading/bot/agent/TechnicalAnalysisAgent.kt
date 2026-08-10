package com.trading.bot.agent

import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.infrastructure.llm.SemanticCache
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.dto.TechnicalReport
import com.trading.bot.model.entity.AgentLog
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.AgentLogRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Агент технического анализа (Agent-1).
 *
 * - Считает индикаторы (RSI, ATR, MACD, Bollinger) через IndicatorCalculator
 * - При недостатке данных (< 30 свечей) возвращает INSUFFICIENT_DATA
 * - Вызывает LLM с детерминированным baseline-отчётом как fallback
 * - Кэширует результат по семантическому отпечатку рынка (SemanticCache)
 * - Пишет лог в AgentLogRepository и метрики agent.technical.decision
 */
@Component
class TechnicalAnalysisAgent(
    private val llmClient: ResilientLlmClient,
    private val promptRegistry: PromptRegistry,
    private val semanticCache: SemanticCache,
    private val agentLogRepository: AgentLogRepository,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Технический анализ тикера по свечам и текущему снапшоту рынка.
     *
     * @param ticker тикер инструмента
     * @param candles исторические свечи (10-мин)
     * @param snapshot текущий рыночный снапшот
     * @param cycleId идентификатор торгового цикла
     * @param version версия LLM-шаблона промпта
     * @return отчёт с трендом, индикаторами и заключением
     */
    suspend fun analyze(
        ticker: String,
        candles: List<Candle>,
        snapshot: MarketSnapshot,
        cycleId: String,
        version: String = PromptRegistry.DEFAULT_VERSION,
    ): TechnicalReport {
        val start = System.currentTimeMillis()
        val indicators =
            IndicatorCalculator.calculate(candles)
                ?: return logAndReturn(
                    TechnicalReport(
                        trend = "NEUTRAL",
                        rsi = 50.0,
                        atr = 0.0,
                        conclusion = "INSUFFICIENT_DATA",
                        confidence = 0.0,
                        reasoning = "Not enough candles (need >= 30, got ${candles.size})",
                    ),
                    ticker,
                    cycleId,
                    start,
                    "{}",
                    isCached = false,
                )

        val baseline =
            TechnicalReport(
                trend = indicators.trend,
                rsi = round2(indicators.rsi),
                atr = round2(indicators.atr),
                macd = round2(indicators.macdHistogram),
                bbUpper = indicators.bbUpper,
                bbLower = indicators.bbLower,
                conclusion = indicators.conclusion,
                confidence = 0.55,
                reasoning =
                    "RSI=${round2(indicators.rsi)}, MACD=${round2(indicators.macdHistogram)}, " +
                        "BB=[${indicators.bbLower}..${indicators.bbUpper}], trend=${indicators.trend}",
            )

        val volatilityRegime = volatilityRegime(indicators.atr, snapshot.currentPrice)
        val atrPercentile = IndicatorCalculator.atrPercentile(candles)

        // Семантический отпечаток: цена (1 знак) + RSI-бакет + trend + vol regime + MACD + ATR pct + сессия
        val fingerprint =
            semanticCache.fingerprint(
                snapshot.currentPrice,
                indicators.rsi,
                indicators.trend,
                volatilityRegime,
                macdHistogram = indicators.macdHistogram,
                atrPercentile = atrPercentile,
            )

        val variables =
            mapOf(
                "ticker" to ticker,
                "currentPrice" to snapshot.currentPrice.toPlainString(),
                "rsi" to round2(indicators.rsi),
                "atr" to round2(indicators.atr),
                "macdHistogram" to round2(indicators.macdHistogram),
                "bbLower" to indicators.bbLower.toPlainString(),
                "bbMiddle" to indicators.bbMiddle.toPlainString(),
                "bbUpper" to indicators.bbUpper.toPlainString(),
                "trend" to indicators.trend,
                "volume" to (snapshot.volume ?: 0),
                "timeframe" to "MINUTE_10",
            )

        val prompt = promptRegistry.getTemplate("technical-analysis", version)
        val resp =
            llmClient.complete(
                agent = "technical",
                ticker = ticker,
                prompt = prompt,
                variables = variables,
                fingerprint = fingerprint,
                temperature = 0.1,
            )

        if (resp.isFallback) {
            logger.info { "LLM unavailable for $ticker, using deterministic technical analysis" }
            return logAndReturn(
                baseline,
                ticker,
                cycleId,
                start,
                resp.content,
                isCached = resp.fromCache,
                storageKey = resp.storageKey,
            )
        }

        return try {
            val j = objectMapper.readTree(resp.content)
            val enhanced =
                baseline.copy(
                    conclusion =
                        j.path("conclusion").asString("NEUTRAL").uppercase().let {
                            if (it in setOf("BULLISH", "BEARISH", "NEUTRAL")) it else "NEUTRAL"
                        },
                    confidence = j.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0),
                    reasoning = j.path("reasoning").asString(baseline.reasoning),
                )
            logAndReturn(
                enhanced,
                ticker,
                cycleId,
                start,
                resp.content,
                isCached = resp.fromCache,
                tokensUsed = resp.tokensUsed,
                storageKey = resp.storageKey,
            )
        } catch (e: Exception) {
            logger.warn(e) { "Technical LLM parse error for $ticker" }
            logAndReturn(
                baseline,
                ticker,
                cycleId,
                start,
                resp.content,
                isCached = resp.fromCache,
                tokensUsed = resp.tokensUsed,
                storageKey = resp.storageKey,
            )
        }
    }

    private fun volatilityRegime(
        atr: Double,
        price: BigDecimal,
    ): String {
        if (price <= BigDecimal.ZERO) return "UNKNOWN"
        val atrPercent =
            BigDecimal(atr)
                .divide(price, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal("100"))
                .toDouble()
        return when {
            atrPercent < 1.0 -> "LOW_VOLATILITY"
            atrPercent < 2.5 -> "MEDIUM_VOLATILITY"
            else -> "HIGH_VOLATILITY"
        }
    }

    private suspend fun logAndReturn(
        report: TechnicalReport,
        ticker: String,
        cycleId: String,
        startMs: Long,
        raw: String,
        isCached: Boolean,
        tokensUsed: Int = 0,
        storageKey: String? = null,
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
                latencyMs = System.currentTimeMillis() - startMs,
                tokensUsed = tokensUsed,
                isCached = isCached,
                storageKey = storageKey,
            ),
        )
        meterRegistry.counter("agent.technical.decision", Tags.of("action", report.conclusion)).increment()
        return report
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100) / 100.0
}
