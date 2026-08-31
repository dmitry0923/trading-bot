package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import com.trading.bot.config.RiskConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * Запрос панельного бэктеста (roadmap v2.3): один вызов = несколько тикеров.
 *
 * @param tickers список тикеров для прогона (дубликаты схлопываются)
 * @param days глубина истории (по умолчанию `bt.days`)
 * @param timeframe таймфрейм свечей (по умолчанию `bt.timeframe`)
 * @param loadHistory загрузить историю с MOEX ISS перед прогоном
 * @param initialCapital стартовый капитал (по умолчанию `bt.initial-capital`)
 * @param slPercent стоп-лосс в долях от цены входа, напр. 0.02 = 2% (по умолчанию `bt.sl-percent` / 100)
 * @param tpPercent тейк-профит в долях от цены входа, напр. 0.04 = 4% (по умолчанию `bt.tp-percent` / 100)
 * @param minBarsForSignal минимальное число баров для сигнала (по умолчанию `bt.min-bars-for-signal`)
 */
data class PanelBacktestRequest(
    val tickers: List<String>,
    val days: Int? = null,
    val timeframe: String? = null,
    val loadHistory: Boolean = false,
    val initialCapital: BigDecimal? = null,
    val slPercent: Double? = null,
    val tpPercent: Double? = null,
    val minBarsForSignal: Int? = null,
    val leverage: Double? = null,
    val capitalSlice: Double? = null,
    val riskPerTradePercent: Double? = null,
    val adaptiveConfidenceThreshold: Double? = null,
    val slPoints: Int? = null,
    val tpPoints: Int? = null,
    val futuresMaxContractsPerPosition: Int? = null,
)

/** Компактный результат прогона одного тикера (без equityCurve — тяжёлых серий). */
data class PanelTickerSummary(
    val ticker: String,
    val totalReturn: Double,
    val sharpeRatio: Double,
    val sortinoRatio: Double,
    val maxDrawdown: Double,
    val winRate: Double,
    val profitFactor: Double,
    val totalTrades: Int,
    val passable: Boolean,
)

/**
 * Распределение результатов по тикерам: доля прошедших критерии, средняя/медианная
 * доходность, разброс. Позволяет увидеть, достигнут ли результат за счёт 1-2 тикеров.
 */
data class PanelBacktestSummary(
    val tickerCount: Int,
    val passCount: Int,
    val passShare: Double,
    val avgTotalReturn: Double,
    val medianTotalReturn: Double,
    val minTotalReturn: Double,
    val maxTotalReturn: Double,
    val totalTrades: Int,
)

/** Ответ панельного бэктеста: параметры прогона + результаты по тикерам + распределение. */
data class PanelBacktestResponse(
    val tickers: List<String>,
    val days: Int,
    val timeframe: String,
    val initialCapital: BigDecimal,
    val slPercent: Double,
    val tpPercent: Double,
    val minBarsForSignal: Int,
    val results: List<PanelTickerSummary>,
    val summary: PanelBacktestSummary,
)

/** Чистая агрегация результатов панели (без зависимостей — unit-тестируемая). */
object PanelBacktestSummarizer {
    fun summarize(results: List<PanelTickerSummary>): PanelBacktestSummary {
        if (results.isEmpty()) {
            return PanelBacktestSummary(
                tickerCount = 0,
                passCount = 0,
                passShare = 0.0,
                avgTotalReturn = 0.0,
                medianTotalReturn = 0.0,
                minTotalReturn = 0.0,
                maxTotalReturn = 0.0,
                totalTrades = 0,
            )
        }
        val returns = results.map { it.totalReturn }
        val sorted = returns.sorted()
        val median =
            if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            }
        return PanelBacktestSummary(
            tickerCount = results.size,
            passCount = results.count { it.passable },
            passShare = results.count { it.passable }.toDouble() / results.size,
            avgTotalReturn = returns.average(),
            medianTotalReturn = median,
            minTotalReturn = sorted.first(),
            maxTotalReturn = sorted.last(),
            totalTrades = results.sumOf { it.totalTrades },
        )
    }
}

/**
 * Панельный бэктест: прогон стратегии по нескольким тикерам за один вызов
 * (roadmap v2.3). Тикеры прогоняются параллельно; каждый прогон идёт через
 * [BacktestEngine.run] — с персистом в `backtest_results` и метрикой
 * `bt_pass_total` для сравнения итераций (13.7.3).
 */
