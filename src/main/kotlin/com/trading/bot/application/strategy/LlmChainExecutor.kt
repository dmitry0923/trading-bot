package com.trading.bot.application.strategy

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.FundamentalAnalysisAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.agent.TechnicalAnalysisAgent
import com.trading.bot.config.LlmConfig
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.infrastructure.llm.DeltaPromptStore
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.service.AdaptiveRiskService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Общий исполнитель агентного LLM-контура (P0, research/llm-signal-source).
 *
 * Technical + Fundamental -> Strategist -> Contrarian -> Arbitrator — единственная
 * копия цепочки в коде. Используется двумя стратегиями:
 *  - [DiscretionaryStrategy] (A/B-рука и ручной вызов, advisory);
 *  - [LlmSignalStrategy] (участие LLM в конкуренции за сигнал).
 *
 * Параллельно запускаются только независимые вызовы (tech, fund, адаптивный
 * порог); далее цепочка строго последовательная. Delta-промпты (roadmap 13.8)
 * сокращают входные токены стратега/контрариана.
 */
object LlmChainExecutor {
    suspend fun run(
        techAgent: TechnicalAnalysisAgent,
        fundAgent: FundamentalAnalysisAgent,
        stratAgent: StrategyAgent,
        contrAgent: ContrarianAgent,
        arbAgent: ArbitratorAgent,
        adaptiveRisk: AdaptiveRiskService,
        deltaStore: DeltaPromptStore,
        llmConfig: LlmConfig,
        meterRegistry: MeterRegistry,
        context: StrategyContext,
        version: String,
        bypassCache: Boolean,
    ): StrategyDecision =
        coroutineScope {
            val (tech, fund, adaptiveConf) =
                coroutineScope {
                    val t = async { techAgent.analyze(context.ticker, context.candles, context.snapshot, context.cycleId) }
                    val f = async { fundAgent.analyze(context.ticker, context.cycleId) }
                    val a = async { adaptiveRisk.getAdaptiveConfidenceThreshold(context.ticker) }
                    Triple(t.await(), f.await(), a.await())
                }

            val techDelta = if (llmConfig.deltaPromptsEnabled) deltaStore.techDelta(context.ticker, tech) else null
            val fundDelta = if (llmConfig.deltaPromptsEnabled) deltaStore.fundDelta(context.ticker, fund) else null

            val draft =
                stratAgent.formulate(
                    context.ticker,
                    tech,
                    fund,
                    context.snapshot,
                    context.cycleId,
                    adaptiveThreshold = adaptiveConf,
                    techDelta = techDelta,
                    fundDelta = fundDelta,
                )
            val challenge = contrAgent.challenge(draft, tech, fund, context.snapshot, context.cycleId, techDelta = techDelta)
            if (!challenge.llmAvailable) {
                // Fail-closed (review/P1): контраргументы не получены (LLM недоступен/
                // сбой/схема). Объективную оценку рисков провести нельзя — HOLD.
                meterRegistry
                    .counter(
                        "llm.chain.contrarian_unavailable",
                        Tags.of("ticker", context.ticker),
                    ).increment()
                return@coroutineScope StrategyDecision.hold(
                    context.snapshot.currentPrice,
                    "Contrarian LLM unavailable -> HOLD",
                )
            }
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

            if (llmConfig.deltaPromptsEnabled) {
                deltaStore.update(context.ticker, tech, fund)
                meterRegistry
                    .counter(
                        "agent.delta.prompts",
                        Tags.of("agent", "discretionary-chain", "mode", if (techDelta != null) "DELTA" else "FULL"),
                    ).increment()
            }

            StrategyDecision(final.action, final.targetPrice, final.signalStrength, final.reasoning)
        }
}
