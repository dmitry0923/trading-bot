package com.trading.bot.application.strategy

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.FundamentalAnalysisAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.agent.TechnicalAnalysisAgent
import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.dto.TechnicalReport
import com.trading.bot.service.AdaptiveRiskService
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Дискреционная (LLM) стратегия — текущий агентный контур как реализация [Strategy].
 *
 * Оборачивает цепочку Technical + Fundamental -> Strategist -> Contrarian ->
 * Arbitrator в единый контракт стратегического этапа. Guardrails (адаптивный
 * порог уверенности, коррекция цены) применяются агентами внутри, как раньше.
 *
 * Промежуточные результаты цепочки (tech/fund/draft/challenge) сохраняются на
 * время цикла для вариантной руки A/B-эксперимента ([produceVariant]) — повторный
 * вызов Арбитра с другим версией промпта без дублирования остальных LLM-вызовов.
 */
@Component
class DiscretionaryStrategy(
    private val techAgent: TechnicalAnalysisAgent,
    private val fundAgent: FundamentalAnalysisAgent,
    private val stratAgent: StrategyAgent,
    private val contrAgent: ContrarianAgent,
    private val arbAgent: ArbitratorAgent,
    private val adaptiveRisk: AdaptiveRiskService,
) : Strategy {
    override val id = "DISCRETIONARY"

    data class ChainInputs(
        val tech: TechnicalReport,
        val fund: FundamentalReport,
        val draft: StrategyAgent.Draft,
        val challenge: ContrarianAgent.ChallengeReport,
        val snapshot: MarketSnapshot,
        val contextPrompt: String?,
        val adaptiveConfidence: Double,
    )

    private val chainCache = ConcurrentHashMap<String, ChainInputs>()

    override suspend fun evaluate(context: StrategyContext): StrategyDecision {
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
            )

        chainCache[key(context)] =
            ChainInputs(tech, fund, draft, challenge, context.snapshot, context.contextPrompt, adaptiveConf)
        return StrategyDecision(final.action, final.targetPrice, final.confidence, final.reasoning)
    }

    /**
     * Вариантная рука A/B-эксперимента: повторный вызов Арбитра с другим версией
     * промпта на тех же входах контрольной руки (семантический кэш обходится).
     * Если контрольная цепочка недоступна (не запускалась в этом цикле) — HOLD.
     */
    suspend fun produceVariant(
        context: StrategyContext,
        version: String,
    ): StrategyDecision {
        val inputs =
            chainCache.remove(key(context))
                ?: return StrategyDecision.hold(context.snapshot.currentPrice, "No control chain for variant")
        val variantFinal =
            arbAgent.adjudicate(
                draft = inputs.draft,
                challenge = inputs.challenge,
                tech = inputs.tech,
                fund = inputs.fund,
                snapshot = inputs.snapshot,
                cycleId = context.cycleId,
                contextPrompt = inputs.contextPrompt,
                adaptiveConfidence = inputs.adaptiveConfidence,
                version = version,
                bypassCache = true,
            )
        return StrategyDecision(variantFinal.action, variantFinal.targetPrice, variantFinal.confidence, variantFinal.reasoning)
    }

    private fun key(context: StrategyContext): String = "${context.ticker}|${context.cycleId}"
}
