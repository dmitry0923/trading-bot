package com.trading.bot.backtest

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.VetoResult
import com.trading.bot.config.BacktestConfig
import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import io.micrometer.core.instrument.MeterRegistry
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
 * @property backtestConfig конфигурация veto-параметров (prefix "bt", поля llm-veto-*).
 * @property meterRegistry реестр метрик Micrometer.
 */
@Component
@ConditionalOnProperty(name = ["bt.agent.veto-mode"], havingValue = "true")
class AgentVetoBacktestSignalGenerator(
    private val contrarianAgent: ContrarianAgent,
    private val arbitratorAgent: ArbitratorAgent,
    private val backtestConfig: BacktestConfig,
    private val meterRegistry: MeterRegistry,
) : BacktestSignalGenerator {
    private val deterministicGenerator = DeterministicBacktestSignalGenerator()

    /**
     * Один [LlmVeto] на генератор, а не на каждый вызов [signal].
     *
     * Внутри [LlmVeto] живут мемоизация вердиктов по (ticker, бар, действие) и
     * счётчик уникальных кандидатов для `sampleEvery`. Если создавать veto в
     * методе, оба состояния обнуляются на каждом баре: WFA заново проигрывает одни
     * и те же бары в каждом фолде и в каждой ячейке сетки подбора SL/TP, поэтому
     * платные вызовы LLM повторялись бы, а `sampleEvery` (счётчик всегда 0) свёлся бы
     * к проверке всех кандидатов подряд — то есть документированное поведение
     * «каждый N-й кандидат» и воспроизводимость прогона не выполнялись бы.
     */
    private val llmVeto: LlmVeto by lazy {
        LlmVeto(
            contrarianAgent = contrarianAgent,
            arbitratorAgent = arbitratorAgent,
            settings = LlmVetoSettings.from(backtestConfig).copy(enabled = true),
            meterRegistry = meterRegistry,
        )
    }

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
        // Счётчики НЕ инкрементятся здесь: их уже ведёт сам LlmVeto (метрики
        // bt_llm_veto_allowed_total/blocked_total). Прежняя дублирующая запись в
        // генераторе удваивала каждое наблюдение, из-за чего block rate по метрикам
        // нельзя было использовать в research-свипе.
        return if (verdict.allow) baselineAction else StrategyAction.HOLD
    }

    companion object {
        /** Уверенность draft'а для вето-пути: 0.5 (нейтральная, не влияет на решение LLM). */
        private const val DEFAULT_STRENGTH = 0.5
    }
}
