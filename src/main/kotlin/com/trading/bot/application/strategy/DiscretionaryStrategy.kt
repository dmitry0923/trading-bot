package com.trading.bot.application.strategy

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.FundamentalAnalysisAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.agent.TechnicalAnalysisAgent
import com.trading.bot.domain.strategy.AdvisoryOnlyStrategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.service.AdaptiveRiskService
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
    ): StrategyDecision {
        val tech = techAgent.analyze(context.ticker, context.candles, context.snapshot, context.cycleId)
        val fund = fundAgent.analyze(context.ticker, context.cycleId)
        val adaptiveConf = adaptiveRisk.getAdaptiveConfidenceThreshold(context.ticker)
        val draft = stratAgent.formulate(context.ticker, tech, fund, context.snapshot, context.cycleId, adaptiveThreshold = adaptiveConf)
        val challenge = contrAgent.challenge(draft, tech, fund, context.snapshot, context.cycleId)
        val final =
            arbAgent.adjudicate(
                draft,
                challenge,
                tech,
                fund,
                context.snapshot,
                context.cycleId,
                contextPrompt = context.contextPrompt,
                adaptiveConfidence = adaptiveConf,
                version = version,
                bypassCache = bypassCache,
            )
        return StrategyDecision(final.action, final.targetPrice, final.confidence, final.reasoning)
    }
}
