package com.trading.bot.backtest.pairs

import com.trading.bot.application.MoexHolidayCalendar
import com.trading.bot.backtest.BacktestMetrics
import com.trading.bot.backtest.BacktestResult
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.FundingHistoryRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Сервис research-прогона market-neutral пары: загрузка свечей и funding обеих ног,
 * inner-join по времени, запуск [PairsBacktestEngine], расчёт метрик на полной
 * истории и на IS/OOS-отрезках.
 *
 * Вердикт одиночного прогона выносится ТОЛЬКО по OOS (same thresholds, что
 * [BacktestResult.isPassable] / `docs/25` §6). Walk-forward ([validate]) дополнительно
 * режет историю на фолды и проверяет consistency — защита от везения на одном сегменте.
 * Live-путь не затрагивается.
 */
@Service
class PairsBacktestService(
    private val candleRepository: CandleRepository,
    private val instrumentsConfig: InstrumentsConfig,
    private val fundingHistoryRepository: FundingHistoryRepository,
    private val config: PairsBacktestConfig,
    private val moexHolidayCalendar: MoexHolidayCalendar? = null,
) {
    private data class Resolved(
        val aTicker: String,
        val bTicker: String,
        val tf: String,
        val lookback: Int,
        val entryZ: Double,
        val exitZ: Double,
        val stopZ: Double,
        val maxHold: Int,
        val capitalSlice: Double,
        val initialCapital: Double,
    )

    private data class Inputs(
        val bars: List<PairsBacktestEngine.Bar>,
        val legSpecA: PairsBacktestEngine.LegSpec,
        val legSpecB: PairsBacktestEngine.LegSpec,
        val fundingA: Map<LocalDate, Double>,
        val fundingB: Map<LocalDate, Double>,
        val calendar: MoexHolidayCalendar,
    )

    @Suppress("LongParameterList")
    suspend fun run(
        legA: String? = null,
        legB: String? = null,
        timeframe: String? = null,
        days: Int = 730,
        lookbackBars: Int? = null,
        entryZ: Double? = null,
        exitZ: Double? = null,
        stopZ: Double? = null,
        maxHoldBars: Int? = null,
        oosFraction: Double? = null,
        capitalSlice: Double? = null,
        initialCapital: Double? = null,
    ): PairsBacktestResponse {
        val r =
            resolve(legA, legB, timeframe, lookbackBars, entryZ, exitZ, stopZ, maxHoldBars, capitalSlice, initialCapital)
        val inputs = loadInputs(r, days)
        val effectiveOos = oosFraction ?: config.oosFraction

        val result =
            PairsBacktestEngine.run(
                bars = inputs.bars,
                specA = inputs.legSpecA,
                specB = inputs.legSpecB,
                params = engineParams(r),
                fundingA = inputs.fundingA,
                fundingB = inputs.fundingB,
                isTradingDay = inputs.calendar::isTradingDay,
            )

        val label = "${r.aTicker}/${r.bTicker}"
        val full = metrics(label, result.equityCurve, result.equityTimestamps, result.trades, 0, result.equityCurve.size)
        val n = result.equityCurve.size
        val split = if (n < 2) n else ((1.0 - effectiveOos.coerceIn(0.0, 0.9)) * n).toInt().coerceIn(1, n - 1)
        val boundary = result.equityTimestamps.getOrNull(split)
        val inSampleTrades = result.trades.filter { boundary == null || it.exitTime.isBefore(boundary) }
        val oosTrades = result.trades.filter { boundary != null && !it.exitTime.isBefore(boundary) }
        val inSample = metrics(label, result.equityCurve, result.equityTimestamps, inSampleTrades, 0, split)
        val outOfSample =
            metrics(label, result.equityCurve, result.equityTimestamps, oosTrades, (split - 1).coerceAtLeast(0), n)

        return PairsBacktestResponse(
            legA = r.aTicker,
            legB = r.bTicker,
            timeframe = r.tf,
            bars = result.bars,
            spreadReturnCorrelation = result.spreadReturnCorrelation,
            meanBeta = result.meanBeta,
            initialCapital = r.initialCapital,
            full = full.metrics(),
            inSample = inSample.metrics(),
            outOfSample = outOfSample.metrics(),
            verdict = verdict(outOfSample),
            trades = result.trades.map { it.toMap() },
        )
    }

    /** Walk-forward валидация пары: фолды + агрегат OOS + consistency (см. [PairsWalkForward]). */
    @Suppress("LongParameterList")
    suspend fun validate(
        legA: String? = null,
        legB: String? = null,
        timeframe: String? = null,
        days: Int = 730,
        lookbackBars: Int? = null,
        entryZ: Double? = null,
        exitZ: Double? = null,
        stopZ: Double? = null,
        maxHoldBars: Int? = null,
        capitalSlice: Double? = null,
        initialCapital: Double? = null,
        folds: Int = 6,
    ): PairsWfaResponse {
        val r =
            resolve(legA, legB, timeframe, lookbackBars, entryZ, exitZ, stopZ, maxHoldBars, capitalSlice, initialCapital)
        val inputs = loadInputs(r, days)
        val wfa =
            PairsWalkForward.run(
                bars = inputs.bars,
                specA = inputs.legSpecA,
                specB = inputs.legSpecB,
                params = engineParams(r),
                folds = folds,
                fundingA = inputs.fundingA,
                fundingB = inputs.fundingB,
                isTradingDay = inputs.calendar::isTradingDay,
            )
        return PairsWfaResponse(
            legA = r.aTicker,
            legB = r.bTicker,
            timeframe = r.tf,
            bars = inputs.bars.size,
            folds = wfa.folds.size,
            consistency = wfa.consistency,
            robust = wfa.robust,
            verdict = wfa.verdict,
            aggregate = wfa.aggregate.metrics(),
            foldResults =
                wfa.folds.map {
                    PairsFoldResult(it.foldIndex, it.from.toString(), it.to.toString(), it.result.metrics())
                },
        )
    }

    @Suppress("LongParameterList")
    private fun resolve(
        legA: String?,
        legB: String?,
        timeframe: String?,
        lookbackBars: Int?,
        entryZ: Double?,
        exitZ: Double?,
        stopZ: Double?,
        maxHoldBars: Int?,
        capitalSlice: Double?,
        initialCapital: Double?,
    ): Resolved =
        Resolved(
            aTicker = legA ?: config.legA,
            bTicker = legB ?: config.legB,
            tf = timeframe ?: config.timeframe,
            lookback = lookbackBars ?: config.lookbackBars,
            entryZ = entryZ ?: config.entryZ,
            exitZ = exitZ ?: config.exitZ,
            stopZ = stopZ ?: config.stopZ,
            maxHold = maxHoldBars ?: config.maxHoldBars,
            capitalSlice = capitalSlice ?: config.capitalSlice,
            initialCapital = initialCapital ?: config.initialCapital,
        )

    private fun engineParams(r: Resolved): PairsBacktestEngine.Params =
        PairsBacktestEngine.Params(
            initialCapital = r.initialCapital,
            lookbackBars = r.lookback,
            entryZ = r.entryZ,
            exitZ = r.exitZ,
            stopZ = r.stopZ,
            maxHoldBars = r.maxHold,
            capitalSlice = r.capitalSlice,
            betaMin = config.betaMin,
        )

    private suspend fun loadInputs(
        r: Resolved,
        days: Int,
    ): Inputs {
        val specA = requireSpec(r.aTicker)
        val specB = requireSpec(r.bTicker)
        val to = LocalDateTime.now()
        val from = to.minusDays(days.toLong())
        val candlesA = candleRepository.findByTickerAndTimeframeAndTimeBetween(r.aTicker, r.tf, from, to)
        val candlesB = candleRepository.findByTickerAndTimeframeAndTimeBetween(r.bTicker, r.tf, from, to)
        val byTimeB = candlesB.associateBy { it.time }
        val matched =
            candlesA.mapNotNull { a ->
                byTimeB[a.time]?.let { b ->
                    PairsBacktestEngine.Bar(
                        time = a.time,
                        openA = a.openPrice.toDouble(),
                        closeA = a.closePrice.toDouble(),
                        openB = b.openPrice.toDouble(),
                        closeB = b.closePrice.toDouble(),
                    )
                }
            }
        return Inputs(
            bars = matched.sortedBy { it.time },
            legSpecA = toLegSpec(specA, r.aTicker),
            legSpecB = toLegSpec(specB, r.bTicker),
            fundingA = loadFunding(r.aTicker, from, to),
            fundingB = loadFunding(r.bTicker, from, to),
            calendar = moexHolidayCalendar ?: MoexHolidayCalendar(),
        )
    }

    private fun requireSpec(ticker: String): InstrumentsConfig.InstrumentSpec =
        instrumentsConfig.find(ticker)
            ?: throw IllegalArgumentException("Unknown instrument for pairs backtest: $ticker")

    private fun toLegSpec(
        spec: InstrumentsConfig.InstrumentSpec,
        ticker: String,
    ): PairsBacktestEngine.LegSpec =
        PairsBacktestEngine.LegSpec(
            ticker = ticker,
            lotSize = spec.lotSize,
            commissionPerContractSide = spec.totalCommissionPerLotSide().toDouble(),
            slippageBps = (spec.slippageBps ?: BigDecimal.ONE).toDouble(),
            fundingPerContractClearing = spec.fundingPerClearing().toDouble(),
        )

    private suspend fun loadFunding(
        ticker: String,
        from: LocalDateTime,
        to: LocalDateTime,
    ): Map<LocalDate, Double> =
        if (!config.useFundingHistory) {
            emptyMap()
        } else {
            fundingHistoryRepository
                .findValuesBetween(ticker, from.toLocalDate(), to.toLocalDate())
                .mapValues { it.value.toDouble() }
        }

    @Suppress("LongParameterList")
    private fun metrics(
        label: String,
        equity: List<Double>,
        timestamps: List<LocalDateTime>,
        trades: List<PairsBacktestEngine.PairTrade>,
        eqFrom: Int,
        eqTo: Int,
    ): BacktestResult {
        val curve = equity.subList(eqFrom, eqTo).map { BigDecimal.valueOf(it) }
        val times = timestamps.subList(eqFrom, eqTo)
        val returns = trades.map { it.netPnl }
        val holds = trades.map { it.holdBars }
        val commission = trades.sumOf { it.commission }
        return BacktestMetrics.compute(
            ticker = label,
            equityCurve = curve,
            equityTimestamps = times,
            tradeReturns = returns,
            holdBars = holds,
            totalCommission = BigDecimal.valueOf(commission),
            isFutures = true,
        )
    }

    private fun verdict(oos: BacktestResult): String =
        when {
            oos.totalTrades < 30 -> "INCONCLUSIVE (oos trades ${oos.totalTrades} < 30)"
            oos.profitFactor < 1.3 -> "REJECTED (oos PF ${"%.3f".format(oos.profitFactor)})"
            oos.probabilityOfNoEdge > 0.05 -> "INCONCLUSIVE (P(noEdge) ${"%.3f".format(oos.probabilityOfNoEdge)})"
            else -> "CANDIDATE (oos PF ${"%.3f".format(oos.profitFactor)})"
        }
}

