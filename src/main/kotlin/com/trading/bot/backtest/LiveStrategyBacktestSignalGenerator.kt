package com.trading.bot.backtest

import com.trading.bot.application.StrategySelector
import com.trading.bot.application.strategy.BreakoutStrategy
import com.trading.bot.application.strategy.CnyRubStrategy
import com.trading.bot.application.strategy.GridStrategy
import com.trading.bot.application.strategy.MeanReversionStrategy
import com.trading.bot.application.strategy.OnlineMlDirectionStrategy
import com.trading.bot.application.strategy.ScalpingStrategy
import com.trading.bot.application.strategy.TrendFollowingStrategy
import com.trading.bot.config.BacktestConfig
import com.trading.bot.domain.risk.PerTickerRegime
import com.trading.bot.domain.risk.RegimeDetectionConfig
import com.trading.bot.domain.risk.RegimeDetector
import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId

/**
 * Research-фильтры входа (docs/17 этап 4c, pt.2, 2026-09-20). Аналог funding-veto/
 * ml-direction null-override: query-параметры перекрывают `bt.*`, null → конфиг.
 */
data class EntryFilterOverrides(
    val sessionFilterEnabled: Boolean? = null,
    val sessionFilterStartMinutes: Int? = null,
    val sessionFilterEndMinutes: Int? = null,
    val pullbackFilterEnabled: Boolean? = null,
    val pullbackEmaPeriod: Int? = null,
    val pullbackMaxDeviationPercent: Double? = null,
    val pullbackBlockOnUnknown: Boolean? = null,
) {
    val anyProvided: Boolean
        get() =
            sessionFilterEnabled != null ||
                sessionFilterStartMinutes != null ||
                sessionFilterEndMinutes != null ||
                pullbackFilterEnabled != null ||
                pullbackEmaPeriod != null ||
                pullbackMaxDeviationPercent != null ||
                pullbackBlockOnUnknown != null
}

/**
 * Входные фильтры (research, pt.2): session-фильтр (вход только в определённые
 * фазы торговой сессии) и pullback-фильтр (вход только в полосе отката к EMA —
 * не гнаться за ценой). Fail-closed по нехватке данных — на выбор оператора.
 */
class EntryFilters(
    val sessionEnabled: Boolean,
    val sessionStartMinutes: Int,
    val sessionEndMinutes: Int,
    val pullbackEnabled: Boolean,
    val pullbackEmaPeriod: Int,
    val pullbackMaxDeviationPercent: Double,
    val pullbackBlockOnUnknown: Boolean,
) {
    companion object {
        fun from(
            config: BacktestConfig,
            overrides: EntryFilterOverrides? = null,
        ): EntryFilters =
            EntryFilters(
                sessionEnabled = overrides?.sessionFilterEnabled ?: config.sessionFilterEnabled,
                sessionStartMinutes =
                    overrides?.sessionFilterStartMinutes ?: config.sessionFilterStartMinutes,
                sessionEndMinutes =
                    overrides?.sessionFilterEndMinutes ?: config.sessionFilterEndMinutes,
                pullbackEnabled = overrides?.pullbackFilterEnabled ?: config.pullbackFilterEnabled,
                pullbackEmaPeriod = overrides?.pullbackEmaPeriod ?: config.pullbackEmaPeriod,
                pullbackMaxDeviationPercent =
                    overrides?.pullbackMaxDeviationPercent ?: config.pullbackMaxDeviationPercent,
                pullbackBlockOnUnknown =
                    overrides?.pullbackBlockOnUnknown ?: config.pullbackBlockOnUnknown,
            )

        /** Фильтры выключены — не влияют на входы. */
        val PASS_THROUGH = EntryFilters(false, 600, 1080, false, 20, 1.0, false)
    }

    /** true → вход в бар [barTime] блокирован (вне окна сессии / вне полосы отката). */
    fun blocksEntry(
        barTime: LocalTime,
        close: BigDecimal,
        closes: List<BigDecimal>,
    ): Boolean {
        if (sessionEnabled) {
            val minutes = barTime.hour * 60 + barTime.minute
            if (minutes < sessionStartMinutes || minutes > sessionEndMinutes) return true
        }
        if (pullbackEnabled) {
            if (closes.size < pullbackEmaPeriod) return pullbackBlockOnUnknown
            val ema = IndicatorCalculator.ema(closes, pullbackEmaPeriod).last()
            if (!ema.isFinite() || ema <= 0) return true
            val deviation = Math.abs(close.toDouble() - ema) / ema * 100.0
            if (deviation > pullbackMaxDeviationPercent) return true
        }
        return false
    }
}

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
 *   2. If regime blocks entry (incl. UNKNOWN due to insufficient data — fail-closed)
 *      → HOLD;
 *   3. Filter strategies by [StrategySelector.eligibleStrategyIds];
 *   4. Weight signalStrength by [StrategySelector.fitScore].
 * When [regimeConfig] is null, regime is not applied (legacy pass-through).
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
 *
 * [mlDirection] — исследовательский онлайн-фильтр НАПРАВЛЕНИЯ (bt.ml-direction-enabled).
 * НЕ конкурирует за сигнал: обучается на каждом баре (онлайн-LR, без lookahead,
 * сброс модели по cycleId на каждый simulate — изоляция фолдов/MC) и применяется
 * к победителю среди детерминированных стратегий следующим образом:
 *   - если ML-направление уверенное и совпадает с направлением победителя — вход;
 *   - если ML-направление уверенное и ПРОТИВОПОЛОЖНО победителю — вход заблокирован
 *     (причина `ML_DIRECTION_VETO`);
 *   - если ML HOLD (нехватка данных/warmup/нет уверенности) — зависит от
 *     [mlDirectionBlockOnUnknown] (true: fail-closed блок, false: пропуск).
 */
