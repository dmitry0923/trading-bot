package com.trading.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.infrastructure.llm.SemanticCache
import com.trading.bot.model.AgentLog
import com.trading.bot.model.FundamentalReport
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.service.MacroContextService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Агент фундаментального анализа (Agent-2).
 *
 * - Зависит только от макро-контекста (ставка ЦБ, нефть Brent, курс USD/RUB)
 * - Кэширует результат по макро-отпечатку (SemanticCache)
 * - При недоступности LLM возвращает NEUTRAL c нулевой уверенностью
 * - Пишет лог в AgentLogRepository и метрики agent.fundamental.decision
 */
@Component
class FundamentalAnalysisAgent(
    private val llmClient: ResilientLlmClient,
    private val promptRegistry: PromptRegistry,
    private val macroContextService: MacroContextService,
    private val semanticCache: SemanticCache,
    private val agentLogRepository: AgentLogRepository,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Фундаментальный анализ тикера на основе макро-контекста.
     *
     * @param ticker тикер инструмента
     * @param cycleId идентификатор торгового цикла
     * @param version версия LLM-шаблона промпта
     * @return отчёт с заключением, уверенностью и обоснованием
     */
    suspend fun analyze(
        ticker: String,
        cycleId: String,
        version: String = PromptRegistry.DEFAULT_VERSION
    ): FundamentalReport {
        val start = System.currentTimeMillis()
        val macro = macroContextService.fetch()

        val variables = mapOf(
            "ticker" to ticker,
            "cbrRate" to macro.cbrRate.toPlainString(),
            "brentPrice" to macro.brentPrice.toPlainString(),
            "usdRub" to macro.usdRub.toPlainString()
        )

        // Фундаментальный анализ зависит только от макро-фона — кэшируем по нему
        val fingerprint = semanticCache.genericFingerprint(
            macro.cbrRate.toPlainString(),
            macro.brentPrice.toPlainString(),
            macro.usdRub.toPlainString()
        )

        val prompt = promptRegistry.getTemplate("fundamental-analysis", version)
        val resp = llmClient.complete(
            agent = "fundamental",
            ticker = ticker,
            prompt = prompt,
            variables = variables,
            fingerprint = fingerprint,
            temperature = 0.1
        )

        val report = if (resp.isFallback) {
            logger.info { "LLM unavailable for fundamental analysis of $ticker" }
            FundamentalReport(conclusion = "NEUTRAL", confidence = 0.0, reasoning = "LLM unavailable")
        } else {
            try {
                val j = objectMapper.readTree(resp.content)
                FundamentalReport(
                    conclusion = j.path("conclusion").asText("NEUTRAL").uppercase().let {
                        if (it in setOf("BULLISH", "BEARISH", "NEUTRAL")) it else "NEUTRAL"
                    },
                    confidence = j.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0),
                    reasoning = j.path("reasoning").asText("")
                )
            } catch (e: Exception) {
                logger.warn(e) { "Fundamental LLM parse error for $ticker" }
                FundamentalReport(conclusion = "NEUTRAL", confidence = 0.0, reasoning = "Parse error")
            }
        }

        agentLogRepository.save(
            AgentLog(
                cycleId = cycleId,
                agentName = "Agent-2-Fundamental",
                ticker = ticker,
                action = report.conclusion,
                confidence = report.confidence,
                reasoning = report.reasoning,
                rawOutput = resp.content,
                latencyMs = System.currentTimeMillis() - start,
                tokensUsed = resp.tokensUsed,
                isCached = resp.fromCache
            )
        )
        meterRegistry.counter("agent.fundamental.decision", Tags.of("action", report.conclusion)).increment()
        return report
    }
}
