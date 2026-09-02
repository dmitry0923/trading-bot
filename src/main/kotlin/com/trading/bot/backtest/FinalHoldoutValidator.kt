package com.trading.bot.backtest

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.model.entity.Candle
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Результат финального holdout-валидирования (статистический аудит P1).
 *
 * Замыкает риск OOS-leakage: walk-forward ([BacktestValidator]) выполняется ТОЛЬКО
 * на данных ДО [holdoutStart], а независимый [holdout]-окно (последние
 * [holdoutFraction] истории) трогается РОВНО один раз зафиксированными
 * параметрами — после того как WFA принял стратегию.
 *
 * @property walkForward результат WFA на данных до holdout-границы
 * @property holdout результат одноразового прогона на независимом holdout-окне
 * @property paramsUsed параметры, зафиксированные по [walkForward] (последний
 *   выбранный фолдом кандидат) и применённые к holdout
 */
data class HoldoutValidation(
    val walkForward: ValidationResult,
    val holdout: BacktestResult,
    val paramsUsed: GridParams,
) {
    /**
     * Минимальный порог проходимости holdout: WFA принял стратегию на данных вне
     * holdout, И независимый holdout тоже passable. Если оба — [passed].
     */
    val passed: Boolean
        get() = walkForward.isPassable() && holdout.isPassable()
}

/**
 * Финальное holdout-валидирование стратегии (аудит P1: устранение OOS-leakage).
 *
 * В отличие от walk-forward, где [BacktestValidator] использует ВСЮ доступную
 * историю (в т.ч. самый свежий сегмент), здесь последние [holdoutFraction]
 * истории ПРОСТО РЕЗЕРВИРУЮТСЯ и НИКОГДА не участвуют в настройке. Если после
 * просмотра holdout параметры меняются — это новый цикл, и holdout перерезается.
 */
@Component
class FinalHoldoutValidator(
    private val backtestValidator: BacktestValidator,
    private val backtestEngine: BacktestEngine,
    private val instrumentsConfig: InstrumentsConfig = InstrumentsConfig(),
) {
    private val logger = KotlinLogging.logger {}

    /**
     * @param holdoutFraction доля КОНЦА истории, резервируемая под holdout (0..1),
     *   по умолчанию 0.20 (последние 20%).
     */
    suspend fun validate(
        ticker: String,
        candles: List<Candle>,
        holdoutFraction: Double = 0.20,
        folds: Int = 4,
        expanding: Boolean = true,
        initialCapital: BigDecimal = BigDecimal("100000"),
        minBarsForSignal: Int = 30,
        leverage: Double = 1.0,
        riskPerTradePercent: Double? = null,
        futuresMaxContractsPerPosition: Int? = null,
        signalGeneratorOverride: BacktestSignalGenerator? = null,
    ): HoldoutValidation {
        require(holdoutFraction > 0.0 && holdoutFraction < 1.0) { "holdoutFraction must be in (0, 1)" }
        val sorted = candles.sortedBy { it.time }
        if (sorted.size < 2) {
            throw IllegalArgumentException("insufficient candles for holdout: ${sorted.size}")
        }
        val holdoutSize = (sorted.size * holdoutFraction).toInt().coerceAtLeast(1)
        val holdoutStart = sorted.size - holdoutSize
        val wfaCandles = sorted.subList(0, holdoutStart)
        val holdoutCandles = sorted.subList(holdoutStart, sorted.size)

        // Walk-forward выполняется ТОЛЬКО на данных до holdout-границы.
        val walkForward =
            backtestValidator.validate(
                ticker,
                wfaCandles,
                folds = folds,
                expanding = expanding,
                initialCapital = initialCapital,
                minBarsForSignal = minBarsForSignal,
                leverage = leverage,
                riskPerTradePercent = riskPerTradePercent,
                futuresMaxContractsPerPosition = futuresMaxContractsPerPosition,
                signalGeneratorOverride = signalGeneratorOverride,
            )

        // Параметры фиксируются по последнему выбранному фолдом кандидату.
        val paramsUsed =
            walkForward.folds.lastOrNull()?.let { f ->
                GridParams(
                    slPercent = f.chosenSlPercent,
                    tpPercent = f.chosenTpPercent,
                    slPoints = f.chosenSlPoints,
                    tpPoints = f.chosenTpPoints,
                )
            } ?: defaultGrid(ticker)

        // Одноразовый финальный прогон на независимом holdout с зафиксированными параметрами.
        val holdout =
            backtestEngine.simulate(
                ticker,
                holdoutCandles,
                initialCapital,
                minBarsForSignal,
                paramsUsed.slPercent,
                paramsUsed.tpPercent,
                commissionMultiplier = 1.0,
                slippageMultiplier = 1.0,
                slPoints = paramsUsed.slPoints,
                tpPoints = paramsUsed.tpPoints,
                leverage = leverage,
                capitalSlice = null,
                riskPerTradePercent = riskPerTradePercent,
                futuresMaxContractsPerPosition = futuresMaxContractsPerPosition,
                signalGeneratorOverride = signalGeneratorOverride,
            )

        val result = HoldoutValidation(walkForward, holdout, paramsUsed)
        logger.info {
            "Holdout $ticker: " +
                "wfa=${if (walkForward.isPassable()) "PASS" else "FAIL"} " +
                "holdout=${if (holdout.isPassable()) "PASS" else "FAIL"} " +
                "trades=${holdout.totalTrades} " +
                "return=${"%.2f".format(holdout.totalReturn * 100)}% " +
                "-> ${if (result.passed) "PASSED" else "FAILED"}"
        }
        return result
    }

    private fun defaultGrid(ticker: String): GridParams =
        if (instrumentsConfig.isFutures(ticker)) {
            GridParams(slPoints = 300, tpPoints = 600)
        } else {
            GridParams(slPercent = 0.02, tpPercent = 0.04)
        }
}
