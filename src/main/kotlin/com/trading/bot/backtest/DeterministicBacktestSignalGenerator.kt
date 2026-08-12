package com.trading.bot.backtest

import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Детерминированный генератор сигналов (режим по умолчанию, без LLM).
 *
 * Активен, пока `bt.agent.enabled=false`. Логика перенесена из
 * [BacktestEngine.signalAt] без изменений: RSI + MACD-гистограмма.
 * RSI<30 и MACD>0 → BUY; RSI>70 и MACD<0 → SELL; иначе HOLD.
 */
@Component
@ConditionalOnProperty(name = ["bt.agent.enabled"], havingValue = "false", matchIfMissing = true)
class DeterministicBacktestSignalGenerator : BacktestSignalGenerator {
    override suspend fun signal(
        ticker: String,
        candles: List<Candle>,
        index: Int,
        minBars: Int,
        cycleId: String,
    ): StrategyAction {
        val window = candles.subList(0, index + 1)
        if (window.size < minBars) return StrategyAction.HOLD
        val ind = IndicatorCalculator.calculate(window) ?: return StrategyAction.HOLD

        return when {
            ind.rsi < 30 && ind.macdHistogram > 0 -> StrategyAction.BUY
            ind.rsi > 70 && ind.macdHistogram < 0 -> StrategyAction.SELL
            else -> StrategyAction.HOLD
        }
    }
}
