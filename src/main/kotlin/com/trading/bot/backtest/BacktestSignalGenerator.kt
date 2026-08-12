package com.trading.bot.backtest

import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle

/**
 * Источник сигналов для бэктеста.
 *
 * Две реализации, включаются взаимоисключающе через `bt.agent.enabled`:
 * - [DeterministicBacktestSignalGenerator] — детерминированная RSI+MACD эвристика
 *   (режим по умолчанию, без LLM);
 * - [AgentBacktestSignalGenerator] — конвейер живых LLM-агентов
 *   (roadmap 13.8.1, включается профилем `backtest`).
 *
 * Вызов должен быть детерминирован по `candles[0..index]` (закрытый бар i — сигнал
 * исполняется по открытию бара i+1, никакого look-ahead).
 */
interface BacktestSignalGenerator {
    /**
     * @param ticker тикер инструмента
     * @param candles все свечи прогона (сортированные по времени)
     * @param index индекс последнего закрытого бара (signal-бар)
     * @param minBars минимальное число баров для сигнала (warm-up)
     * @param cycleId идентификатор цикла для agent-логов
     * @return сигнал BUY/SELL/HOLD
     */
    suspend fun signal(
        ticker: String,
        candles: List<Candle>,
        index: Int,
        minBars: Int,
        cycleId: String,
    ): StrategyAction
}