/** Ответ research-эндпоинта `/api/v1/pairs/backtest` (без equityCurve — только метрики). */
data class PairsBacktestResponse(
    val legA: String,
    val legB: String,
    val timeframe: String,
    val bars: Int,
    val spreadReturnCorrelation: Double,
    val meanBeta: Double,
    val initialCapital: Double,
    val verdict: String,
    val full: Map<String, Any>,
    val inSample: Map<String, Any>,
    val outOfSample: Map<String, Any>,
    val trades: List<Map<String, Any?>>,
)

/** Ответ research-эндпоинта `/api/v1/pairs/validate` (walk-forward). */
data class PairsWfaResponse(
    val legA: String,
    val legB: String,
    val timeframe: String,
    val bars: Int,
    val folds: Int,
    val consistency: Double,
    val robust: Boolean,
    val verdict: String,
    val aggregate: Map<String, Any>,
    val foldResults: List<PairsFoldResult>,
)

/** Метрики одного OOS-фолда walk-forward. */
data class PairsFoldResult(
    val foldIndex: Int,
    val from: String,
    val to: String,
    val metrics: Map<String, Any>,
)

private fun PairsBacktestEngine.PairTrade.toMap(): Map<String, Any?> =
    mapOf(
        "side" to side,
        "entryTime" to entryTime.toString(),
        "exitTime" to exitTime.toString(),
        "qtyA" to qtyA,
        "qtyB" to qtyB,
        "entryPriceA" to entryPriceA,
        "entryPriceB" to entryPriceB,
        "exitPriceA" to exitPriceA,
        "exitPriceB" to exitPriceB,
        "beta" to beta,
        "zEntry" to zEntry,
        "zExit" to zExit,
        "holdBars" to holdBars,
        "grossPnl" to grossPnl,
        "commission" to commission,
        "slippage" to slippage,
        "funding" to funding,
        "netPnl" to netPnl,
        "closeReason" to closeReason,
    )