@Service
class PanelBacktestService(
    private val backtestEngine: BacktestEngine,
    private val backtestConfig: BacktestConfig,
    private val historicalDataLoader: HistoricalDataLoader,
    private val riskConfig: RiskConfig,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun run(request: PanelBacktestRequest): PanelBacktestResponse =
        coroutineScope {
            val tickers = request.tickers.distinct()
            val days = request.days ?: backtestConfig.days
            val timeframe = normalizeTimeframe(request.timeframe ?: backtestConfig.timeframe)
            val initialCapital = request.initialCapital ?: backtestConfig.initialCapital
            val slPercent = request.slPercent ?: (backtestConfig.slPercent / 100.0)
            val tpPercent = request.tpPercent ?: (backtestConfig.tpPercent / 100.0)
            val minBarsForSignal = request.minBarsForSignal ?: backtestConfig.minBarsForSignal

            if (request.loadHistory) {
                historicalDataLoader.loadAndSaveAll(tickers, days)
            }

            logger.info { "Panel backtest: tickers=$tickers days=$days timeframe=$timeframe" }
            logger.debug {
                "Panel params: riskPct=${request.riskPerTradePercent} maxC=${request.futuresMaxContractsPerPosition} slPoints=${request.slPoints} tpPoints=${request.tpPoints} conf=${request.adaptiveConfidenceThreshold}"
            }
            val leverage = request.leverage ?: 1.0
            val capitalSlice = request.capitalSlice ?: backtestConfig.capitalSlice
            val riskPerTradePercent = request.riskPerTradePercent
            val confidenceThreshold = request.adaptiveConfidenceThreshold

            val results =
                tickers
                    .map { ticker ->
                        async {
                            val signalGen =
                                if (confidenceThreshold != null) {
                                    LiveStrategyBacktestSignalGenerator(
                                        regimeConfig =
                                            if (backtestConfig.regimeDetectionEnabled) {
                                                riskConfig.toRegimeDetectionConfig()
                                            } else {
                                                null
                                            },
                                        adaptiveConfidenceThreshold = confidenceThreshold,
                                    )
                                } else {
                                    null
                                }
                            val result =
                                backtestEngine.run(
                                    ticker,
                                    days = days,
                                    timeframe = timeframe,
                                    initialCapital = initialCapital,
                                    minBarsForSignal = minBarsForSignal,
                                    slPercent = slPercent,
                                    tpPercent = tpPercent,
                                    leverage = leverage,
                                    capitalSlice = capitalSlice,
                                    riskPerTradePercent = riskPerTradePercent,
                                    slPoints = request.slPoints,
                                    tpPoints = request.tpPoints,
                                    futuresMaxContractsPerPosition = request.futuresMaxContractsPerPosition,
                                    signalGeneratorOverride = signalGen,
                                )
                            PanelTickerSummary(
                                ticker = ticker,
                                totalReturn = result.totalReturn,
                                sharpeRatio = result.sharpeRatio,
                                sortinoRatio = result.sortinoRatio,
                                maxDrawdown = result.maxDrawdown,
                                winRate = result.winRate,
                                profitFactor = result.profitFactor,
                                totalTrades = result.totalTrades,
                                passable = result.isPassable(),
                            )
                        }
                    }.awaitAll()

            PanelBacktestResponse(
                tickers = tickers,
                days = days,
                timeframe = timeframe,
                initialCapital = initialCapital,
                slPercent = slPercent,
                tpPercent = tpPercent,
                minBarsForSignal = minBarsForSignal,
                results = results,
                summary = PanelBacktestSummarizer.summarize(results),
            )
        }

    companion object {
        private val TIMEFRAME_ALIASES =
            mapOf(
                "1" to "MINUTE_1",
                "5" to "MINUTE_5",
                "10" to "MINUTE_10",
                "15" to "MINUTE_15",
                "30" to "MINUTE_30",
                "60" to "HOUR_1",
                "1h" to "HOUR_1",
                "1d" to "DAY_1",
            )

        /**
         * Нормализация timeframe: пользовательские алиасы ("10", "60", "1h")
         * маппятся на канонические значения enums ("MINUTE_10", "HOUR_1"),
         * совпадающие с тем, что записывает [HistoricalDataLoader] в БД.
         * Уже каноничные значения проходят без изменений.
         */
        fun normalizeTimeframe(tf: String): String = TIMEFRAME_ALIASES[tf.lowercase()] ?: tf
    }
}
