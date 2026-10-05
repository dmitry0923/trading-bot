package com.trading.bot.backtest

import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.VetoResult
import com.trading.bot.config.BacktestAgentConfig
import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.ZoneId

/**
 * Генератор сигналов с LLM-veto поверх детерминированного baseline CNYRUBF (Вариант B).
 *
 * **Проверяемая гипотеза (docs/24):** детерминированный RSI+MACD сигнал как основа,
 * LLM через ContrarianAgent → ArbitratorAgent.adjudicate() как фильтр убыточных входов.
 *
 * Механика (Вариант B):
 * 1. Детерминированный сигнал (baseline CNYRUBF): RSI+MACD — наследует
 *    [DeterministicBacktestSignalGenerator].
 * 2. При HOLD от детерминированного сигнала → пропускаем LLM-вызов.
 * 3. При BUY/SELL → veto через [LlmVeto] (ContrarianAgent + ArbitratorAgent).
 * 4. Fail-closed: если LLM недоступен → HOLD (не торговать). Это [VetoResult.HOLD].
 * 5. Метрика bt_llm_veto_blocked_total инкрементируется при каждом блоке вето.
 *
 * Активен при `bt.agent.veto-mode=true` (отдельный профиль от `bt.agent.enabled`).
 *
 * **OBI/микроструктура закрыта (коммит 39ef4006):** MarketMicrostructureAgent с OBI
 * не используется. Единственная открытая гипотеза — данный LLM-veto Вариант B.
 *
 * @property contrarianAgent агент-«адвокат дьявола» (оценка риска входа).
 * @property arbitratorAgent арбитр (финальный вердикт).
 * @property agentConfig конфигурация backtest-агентов (prefix "bt.agent").
 * @property meterRegistry реестр метрик Micrometer.
 */
@Component
@ConditionalOnProperty(name = ["bt.agent.veto-mode"], havingValue = "true")
class AgentVetoBacktestSignalGenerator(
    private val contrarianAgent: ContrarianAgent,
    private val arbitratorAgent: ArbitratorAgent,
    private val agentConfig: BacktestAgentConfig,
    private val meterRegistry: MeterRegistry,
) : BacktestSignalGenerator {

    private val deterministicGenerator = DeterministicBacktestSignalGenerator()

    override suspend fun signal(
        ticker: String,
        candles: List<Candle>,
        index: Int,
        minBars: Int,
        cycleId: String,
    ): StrategyAction {
        // Шаг 1: детерминированный baseline сигнал (RSI + MACD).
        val baselineAction = deterministicGenerator.signal(ticker, candles, index, minBars, cycleId)

        // Шаг 2: при HOLD от baseline — LLM не вызываем.
        if (baselineAction == StrategyAction.HOLD) {
            return StrategyAction.HOLD
        }

        // Шаг 3: LLM-veto для BUY/SELL сигналов.
        val bar = candles[index]
        val snapshot =
            MarketSnapshot(
                ticker = ticker,
                currentPrice = bar.closePrice,
                volume = bar.volume,
                timestamp = bar.time.atZone(ZoneId.systemDefault()).toInstant(),
            )
        val window = candles.subList(0, index + 1)
        val indicators = IndicatorCalculator.calculate(window)

        val vetoSettings = LlmVetoSettings.from(agentConfig)
        val llmVeto =
            LlmVeto(
                contrarianAgent = contrarianAgent,
                arbitratorAgent = arbitratorAgent,
                settings = vetoSettings,
                meterRegistry = meterRegistry,
            )

        val verdict =
            llmVeto.veto(
                ticker = ticker,
                action = baselineAction,
                strength = DEFAULT_STRENGTH,
                snapshot = snapshot,
                indicators = indicators,
                cycleId = cycleId,
                barTime = bar.time,
            )

        // Шаг 4: маршрутизация вердикта.
        return when {
            verdict.allow -> {
                meterRegistry
                    .counter("bt_llm_veto_allowed_total", Tags.of("ticker", ticker, "reason", verdict.reason))
                    .increment()
                baselineAction
            }
            else -> {
                // Блокировка — fail-closed (LLM недоступен) или явный HOLD арбитра.
                meterRegistry
                    .counter("bt_llm_veto_blocked_total", Tags.of("ticker", ticker, "reason", verdict.reason))
                    .increment()
                StrategyAction.HOLD
            }
        }
    }

    companion object {
        /** Уверенность draft'а для вето-пути: 0.5 (нейтральная, не влияет на решение LLM). */
        private const val DEFAULT_STRENGTH = 0.5
    }
}
