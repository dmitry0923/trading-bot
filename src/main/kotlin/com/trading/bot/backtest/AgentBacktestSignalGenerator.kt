package com.trading.bot.backtest

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.FundamentalAnalysisAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.agent.TechnicalAnalysisAgent
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
 * - Порог уверенности — единый `bt.agent.confidence-threshold` (0.60) для стратега
 *   и арбитра, как live-fallback без статистики (адаптивный порог в бэктесте не
 *   вычисляется: истории сделок в прогоне нет, а обращаться к live-истории нельзя).
 * - Версия промптов `bt.agent.prompt-version` (research: aggressive) и минимальная
 *   уверенность тех-отчёта `bt.agent.tech-min-signal-strength` (research: 0.0 —
 *   LLM даёт BUY/SELL по одному сильному анализу)
 * - Бюджет цепочки `bt.agent.signal-budget-ms` (research, 0 = выключен): при
 *   превышении — fail-closed HOLD + метрика `backtest.agent.timeout{cause=budget}`,
 *   как в live ([com.trading.bot.application.strategy.LlmSignalStrategy], R2).
 *   По умолчанию 0, чтобы уже измеренные LLM-прогоны не менялись.
 * - Инъекция таймаутов `bt.agent.timeout-injection-rate` (research, 0.0 = выключено):
 *   доля сэмплов, где таймаут имитируется реальной задержкой внутри бюджета
 *   (детерминированно по тикеру и индексу бара, [LlmTimeoutInjection]). Нужна, чтобы
 *   доказать, что таймаут даёт HOLD, а не вход по детерминированному fallback'у.
 *
 * При недоступности LLM агенты возвращают детерминированные fallback'и
 * (INSUFFICIENT_DATA/NEUTRAL/HOLD) — прогон идёт без API-ключа. Это НЕ то же самое,
 * что таймаут: таймаут бюджета прерывает цепочку целиком и всегда даёт HOLD.
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
    /**
     * Конфигурация инъекции читается лениво: `@ConfigurationProperties` связывается
     * после конструктора, поэтому на этапе создания бина значений ещё нет.
     * Ошибка конфигурации (rate вне 0.0…1.0) поднимается на первом же сэмпле.
     */
    private val timeoutInjection by lazy {
        val injection = LlmTimeoutInjection.from(config.timeoutInjectionRate)
        // Инъекция требует бюджета: без него задержка не на что опереться и таймаут
        // неотличим от обычного вызова. Конфигурационная ошибка, а не HOLD.
        require(!injection.enabled || config.signalBudgetMs > 0) {
            "bt.agent.timeout-injection-rate=${config.timeoutInjectionRate} требует bt.agent.signal-budget-ms > 0"
        }
        injection
    }

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
        // Валидация пары budget/injection — до раннего выхода по бюджету.
        val injection = timeoutInjection
        val budgetMs = config.signalBudgetMs
        if (budgetMs <= 0) return runChain(ticker, candles, index, cycleId)
        val injected = injection.shouldTimeout(ticker, index)
        return try {
            withTimeout(budgetMs) {
                if (injected) {
                    // Реальная задержка внутри бюджета: срабатывает тот же
                    // TimeoutCancellationException, что и в live, без отдельной ветки.
                    delay(budgetMs)
                }
                runChain(ticker, candles, index, cycleId)
            }
        } catch (e: TimeoutCancellationException) {
            meterRegistry
                .counter("backtest.agent.timeout", Tags.of("ticker", ticker, "cause", if (injected) "injected" else "budget"))
                .increment()
            meterRegistry.counter("backtest.agent.signal", Tags.of("ticker", ticker, "action", "HOLD")).increment()
            StrategyAction.HOLD
        }
    }

    private suspend fun runChain(
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
                            config.promptVersion,
                            config.temperature,
                            config.cacheNamespace,
                        )
                    }
                val f =
                    async {
                        fundAgent.analyze(
                            ticker,
                            cycleId,
                            config.promptVersion,
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
                adaptiveThreshold = config.confidenceThreshold,
                version = config.promptVersion,
                temperature = config.temperature,
                cacheNamespace = config.cacheNamespace,
                techMinSignalStrength = config.techMinSignalStrength,
            )
        val challenge =
            contrAgent.challenge(
                draft,
                tech,
                fund,
                snapshot,
                cycleId,
                version = config.promptVersion,
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
                adaptiveConfidence = config.confidenceThreshold,
                version = config.promptVersion,
                bypassCache = false,
                temperature = config.temperature,
                cacheNamespace = config.cacheNamespace,
            )

        meterRegistry.counter("backtest.agent.signal", Tags.of("ticker", ticker, "action", decision.action.name)).increment()
        return decision.action
    }
}
