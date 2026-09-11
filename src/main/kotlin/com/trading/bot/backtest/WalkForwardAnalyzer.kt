package com.trading.bot.backtest

import com.trading.bot.model.entity.Candle
import java.math.BigDecimal

/**
 * Единый конфиг walk-forward анализатора.
 *
 * Оба публичных эндпоинта (`/validate` и DeploymentGate) создают ровно такой
 * конфиг и передают его в [WalkForwardAnalyzer] — это исключает расхождение
 * пайплайнов по folds / train-window / warmup / costs / sizing / strategy params.
 */
data class WfaConfig(
    val folds: Int = 4,
    val expanding: Boolean = true,
    val initialCapital: BigDecimal = BigDecimal("100000"),
    val minBarsForSignal: Int = 30,
    val leverage: Double = 1.0,
    val riskPerTradePercent: Double? = null,
    val futuresMaxContractsPerPosition: Int? = null,
    val signalGeneratorOverride: BacktestSignalGenerator? = null,
)

/**
 * Единственная реализация walk-forward анализа (canonical WFA).
 *
 * И `/api/v1/backtest/{ticker}/validate`, и DeploymentGate (через
 * [FinalHoldoutValidator]) обязаны выполнять WFA ТОЛЬКО через этот контракт.
 * Никакого второго пути расчёта OOS-метрик не существует — расхождение
 * validate vs gate сводится к РАЗЛИЧИЮ ВХОДНЫХ ДАННЫХ (holdout-сплит 20%),
 * а не к различию алгоритмов.
 */
interface WalkForwardAnalyzer {
    suspend fun run(
        ticker: String,
        candles: List<Candle>,
        config: WfaConfig,
    ): ValidationResult
}
