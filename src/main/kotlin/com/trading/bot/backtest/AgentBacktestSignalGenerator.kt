package com.trading.bot.backtest

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.FundamentalAnalysisAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.agent.TechnicalAnalysisAgent
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.ZoneId

/**
 * Генератор сигналов на конвейере живых LLM-агентов (roadmap 13.8.1).
 *
 * Активен при `bt.agent.enabled=true` (профиль `backtest`).
 *
 * - Сэмплирование: оценка каждые `bt.agent.sample-every` баров, между сэмплами HOLD.
 * - Детерминизм: temperature=0.0, semantic cache по fingerprint бара.
 * - Изоляция кэша: namespace `bt.agent.cache-namespace` ("backtest") — бэктест
 *   не читает/не пишет live-кэш (защита от look-ahead bias и загрязнения).
 * - Технический и фундаментальный агенты вызываются параллельно.
 * - Цепочка соответствует live-пути: tech → fund → strategy → contrarian → arbitrator.
 *
 * При недоступности LLM агенты возвращают детерминированные fallback'и
 * (INSUFFICIENT_DATA/NEUTRAL/HOLD) — прогон идёт без API-ключа.
 */
@Component
@ConditionalOnProperty(name = ["bt.agent.enabled"], havingValue = "true")
class AgentBacktestSignalGenerator(
    private val techAgent: TechnicalAnalysisAgent,
    private val fundAgent: FundamentalAnalysisAgent,
    private val stratAgent: StrategyAgent,
    private val contrAgent: ContrarianAgent,
    private val arbAgent: ArbitratorAgent,
    private val config: BacktestAgentConfig,
    private val meterRegistry: MeterRegistry,
) : BacktestSignalGenerator {
    override suspend fun signal(
        ticker: String,
        candles: List<Candle>,
        index: Int,
        minBars: Int,
        cycleId: String,
    ): StrategyAction {
        if (index < minBars) return StrategyAction.HOLD
        if (index % config.sampleEvery != 0) return StrategyAction.HOLD
        meterRegistry.counter("backtest.agent.evaluations", Tags.of("ticker", ticker)).increment()
        return evaluate(ticker, candles, index, cycleId)
    }

    private suspend fun evaluate(
        ticker: String,
        candles: List<Candle>,
        index: Int,
        cycleId: String,
    ): StrategyAction {
        val bar = candles[index]
        val snapshot =
            MarketSnapshot(
                ticker = ticker,
                currentPrice = bar.closePrice,
                volume = bar.volume,
                timestamp = bar.time.atZone(ZoneId.systemDefault()).toInstant(),
            )
        val window = candles.subList(0, index + 1)

        // Все аргументы передаются явно (включая default-параметры агентов):
        // вызовы через синтетический $default-мост не мокаются в unit-тестах.
        val (tech, fund) =
            coroutineScope {
                val t =
                    async {
                        techAgent.analyze(
                            ticker,
                            window,
                            snapshot,
                            cycleId,
                            PromptRegistry.DEFAULT_VERSION,
                            config.temperature,
                            config.cacheNamespace,
                        )
                    }
                val f =
                    async {
                        fundAgent.analyze(
                            ticker,
                            cycleId,
                            PromptRegistry.DEFAULT_VERSION,
                            config.temperature,
                            config.cacheNamespace,
                        )
                    }
                t.await() to f.await()
            }

        val draft =
            stratAgent.formulate(
                ticker,
                tech,
                fund,
                snapshot,
                cycleId,
                adaptiveThreshold = 0.5,
                version = PromptRegistry.DEFAULT_VERSION,
                temperature = config.temperature,
                cacheNamespace = config.cacheNamespace,
            )
        val challenge =
            contrAgent.challenge(
                draft,
                tech,
                fund,
                snapshot,
                cycleId,
                version = PromptRegistry.DEFAULT_VERSION,
                temperature = config.temperature,
                cacheNamespace = config.cacheNamespace,
            )
        val decision =
            arbAgent.adjudicate(
                draft,
                challenge,
                tech,
                fund,
                snapshot,
                cycleId,
                contextPrompt = null,
                adaptiveConfidence = 0.60,
                version = PromptRegistry.DEFAULT_VERSION,
                bypassCache = false,
                temperature = config.temperature,
                cacheNamespace = config.cacheNamespace,
            )

        meterRegistry.counter("backtest.agent.signal", Tags.of("ticker", ticker, "action", decision.action.name)).increment()
        return decision.action
    }
}