class LiveStrategyBacktestSignalGenerator(
    private val regimeConfig: RegimeDetectionConfig? = null,
    private val adaptiveConfidenceThreshold: Double = 0.60,
    private val mlDirection: OnlineMlDirectionStrategy? = null,
    private val mlDirectionBlockOnUnknown: Boolean = false,
    private val entryFilters: EntryFilters? = null,
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

        val regime: PerTickerRegime? =
            regimeConfig?.let { RegimeDetector.detect(window, it) }

        if (regime != null && regime.blocksEntry) return StrategyAction.HOLD

        val eligibleIds = regime?.let { strategySelector.eligibleStrategyIds(it) }

        val context =
            StrategyContext(
                ticker = ticker,
                snapshot = snapshot,
                candles = window,
                indicators = indicators,
                cycleId = cycleId,
                regime = regime,
            )

        // ML-фильтр обучается на каждом баре (онлайн-LR без lookahead), даже если
        // в этом баре ни одна стратегия не дала сигнала.
        val mlDecision =
            mlDirection?.let {
                try {
                    it.evaluate(context)
                } catch (_: Exception) {
                    null
                }
            }

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
                    if (regime != null) {
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

        // Research-фильтры входа (docs/17 этап 4c): session (вход только в фазы
        // сессии) и pullback к EMA (не гнаться за ценой). Fail-closed по выбору
        // оператора; по умолчанию off.
        val entryBlocked =
            entryFilters?.let {
                try {
                    it.blocksEntry(
                        bar.time.toLocalTime(),
                        bar.closePrice,
                        window.map { c -> c.closePrice },
                    )
                } catch (_: Exception) {
                    false
                }
            } ?: false
        if (entryBlocked) return StrategyAction.HOLD

        // ML-фильтр направления: veto против направления победителя.
        val mlAction = mlDecision?.action ?: StrategyAction.HOLD
        return when {
            mlDecision == null -> {
                bestAction
            }

            mlAction == StrategyAction.HOLD -> {
                if (mlDirectionBlockOnUnknown) {
                    StrategyAction.HOLD
                } else {
                    bestAction
                }
            }

            mlAction != bestAction -> {
                StrategyAction.HOLD
            }

            else -> {
                bestAction
            }
        }
    }
}
