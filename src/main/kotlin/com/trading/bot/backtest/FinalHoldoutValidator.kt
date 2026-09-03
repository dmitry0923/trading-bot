package com.trading.bot.backtest

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.model.entity.Candle
import com.trading.bot.service.BuildIdentity
import com.trading.bot.service.LiveStrategyFingerprintProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Результат финального holdout-валидирования (статистический аудит P1).
 *
 * Замыкает риск OOS-leakage: walk-forward ([BacktestValidator]) и базовый
 * backtest ([devBacktest]) выполняются ТОЛЬКО на данных ДО [HoldoutValidation]
 * holdout-границы, а независимый holdout-окно (последние [holdoutFraction]
 * истории) трогается РОВНО один раз зафиксированными параметрами — после того
 * как WFA принял стратегию.
 *
 * @property walkForward результат WFA на данных до holdout-границы
 * @property holdout результат одноразового прогона на независимом holdout-окне
 * @property paramsUsed параметры, зафиксированные по [walkForward] и применённые
 *   к holdout (включая confidence/leverage/risk — см. [StrategyParameters])
 * @property frozenStrategy единый ЗАМОРОЖЕННЫЙ объект стратегии (из [paramsUsed] +
 *   strategy version + build identity): тот же объект, что идёт в MC, DeploymentGate,
 *   fingerprint и live-execution (P1-аудит — один источник правды)
 * @property devBacktest базовый backtest на данных ДО holdout-границы с
 *   зафиксированными параметрами — используется для edge-проверки без утечки
 */
data class HoldoutValidation(
    val walkForward: ValidationResult,
    val holdout: BacktestResult,
    val paramsUsed: StrategyParameters,
    val frozenStrategy: FrozenStrategy,
    val devBacktest: BacktestResult,
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
    private val buildIdentity: BuildIdentity,
    private val fingerprintProvider: LiveStrategyFingerprintProvider,
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
        adaptiveConfidenceThreshold: Double? = null,
        signalGeneratorOverride: BacktestSignalGenerator? = null,
    ): HoldoutValidation {
        require(holdoutFraction > 0.0 && holdoutFraction < 1.0) { "holdoutFraction must be in (0, 1)" }
        val sorted = candles.sortedBy { it.time }
        if (sorted.size < 2) {
            throw IllegalArgumentException("insufficient candles for holdout: ${sorted.size}")
        }
        val (wfaCandles, holdoutCandles) = splitDevHoldout(sorted, holdoutFraction)

        val effectiveOverride =
            signalGeneratorOverride
                ?: adaptiveConfidenceThreshold?.let {
                    LiveStrategyBacktestSignalGenerator(adaptiveConfidenceThreshold = it)
                }

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
                signalGeneratorOverride = effectiveOverride,
            )

        // Параметры фиксируются по последнему выбранному фолдом кандидату.
        val paramsUsed =
            walkForward.folds.lastOrNull()?.let { f ->
                StrategyParameters(
                    slPercent = f.chosenSlPercent,
                    tpPercent = f.chosenTpPercent,
                    slPoints = f.chosenSlPoints,
                    tpPoints = f.chosenTpPoints,
                    confidenceThreshold = adaptiveConfidenceThreshold,
                    leverage = leverage,
                    riskPerTradePercent = riskPerTradePercent,
                    futuresMaxContractsPerPosition = futuresMaxContractsPerPosition,
                )
            } ?: defaultParams(
                ticker,
                adaptiveConfidenceThreshold,
                leverage,
                riskPerTradePercent,
                futuresMaxContractsPerPosition,
            )

        // Единый ЗАМОРОЖЕННЫЙ объект стратегии: он же идёт в MC, DeploymentGate,
        // fingerprint и live-execution (P1-аудит — один источник правды).
        val frozenStrategy =
            FrozenStrategy.from(
                parameters = paramsUsed,
                ticker = ticker,
                strategyVersion = fingerprintProvider.strategyVersion,
                gitCommitSha = buildIdentity.gitCommitSha(),
            )

        // Базовый backtest на dev-данных (без holdout) с зафиксированными параметрами.
        val devBacktest =
            backtestEngine.simulate(
                ticker,
                wfaCandles,
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
                signalGeneratorOverride = effectiveOverride,
            )

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
                signalGeneratorOverride = effectiveOverride,
            )

        val result = HoldoutValidation(walkForward, holdout, paramsUsed, frozenStrategy, devBacktest)
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

    private fun defaultParams(
        ticker: String,
        confidenceThreshold: Double?,
        leverage: Double,
        riskPerTradePercent: Double?,
        futuresMaxContractsPerPosition: Int?,
    ): StrategyParameters =
        if (instrumentsConfig.isFutures(ticker)) {
            StrategyParameters(
                slPoints = 300,
                tpPoints = 600,
                confidenceThreshold = confidenceThreshold,
                leverage = leverage,
                riskPerTradePercent = riskPerTradePercent,
                futuresMaxContractsPerPosition = futuresMaxContractsPerPosition,
            )
        } else {
            StrategyParameters(
                slPercent = 0.02,
                tpPercent = 0.04,
                confidenceThreshold = confidenceThreshold,
                leverage = leverage,
                riskPerTradePercent = riskPerTradePercent,
                futuresMaxContractsPerPosition = futuresMaxContractsPerPosition,
            )
        }
}
