package com.trading.bot.backtest

import com.trading.bot.application.StrategySelector
import com.trading.bot.application.strategy.BreakoutStrategy
import com.trading.bot.application.strategy.CnyRubStrategy
import com.trading.bot.application.strategy.GridStrategy
import com.trading.bot.application.strategy.MeanReversionStrategy
import com.trading.bot.application.strategy.ScalpingStrategy
import com.trading.bot.application.strategy.TrendFollowingStrategy
import com.trading.bot.domain.risk.PerTickerRegime
import com.trading.bot.domain.risk.RegimeDetectionConfig
import com.trading.bot.domain.risk.RegimeDetector
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
 * The winner is selected by maximum weighted signal strength, same as LIVE
 * (ties broken by registration order — deterministic).
 *
 * Regime parity (P0#1): when [regimeConfig] is non-null, the generator
 * mirrors [com.trading.bot.application.StrategyRunner.runAll] behaviour:
 *   1. Detect per-ticker regime via [RegimeDetector];
 *   2. If regime blocks entry → HOLD;
 *   3. Filter strategies by [StrategySelector.eligibleStrategyIds];
 *   4. Weight signalStrength by [StrategySelector.fitScore].
 *
 * Adaptive confidence gate (P0#2): mirrors [com.trading.bot.service.StrategyService]
 * adaptive threshold. Signals with strength below [adaptiveConfidenceThreshold]
 * (or non-finite, e.g. NaN) are gated to HOLD.
 *
 * This ensures signal parity: backtest tests the same strategy decisions
 * that would fire in LIVE trading, not a simplified heuristic.
 *
 * Не Spring-бин: инстанцируется напрямую ([BacktestSignalGeneratorConfig] при
 * `bt.agent.live-strategies=true`). Все стратегии создаются локально на каждый
 * вызов сигнала не нужны — они stateless, поэтому список создаётся один раз.
 * Детерминирован по `candles[0..index]`: никаких LLM, часов или внешних данных.
 */
class LiveStrategyBacktestSignalGenerator(
    private val regimeConfig: RegimeDetectionConfig? = null,
    private val adaptiveConfidenceThreshold: Double = 0.60,
) : BacktestSignalGenerator {
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

    private val strategySelector = StrategySelector()

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

        val regimeEnabled = regimeConfig != null
        val regime: PerTickerRegime =
            if (regimeEnabled) {
                RegimeDetector.detect(window, regimeConfig!!)
            } else {
                PerTickerRegime.UNKNOWN
            }

        if (regimeEnabled && regime.blocksEntry) return StrategyAction.HOLD

        val eligibleIds = if (regimeEnabled) strategySelector.eligibleStrategyIds(regime) else null

        val context =
            StrategyContext(
                ticker = ticker,
                snapshot = snapshot,
                candles = window,
                indicators = indicators,
                cycleId = cycleId,
                regime = regime,
            )

        var bestAction = StrategyAction.HOLD
        var bestStrength = 0.0

        for (strategy in strategies) {
            if (eligibleIds != null && strategy.id !in eligibleIds) continue
            val decision =
                try {
                    strategy.evaluate(context)
                } catch (_: Exception) {
                    continue
                }
            if (decision.action != StrategyAction.HOLD) {
                val strength =
                    if (regimeEnabled) {
                        val fit = strategySelector.fitScore(strategy.id, regime)
                        (decision.signalStrength * fit).coerceIn(0.0, 1.0)
                    } else {
                        decision.signalStrength
                    }
                if (strength > bestStrength) {
                    bestAction = decision.action
                    bestStrength = strength
                }
            }
        }

        if (bestAction == StrategyAction.HOLD) return StrategyAction.HOLD

        if (!bestStrength.isFinite() || bestStrength < adaptiveConfidenceThreshold) {
            return StrategyAction.HOLD
        }

        return bestAction
    }
}
