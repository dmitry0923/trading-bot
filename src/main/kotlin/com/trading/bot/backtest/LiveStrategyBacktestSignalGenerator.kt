package com.trading.bot.backtest

import com.trading.bot.application.strategy.BreakoutStrategy
import com.trading.bot.application.strategy.CnyRubStrategy
import com.trading.bot.application.strategy.GridStrategy
import com.trading.bot.application.strategy.MeanReversionStrategy
import com.trading.bot.application.strategy.ScalpingStrategy
import com.trading.bot.application.strategy.TrendFollowingStrategy
import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import java.time.ZoneId

/**
 * Backtest signal generator that mirrors the LIVE strategy pipeline.
 *
 * Uses the same deterministic strategies as LIVE (StrategyRunner):
 * TrendFollowing, Breakout, Scalping, MeanReversion, Grid, CnyRubStrategy.
 * The winner is selected by maximum signal strength, same as LIVE
 * (ties broken by registration order — deterministic).
 *
 * This ensures signal parity: backtest tests the same strategy decisions
 * that would fire in LIVE trading, not a simplified heuristic.
 *
 * Не Spring-бин: инстанцируется напрямую ([BacktestSignalGeneratorConfig] при
 * `bt.agent.live-strategies=true`). Все стратегии создаются локально на каждый
 * вызов сигнала не нужны — они stateless, поэтому список создаётся один раз.
 * Детерминирован по `candles[0..index]`: никаких LLM, часов или внешних данных.
 */
class LiveStrategyBacktestSignalGenerator : BacktestSignalGenerator {

    private val strategies: List<Strategy> =
        listOf(
            TrendFollowingStrategy(),
            BreakoutStrategy(),
            ScalpingStrategy(),
            MeanReversionStrategy(),
            GridStrategy(),
            // Микроструктура (bid/ask/OBI) в бэктесте отсутствует — стратегия
            // детерминированно падает в fallback-режим чистого mean-reversion.
            CnyRubStrategy(),
        )

    override suspend fun signal(
        ticker: String,
        candles: List<Candle>,
        index: Int,
        minBars: Int,
        cycleId: String,
    ): StrategyAction {
        if (index < minBars) return StrategyAction.HOLD

        val window = candles.subList(0, index + 1)
        val indicators = IndicatorCalculator.calculate(window)
        val bar = candles[index]

        val snapshot =
            MarketSnapshot(
                ticker = ticker,
                currentPrice = bar.closePrice,
                volume = bar.volume,
                timestamp = bar.time.atZone(ZoneId.systemDefault()).toInstant(),
            )

        val context =
            StrategyContext(
                ticker = ticker,
                snapshot = snapshot,
                candles = window,
                indicators = indicators,
                cycleId = cycleId,
            )

        var bestAction = StrategyAction.HOLD
        var bestStrength = 0.0

        for (strategy in strategies) {
            val decision =
                try {
                    strategy.evaluate(context)
                } catch (_: Exception) {
                    // Стратегия упала — пропускаем (как в StrategyRunner: цикл не прерывается).
                    continue
                }
            if (decision.action != StrategyAction.HOLD && decision.signalStrength > bestStrength) {
                bestAction = decision.action
                bestStrength = decision.signalStrength
            }
        }

        return bestAction
    }
}
