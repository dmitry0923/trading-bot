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
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal

/**
 * Стратегический агент (Agent-3).
 *
 * - Синтезирует сигналы технического и фундаментального агентов в торговый draft
 * - Guardrail: при недостаточных/неуверенных данных возвращает HOLD без вызова LLM
 * - При недоступности LLM возвращает HOLD с причиной
 * - Пост-обработка через Guardrails (низкая уверенность -> HOLD, коррекция цены)
 * - Пишет лог в AgentLogRepository и метрики agent.strategy.decision
 *
 * Draft несёт ТОЛЬКО направление сделки (BUY/SELL/HOLD), целевую цену и
 * уверенность. Размер позиции и SL/TP вычисляет риск-этап (Sizer/OrderBuilder).
 */
@Component
class StrategyAgent(
    private val llmClient: ResilientLlmClient,
    private val promptRegistry: PromptRegistry,
    private val semanticCache: SemanticCache,
    private val guardrails: Guardrails,
    private val agentLogRepository: AgentLogRepository,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}

    data class Draft(
        val action: StrategyAction,
        val targetPrice: BigDecimal,
        val signalStrength: Double,
        val reasoning: String,
    )

    /**
     * Формирует черновое торговое решение на основе технического и фундаментального отчётов.
     *
     * @param ticker тикер инструмента
     * @param tech отчёт технического анализа
     * @param fund отчёт фундаментального анализа
     * @param snapshot текущий рыночный снапшот
     * @param cycleId идентификатор торгового цикла
     * @param adaptiveThreshold адаптивный порог уверенности (из AdaptiveRiskService)
     * @param version версия LLM-шаблона промпта
     * @param temperature температура генерации (live-путь 0.15, бэктест — 0.0)
     * @param cacheNamespace изолирует semantic cache (бэктест: "backtest")
     * @param techDelta дельта-компрессия тех-отчёта (roadmap 13.8); null — полный текст
     * @param fundDelta дельта-компрессия фундаментального отчёта (roadmap 13.8); null — полный текст
     * @return черновик стратегии (Draft)
     */
    suspend fun formulate(
        ticker: String,
        tech: TechnicalReport,
        fund: FundamentalReport,
        snapshot: MarketSnapshot,
        cycleId: String,
        adaptiveThreshold: Double = 0.5,
        version: String = PromptRegistry.DEFAULT_VERSION,
        temperature: Double = 0.15,
        cacheNamespace: String? = null,
        techDelta: String? = null,
        fundDelta: String? = null,
    ): Draft {
        val start = System.currentTimeMillis()

        // GUARDRAIL: недостаточно данных → HOLD без LLM-вызова
        if (tech.conclusion == "INSUFFICIENT_DATA" || tech.signalStrength < 0.5) {
            return logAndReturn(
                hold(snapshot.currentPrice, "Insufficient technical data (conf=${tech.signalStrength})"),
                ticker,
                cycleId,
                start,
                "{}",
                overrideReason = "GUARDRAIL: INSUFFICIENT_TECH_DATA",
            )
        }

        val fingerprint =
            semanticCache.fingerprint(
                snapshot.currentPrice,
                tech.rsi,
                tech.trend,
                "technical",
                macdHistogram = tech.macd,
            )

        val variables =
            mapOf(
                "ticker" to ticker,
                "currentPrice" to snapshot.currentPrice.toPlainString(),
                "techConclusion" to tech.conclusion,
                "techSignalStrength" to tech.signalStrength,
                "techTrend" to tech.trend,
                "techRsi" to tech.rsi,
                "techReasoning" to (techDelta ?: tech.reasoning),
                "fundConclusion" to fund.conclusion,
                "fundSignalStrength" to fund.signalStrength,
                "fundReasoning" to (fundDelta ?: fund.reasoning),
            )

        val prompt = promptRegistry.getTemplate("strategy", version)
        val resp =
            llmClient.complete(
                agent = "strategy",
                ticker = ticker,
                prompt = prompt,
                variables = variables,
                fingerprint = fingerprint,
                temperature = temperature,
                cacheNamespace = cacheNamespace,
            )
        if (resp.isFallback) {
            logger.info { "LLM unavailable for $ticker, HOLD" }
            return logAndReturn(
                hold(snapshot.currentPrice, "LLM unavailable"),
                ticker,
                cycleId,
                start,
                resp.content,
                isCached = resp.fromCache,
                storageKey = resp.storageKey,
            )
        }

        val draft =
            try {
                val cleaned =
                    resp.content
                        .replace("```json", "")
                        .replace("```", "")
                        .trim()
                val j = objectMapper.readTree(cleaned)
                val action =
                    StrategyAction.entries.firstOrNull {
                        it.name == j.path("action").asString("HOLD").uppercase()
                    } ?: StrategyAction.HOLD

                val rawPrice = j.path("targetPrice").asString().toBigDecimalOrNull() ?: snapshot.currentPrice
                Draft(
                    action = action,
                    targetPrice = rawPrice,
                    signalStrength = j.path("signalStrength").asDouble(0.0).coerceIn(0.0, 1.0),
                    reasoning = j.path("reasoning").asString(""),
                )
            } catch (e: Exception) {
                logger.warn(e) { "Strategy LLM parse error for $ticker" }
                meterRegistry.counter("strategy.agent.parse.error", Tags.of("ticker", ticker)).increment()
                return logAndReturn(
                    hold(snapshot.currentPrice, "Parse error: ${e.message}"),
                    ticker,
                    cycleId,
                    start,
                    resp.content,
                    isCached = resp.fromCache,
                    tokensUsed = resp.tokensUsed,
                    storageKey = resp.storageKey,
                )
            }

        // POST-PROCESSING GUARDRAILS: низкая уверенность → HOLD, отклонение цены → коррекция
        val guarded =
            guardrails.apply(
                signal =
                    Guardrails.Signal(
                        action = draft.action,
                        targetPrice = draft.targetPrice,
                        signalStrength = draft.signalStrength,
                    ),
                marketPrice = snapshot.currentPrice,
                adaptiveThreshold = adaptiveThreshold,
            )

        val finalDraft =
            if (guarded.overridden) {
                logger.info { "Guardrail for $ticker: ${guarded.overrideReason}" }
                draft.copy(
                    action = guarded.signal.action,
                    targetPrice = guarded.signal.targetPrice,
                    signalStrength = guarded.signal.signalStrength,
                    reasoning = draft.reasoning + " [GUARDRAIL: ${guarded.overrideReason}]",
                )
            } else {
                draft
            }

        return logAndReturn(
            finalDraft,
            ticker,
            cycleId,
            start,
            resp.content,
            isCached = resp.fromCache,
            tokensUsed = resp.tokensUsed,
            overrideReason = guarded.overrideReason,
            storageKey = resp.storageKey,
        )
    }

    private fun hold(
        marketPrice: BigDecimal,
        reason: String,
    ): Draft = Draft(StrategyAction.HOLD, marketPrice, 0.0, reason)

    private suspend fun logAndReturn(
        draft: Draft,
        ticker: String,
        cycleId: String,
        startMs: Long,
        raw: String,
        isCached: Boolean = false,
        tokensUsed: Int = 0,
        overrideReason: String? = null,
        storageKey: String? = null,
    ): Draft {
        agentLogRepository.save(
            AgentLog(
                cycleId = cycleId,
                agentName = "Agent-3-Strategist",
                ticker = ticker,
                action = draft.action.name,
                signalStrength = draft.signalStrength,
                reasoning = draft.reasoning,
                rawOutput = raw,
                latencyMs = System.currentTimeMillis() - startMs,
                tokensUsed = tokensUsed,
                isCached = isCached,
                overrideReason = overrideReason,
                storageKey = storageKey,
            ),
        )
        meterRegistry.counter("agent.strategy.decision", Tags.of("action", draft.action.name, "ticker", ticker)).increment()
        return draft
    }
}
