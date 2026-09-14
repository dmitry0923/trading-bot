package com.trading.bot.application.strategy

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.FundamentalAnalysisAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.agent.TechnicalAnalysisAgent
import com.trading.bot.config.LlmConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.infrastructure.llm.DeltaPromptStore
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.service.AdaptiveRiskService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component

/**
 * LLM как источник сигнала (P0, research/llm-signal-source; docs/17).
 *
 * Единственная стратегия, которая запускает полный агентный контур
 * (Technical + Fundamental -> Strategist -> Contrarian -> Arbitrator) в КОНКУРЕНЦИИ
 * за сигнал — в противовес [DiscretionaryStrategy]/[AdvisoryOnlyStrategy], которая
 * исключена из конкуренции (C-001) и работает только советником.
 *
 * Безопасность (fail-closed):
 *  - пока `trading.llm-signal-source=false` — [StrategyRunner] исключает стратегию
 *    из конкуренции, а сам [evaluate] возвращает HOLD с метрикой;
 *  - `trading.llm-signal-only=true` — включается ТОЛЬКО эта стратегия.
 *
 * Риск-параметры (quantity/SL/TP) стратегия НЕ назначает: они формируются общим
 * риск-пайплайном ниже (RiskEngine/PositionSizer) для любой стратегии-победителя.
 */
@Component
class LlmSignalStrategy(
    private val techAgent: TechnicalAnalysisAgent,
    private val fundAgent: FundamentalAnalysisAgent,
    private val stratAgent: StrategyAgent,
    private val contrAgent: ContrarianAgent,
    private val arbAgent: ArbitratorAgent,
    private val adaptiveRisk: AdaptiveRiskService,
    private val deltaStore: DeltaPromptStore,
    private val llmConfig: LlmConfig,
    private val tradingConfig: TradingConfig,
    private val meterRegistry: MeterRegistry,
) : Strategy {
    override val id: String = ID

    private val logger = KotlinLogging.logger {}

    override suspend fun evaluate(context: StrategyContext): StrategyDecision {
        if (!tradingConfig.llmSignalSourceEnabled) {
            meterRegistry.counter("llm.signal.disabled", Tags.of("ticker", context.ticker)).increment()
            return StrategyDecision.hold(context.snapshot.currentPrice, "LLM signal source disabled")
        }
        // R2-аудит (этап 3, risk): полная LLM-цепочка (tech+fund -> strategist ->
        // contrarian -> arbitrator) под жёстким дедлайном [TradingConfig.llmSignalBudgetMs].
        // LLM как источник сигнала НЕ должен задерживать order-execution: при превышении
        // бюджета — fail-closed HOLD + метрика `llm.signal.timeout`.
        return try {
            withTimeout(tradingConfig.llmSignalBudgetMs) {
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
                    version = PromptRegistry.DEFAULT_VERSION,
                    bypassCache = false,
                )
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) {
                "LLM signal chain timed out after ${tradingConfig.llmSignalBudgetMs}ms on ${context.ticker} -> HOLD"
            }
            meterRegistry.counter("llm.signal.timeout", Tags.of("ticker", context.ticker)).increment()
            StrategyDecision.hold(context.snapshot.currentPrice, "LLM signal budget exceeded")
        }
    }

    companion object {
        /** Стабильный id стратегии (фильтры в [com.trading.bot.application.StrategyRunner]). */
        const val ID = "LLM_SIGNAL"
    }
}
