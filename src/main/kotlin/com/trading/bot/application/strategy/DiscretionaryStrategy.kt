package com.trading.bot.application.strategy

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.FundamentalAnalysisAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.agent.TechnicalAnalysisAgent
import com.trading.bot.config.LlmConfig
import com.trading.bot.domain.strategy.AdvisoryOnlyStrategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.infrastructure.llm.DeltaPromptStore
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.service.AdaptiveRiskService
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Дискреционная (LLM) стратегия — агентный контур для A/B-эксперимента и аналитики.
 *
 * Помечена [AdvisoryOnlyStrategy]: НЕ участвует в конкуренции за сигнал
 * (C-001). Единственный источник сигнала — детерминированные стратегии; LLM
 * работает советником через [com.trading.bot.application.advisor.LlmAdvisor].
 * Цепочка Technical + Fundamental -> Strategist -> Contrarian -> Arbitrator
 * сохраняется как вариантная рука A/B-эксперимента ([produceVariant]).
 *
 * Снижение LLM-латентности (roadmap 13.8):
 *  - Technical + Fundamental + адаптивный порог запускаются ПАРАЛЛЕЛЬНО
 *    (независимые вызовы, нет передачи данных между ними);
 *  - при llm.delta-prompts-enabled=true стратег и контрариан получают вместо
 *    полного reasoning только дельту отчётов с прошлого цикла ([DeltaPromptStore]),
 *    сокращая входные токены; фолбэк на полный текст при отсутствии предыдущего
 *    отчёта или при выключенной фиче.
 *
 * [evaluate] вызывается только вручную/для аналитики: тот же контур с версией
 * промпта по умолчанию. [produceVariant] запускает контур с вариантной версией
 * промпта Арбитра и обходом семантического кэша (иначе вариант получил бы
 * кэшированный ответ контрольной руки — эксперимент бессмыслен).
 */
@Component
class DiscretionaryStrategy(
    private val techAgent: TechnicalAnalysisAgent,
    private val fundAgent: FundamentalAnalysisAgent,
    private val stratAgent: StrategyAgent,
    private val contrAgent: ContrarianAgent,
    private val arbAgent: ArbitratorAgent,
    private val adaptiveRisk: AdaptiveRiskService,
    private val deltaStore: DeltaPromptStore,
    private val llmConfig: LlmConfig,
    private val meterRegistry: MeterRegistry,
) : AdvisoryOnlyStrategy {
    override val id = "DISCRETIONARY"

    override suspend fun evaluate(context: StrategyContext): StrategyDecision =
        runChain(context, PromptRegistry.DEFAULT_VERSION, bypassCache = false)

    /**
     * Вариантная рука A/B-эксперимента: полный агентный контур с вариантной
     * версией промпта Арбитра. Не полагается на кэш цепочки: контур запускается
     * целиком (shadow-режим, по конфигурации эксперимента).
     */
    suspend fun produceVariant(
        context: StrategyContext,
        version: String,
    ): StrategyDecision = runChain(context, version, bypassCache = true)

    private suspend fun runChain(
        context: StrategyContext,
        version: String,
        bypassCache: Boolean,
    ): StrategyDecision =
        LlmChainExecutor.run(
            techAgent = techAgent,
            fundAgent = fundAgent,
            stratAgent = stratAgent,
            contrAgent = contrAgent,
            arbAgent = arbAgent,
            adaptiveRisk = adaptiveRisk,
            deltaStore = deltaStore,
            llmConfig = llmConfig,
            meterRegistry = meterRegistry,
            context = context,
            version = version,
            bypassCache = bypassCache,
        )
}
