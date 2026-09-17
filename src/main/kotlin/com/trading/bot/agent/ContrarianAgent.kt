package com.trading.bot.agent

import com.trading.bot.infrastructure.llm.DefaultJsonSchemaValidator
import com.trading.bot.infrastructure.llm.JsonSchemaValidator
import com.trading.bot.infrastructure.llm.LlmResponseSchemas
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

/**
 * Контрариан-агент (Agent-4) — «адвокат дьявола».
 *
 * - Оспаривает черновик стратега: валидность, уровень риска и критика
 * - Guardrail: при HOLD-черновике не вызывает LLM, риск LOW
 * - Fail-closed (review/P1): при недоступности LLM / ошибке парсинга / несоответствии
 *   схеме возвращает isValid=false, riskLevel=CRITICAL, signalStrength=0.0 и
 *   [ChallengeReport.llmAvailable]=false — цепочка (LlmChainExecutor) переводит в HOLD.
 *   Раньше недоступность LLM «разрешала» сделку (fail-open: isValid=true, LOW) — сделка
 *   могла пройти без объективной оценки рисков.
 * - Кэширует результат по семантическому отпечатку рынка (SemanticCache)
 * - Пишет лог в AgentLogRepository и метрики agent.contrarian.decision
 */
@Component
class ContrarianAgent(
    private val llmClient: ResilientLlmClient,
    private val promptRegistry: PromptRegistry,
    private val semanticCache: SemanticCache,
    private val agentLogRepository: AgentLogRepository,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator = DefaultJsonSchemaValidator(objectMapper),
) {
    private val logger = KotlinLogging.logger {}

    data class ChallengeReport(
        val isValid: Boolean,
        val riskLevel: String,
        val critique: String,
        val signalStrength: Double,
        /** false — LLM не отвечал (fallback/схема/парсинг): цепочка обязана дать HOLD. */
        val llmAvailable: Boolean = true,
    )

    /**
     * Оспаривает черновик стратега и возвращает оценку риска сделки.
     *
     * @param draft черновик стратега
     * @param tech отчёт технического анализа
     * @param fund отчёт фундаментального анализа
     * @param snapshot текущий рыночный снапшот
     * @param cycleId идентификатор торгового цикла
     * @param version версия LLM-шаблона промпта
     * @param temperature температура генерации (live-путь 0.1, бэктест — 0.0)
     * @param cacheNamespace изолирует semantic cache (бэктест: "backtest")
     * @param techDelta дельта-компрессия тех-отчёта (roadmap 13.8); null — полный текст
     * @return отчёт о валидности, уровне риска и критике
     */
    suspend fun challenge(
        draft: StrategyAgent.Draft,
        tech: TechnicalReport,
        fund: FundamentalReport,
        snapshot: MarketSnapshot,
        cycleId: String,
        version: String = PromptRegistry.DEFAULT_VERSION,
        temperature: Double = 0.1,
        cacheNamespace: String? = null,
        techDelta: String? = null,
    ): ChallengeReport {
        val start = System.currentTimeMillis()

        // GUARDRAIL: если стратег сказал HOLD — LLM не вызываем, риск низкий
        if (draft.action == StrategyAction.HOLD) {
            return logAndReturn(
                ChallengeReport(isValid = true, riskLevel = "LOW", critique = "No position proposed", signalStrength = 1.0),
                snapshot.ticker,
                cycleId,
                start,
                "{}",
            )
        }

        val variables =
            mapOf(
                "action" to draft.action.name,
                "targetPrice" to draft.targetPrice.toPlainString(),
                "strategyReasoning" to draft.reasoning,
                "techConclusion" to tech.conclusion,
                "techSignalStrength" to tech.signalStrength,
                "techReasoning" to (techDelta ?: tech.reasoning),
                "fundConclusion" to fund.conclusion,
                "fundSignalStrength" to fund.signalStrength,
                "currentPrice" to snapshot.currentPrice.toPlainString(),
                "trend" to tech.trend,
                "rsi" to tech.rsi,
                "atr" to tech.atr,
            )

        // Одинаковый сигнал при том же рынке -> одинаковый challenge (кэш)
        val fingerprint =
            semanticCache.fingerprint(
                snapshot.currentPrice,
                tech.rsi,
                tech.trend,
                "contrarian",
                macdHistogram = tech.macd,
            )

        val prompt = promptRegistry.getTemplate("contrarian", version)
        val resp =
            llmClient.complete(
                agent = "contrarian",
                ticker = snapshot.ticker,
                prompt = prompt,
                variables = variables,
                fingerprint = fingerprint,
                temperature = temperature,
                cacheNamespace = cacheNamespace,
            )

        val cleaned =
            resp.content
                .replace("```json", "")
                .replace("```", "")
                .trim()

        val report =
            if (resp.isFallback) {
                logger.warn { "Contrarian LLM unavailable for ${snapshot.ticker} -> fail-closed CRITICAL" }
                meterRegistry
                    .counter(
                        "llm.fallback.activated",
                        Tags.of("agent", "contrarian", "reason", "FAIL_CLOSED_UNAVAILABLE"),
                    ).increment()
                ChallengeReport(
                    isValid = false,
                    riskLevel = "CRITICAL",
                    critique = "LLM unavailable",
                    signalStrength = 0.0,
                    llmAvailable = false,
                )
            } else if (!jsonSchemaValidator.isValid(cleaned, LlmResponseSchemas.CHALLENGE_REPORT)) {
                logger.warn { "Contrarian LLM response failed schema validation for ${snapshot.ticker}" }
                meterRegistry
                    .counter(
                        "llm.schema.rejected",
                        Tags.of("agent", "contrarian", "ticker", snapshot.ticker),
                    ).increment()
                ChallengeReport(
                    isValid = false,
                    riskLevel = "CRITICAL",
                    critique = "Schema rejected",
                    signalStrength = 0.0,
                    llmAvailable = false,
                )
            } else {
                try {
                    val j = objectMapper.readTree(cleaned)
                    ChallengeReport(
                        isValid = j.path("isValid").asBoolean(false),
                        riskLevel =
                            j.path("riskLevel").asString("LOW").uppercase().let {
                                if (it in setOf("LOW", "MEDIUM", "HIGH", "CRITICAL")) it else "LOW"
                            },
                        critique = j.path("critique").asString(""),
                        signalStrength = j.path("signalStrength").asDouble(0.0).coerceIn(0.0, 1.0),
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Contrarian LLM parse error for ${snapshot.ticker} -> fail-closed CRITICAL" }
                    ChallengeReport(
                        isValid = false,
                        riskLevel = "CRITICAL",
                        critique = "Parse error",
                        signalStrength = 0.0,
                        llmAvailable = false,
                    )
                }
            }

        return logAndReturn(report, snapshot.ticker, cycleId, start, resp.content, resp.tokensUsed, resp.fromCache, resp.storageKey)
    }

    private suspend fun logAndReturn(
        report: ChallengeReport,
        ticker: String,
        cycleId: String,
        startMs: Long,
        raw: String,
        tokensUsed: Int = 0,
        isCached: Boolean = false,
        storageKey: String? = null,
    ): ChallengeReport {
        agentLogRepository.save(
            AgentLog(
                cycleId = cycleId,
                agentName = "Agent-4-Contrarian",
                ticker = ticker,
                action = "CHALLENGE:${report.riskLevel}",
                signalStrength = report.signalStrength,
                reasoning = report.critique,
                rawOutput = raw,
                latencyMs = System.currentTimeMillis() - startMs,
                tokensUsed = tokensUsed,
                isCached = isCached,
                storageKey = storageKey,
            ),
        )
        meterRegistry.counter("agent.contrarian.decision", Tags.of("riskLevel", report.riskLevel, "ticker", ticker)).increment()
        return report
    }
}
