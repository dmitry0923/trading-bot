package com.trading.bot.backtest.portfolio

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
 * Сервис research-прогона cross-sectional momentum + carry портфеля (`docs/26`):
 * загрузка свечей всех инструментов юниверса, inner-join по времени, загрузка funding,
 * запуск [PortfolioMomentumEngine], расчёт метрик на полной истории и на IS/OOS-отрезках,
 * а также walk-forward ([validate]).
 *
 * Вердикт одиночного прогона — ТОЛЬКО по OOS (пороги [BacktestResult.isPassable]);
 * walk-forward дополнительно проверяет consistency (защита от везения на одном сегменте).
 * `holdoutFraction` в [validate] отрезает от истории последние `holdoutFraction` ДО
 * walk-forward — протокол IS70/OOS30 (holdout не участвует ни в каком подборе, вердикт
 * по нему даёт `/momentum?oosFraction=`). Live-путь не затрагивается.
 */
@Service
class PortfolioMomentumService(
    private val candleRepository: CandleRepository,
    private val instrumentsConfig: InstrumentsConfig,
    private val fundingHistoryRepository: FundingHistoryRepository,
    private val config: PortfolioMomentumConfig,
    private val moexHolidayCalendar: MoexHolidayCalendar? = null,
) {
    private data class Resolved(
        val universe: List<String>,
        val tf: String,
        val lookback: Int,
        val skip: Int,
        val reversalEnabled: Boolean,
        val rebalance: Int,
        val longCount: Int,
        val shortCount: Int,
        val capitalSlice: Double,
        val momentumWeight: Double,
        val carryWeight: Double,
        val carryLookbackDays: Int,
        val minUniverse: Int,
        val initialCapital: Double,
        val volScalingEnabled: Boolean,
        val volLookbackBars: Int,
        val volTargetEnabled: Boolean,
        val volTargetAnnualized: Double,
        val volTargetBarsPerYear: Double,
    )

    private data class Inputs(
        val bars: List<PortfolioMomentumEngine.Bar>,
        val specs: List<PortfolioMomentumEngine.LegSpec>,
        val funding: List<Map<LocalDate, Double>>,
        val calendar: MoexHolidayCalendar,
    )

    @Suppress("LongParameterList")
    suspend fun run(
        universe: List<String>? = null,
        timeframe: String? = null,
        days: Int = 730,
        lookbackBars: Int? = null,
        skipBars: Int? = null,
        rebalanceBars: Int? = null,
        longCount: Int? = null,
        shortCount: Int? = null,
        oosFraction: Double? = null,
        capitalSlice: Double? = null,
        momentumWeight: Double? = null,
        carryWeight: Double? = null,
        carryLookbackDays: Int? = null,
        minUniverse: Int? = null,
        initialCapital: Double? = null,
        reversalEnabled: Boolean? = null,
        volScalingEnabled: Boolean? = null,
        volLookbackBars: Int? = null,
        volTargetEnabled: Boolean? = null,
        volTargetAnnualized: Double? = null,
        volTargetBarsPerYear: Double? = null,
    ): MomentumBacktestResponse {
        val r =
            resolve(
                universe = universe,
                timeframe = timeframe,
                lookbackBars = lookbackBars,
                skipBars = skipBars,
                rebalanceBars = rebalanceBars,
                longCount = longCount,
                shortCount = shortCount,
                capitalSlice = capitalSlice,
                momentumWeight = momentumWeight,
                carryWeight = carryWeight,
                carryLookbackDays = carryLookbackDays,
                minUniverse = minUniverse,
                initialCapital = initialCapital,
                reversalEnabled = reversalEnabled,
                volScalingEnabled = volScalingEnabled,
                volLookbackBars = volLookbackBars,
                volTargetEnabled = volTargetEnabled,
                volTargetAnnualized = volTargetAnnualized,
                volTargetBarsPerYear = volTargetBarsPerYear,
            )
        val inputs = loadInputs(r, days)
        val effectiveOos = oosFraction ?: config.oosFraction

        val result =
            PortfolioMomentumEngine.run(
                bars = inputs.bars,
                specs = inputs.specs,
                params = engineParams(r),
                funding = inputs.funding,
                isTradingDay = inputs.calendar::isTradingDay,
            )

        val label = r.universe.joinToString("+")
        val n = result.equityCurve.size
        val full = metrics(label, result.equityCurve, result.equityTimestamps, result.trades, 0, n)
        val split = if (n < 2) n else ((1.0 - effectiveOos.coerceIn(0.0, 0.9)) * n).toInt().coerceIn(1, n - 1)
        val boundary = result.equityTimestamps.getOrNull(split)
        val inSampleTrades = result.trades.filter { boundary == null || it.exitTime.isBefore(boundary) }
        val oosTrades = result.trades.filter { boundary != null && !it.exitTime.isBefore(boundary) }
        val inSample = metrics(label, result.equityCurve, result.equityTimestamps, inSampleTrades, 0, split)
        val outOfSample =
            metrics(label, result.equityCurve, result.equityTimestamps, oosTrades, (split - 1).coerceAtLeast(0), n)

        return MomentumBacktestResponse(
            universe = r.universe,
            timeframe = r.tf,
            bars = result.bars,
            rebalances = result.rebalances,
            initialCapital = r.initialCapital,
            verdict = verdict(outOfSample),
            full = full.metrics(),
            inSample = inSample.metrics(),
            outOfSample = outOfSample.metrics(),
            trades = result.trades.map { it.toMap() },
        )
    }

    /** Walk-forward валидация портфеля: фолды + агрегат OOS + consistency (см. [PortfolioWalkForward]). */
    @Suppress("LongParameterList")
    suspend fun validate(
        universe: List<String>? = null,
        timeframe: String? = null,
        days: Int = 730,
        lookbackBars: Int? = null,
        skipBars: Int? = null,
        rebalanceBars: Int? = null,
        longCount: Int? = null,
        shortCount: Int? = null,
        capitalSlice: Double? = null,
        momentumWeight: Double? = null,
        carryWeight: Double? = null,
        carryLookbackDays: Int? = null,
        minUniverse: Int? = null,
        initialCapital: Double? = null,
        reversalEnabled: Boolean? = null,
        volScalingEnabled: Boolean? = null,
        volLookbackBars: Int? = null,
        volTargetEnabled: Boolean? = null,
        volTargetAnnualized: Double? = null,
        volTargetBarsPerYear: Double? = null,
        folds: Int = 8,
        holdoutFraction: Double? = null,
    ): MomentumWfaResponse {
        val r =
            resolve(
                universe = universe,
                timeframe = timeframe,
                lookbackBars = lookbackBars,
                skipBars = skipBars,
                rebalanceBars = rebalanceBars,
                longCount = longCount,
                shortCount = shortCount,
                capitalSlice = capitalSlice,
                momentumWeight = momentumWeight,
                carryWeight = carryWeight,
                carryLookbackDays = carryLookbackDays,
                minUniverse = minUniverse,
                initialCapital = initialCapital,
                reversalEnabled = reversalEnabled,
                volScalingEnabled = volScalingEnabled,
                volLookbackBars = volLookbackBars,
                volTargetEnabled = volTargetEnabled,
                volTargetAnnualized = volTargetAnnualized,
                volTargetBarsPerYear = volTargetBarsPerYear,
            )
        val inputs = loadInputs(r, days)
        val devBars = devSlice(inputs.bars, holdoutFraction)
        val wfa =
            PortfolioWalkForward.run(
                bars = devBars,
                specs = inputs.specs,
                params = engineParams(r),
                folds = folds,
                funding = inputs.funding,
                isTradingDay = inputs.calendar::isTradingDay,
            )
        return MomentumWfaResponse(
            universe = r.universe,
            timeframe = r.tf,
            bars = devBars.size,
            folds = wfa.folds.size,
            consistency = wfa.consistency,
            robust = wfa.robust,
            verdict = wfa.verdict,
            aggregate = wfa.aggregate.metrics(),
            foldResults =
                wfa.folds.map {
                    MomentumFoldResult(it.foldIndex, it.from.toString(), it.to.toString(), it.result.metrics())
                },
            holdoutFraction = holdoutFraction,
        )
    }

    @Suppress("LongParameterList")
    private fun resolve(
        universe: List<String>?,
        timeframe: String?,
        lookbackBars: Int?,
        skipBars: Int?,
        reversalEnabled: Boolean?,
        rebalanceBars: Int?,
        longCount: Int?,
        shortCount: Int?,
        capitalSlice: Double?,
        momentumWeight: Double?,
        carryWeight: Double?,
        carryLookbackDays: Int?,
        minUniverse: Int?,
        initialCapital: Double?,
        volScalingEnabled: Boolean?,
        volLookbackBars: Int?,
        volTargetEnabled: Boolean?,
        volTargetAnnualized: Double?,
        volTargetBarsPerYear: Double?,
    ): Resolved =
        Resolved(
            universe = (universe ?: config.universe).map { it.trim().uppercase() }.filter { it.isNotEmpty() },
            tf = timeframe ?: config.timeframe,
            lookback = lookbackBars ?: config.lookbackBars,
            skip = skipBars ?: config.skipBars,
            reversalEnabled = reversalEnabled ?: config.reversalEnabled,
            rebalance = rebalanceBars ?: config.rebalanceBars,
            longCount = longCount ?: config.longCount,
            shortCount = shortCount ?: config.shortCount,
            capitalSlice = capitalSlice ?: config.capitalSlice,
            momentumWeight = momentumWeight ?: config.momentumWeight,
            carryWeight = carryWeight ?: config.carryWeight,
            carryLookbackDays = carryLookbackDays ?: config.carryLookbackDays,
            minUniverse = minUniverse ?: config.minUniverse,
            initialCapital = initialCapital ?: config.initialCapital,
            volScalingEnabled = volScalingEnabled ?: config.volScalingEnabled,
            volLookbackBars = volLookbackBars ?: config.volLookbackBars,
            volTargetEnabled = volTargetEnabled ?: config.volTargetEnabled,
            volTargetAnnualized = volTargetAnnualized ?: config.volTargetAnnualized,
            volTargetBarsPerYear = volTargetBarsPerYear ?: config.volTargetBarsPerYear,
        )

    private fun engineParams(r: Resolved): PortfolioMomentumEngine.Params =
        PortfolioMomentumEngine.Params(
            initialCapital = r.initialCapital,
            lookbackBars = r.lookback,
            skipBars = r.skip,
            reversalEnabled = r.reversalEnabled,
            rebalanceBars = r.rebalance,
            longCount = r.longCount,
            shortCount = r.shortCount,
            capitalSlice = r.capitalSlice,
            momentumWeight = r.momentumWeight,
            carryWeight = r.carryWeight,
            carryLookbackDays = r.carryLookbackDays,
            minUniverse = r.minUniverse,
            volScalingEnabled = r.volScalingEnabled,
            volLookbackBars = r.volLookbackBars,
            volTargetEnabled = r.volTargetEnabled,
            volTargetAnnualized = r.volTargetAnnualized,
            volTargetBarsPerYear = r.volTargetBarsPerYear,
        )

    private suspend fun loadInputs(
        r: Resolved,
        days: Int,
    ): Inputs {
        require(r.universe.isNotEmpty()) { "Portfolio universe is empty" }
        val specsList = r.universe.map { requireSpec(it) }
        val to = LocalDateTime.now()
        val from = to.minusDays(days.toLong())
        val candles = r.universe.map { candleRepository.findByTickerAndTimeframeAndTimeBetween(it, r.tf, from, to) }
        val byTime = candles.map { list -> list.associateBy { it.time } }
        val base = candles.first().sortedBy { it.time }
        val u = r.universe.size
        val bars = ArrayList<PortfolioMomentumEngine.Bar>(base.size)
        for (c0 in base) {
            val opens = DoubleArray(u)
            val closes = DoubleArray(u)
            var complete = true
            for (j in 0 until u) {
                val c = byTime[j][c0.time]
                if (c == null) {
                    complete = false
                    break
                }
                opens[j] = c.openPrice.toDouble()
                closes[j] = c.closePrice.toDouble()
            }
            if (complete) bars.add(PortfolioMomentumEngine.Bar(c0.time, opens, closes))
        }
        return Inputs(
            bars = bars,
            specs = r.universe.indices.map { toLegSpec(specsList[it], r.universe[it]) },
            funding = r.universe.indices.map { loadFunding(r.universe[it], from, to) },
            calendar = moexHolidayCalendar ?: MoexHolidayCalendar(),
        )
    }

    private fun requireSpec(ticker: String): InstrumentsConfig.InstrumentSpec =
        instrumentsConfig.find(ticker)
            ?: throw IllegalArgumentException("Unknown instrument for portfolio backtest: $ticker")

    private fun toLegSpec(
        spec: InstrumentsConfig.InstrumentSpec,
        ticker: String,
    ): PortfolioMomentumEngine.LegSpec =
        PortfolioMomentumEngine.LegSpec(
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
        trades: List<PortfolioMomentumEngine.Trade>,
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

/** Ответ research-эндпоинта `/api/v1/portfolio/momentum` (без equityCurve — только метрики). */
data class MomentumBacktestResponse(
    val universe: List<String>,
    val timeframe: String,
    val bars: Int,
    val rebalances: Int,
    val initialCapital: Double,
    val verdict: String,
    val full: Map<String, Any>,
    val inSample: Map<String, Any>,
    val outOfSample: Map<String, Any>,
    val trades: List<Map<String, Any?>>,
)

/** Ответ research-эндпоинта `/api/v1/portfolio/momentum/validate` (walk-forward). */
data class MomentumWfaResponse(
    val universe: List<String>,
    val timeframe: String,
    val bars: Int,
    val folds: Int,
    val consistency: Double,
    val robust: Boolean,
    val verdict: String,
    val aggregate: Map<String, Any>,
    val foldResults: List<MomentumFoldResult>,
    /** Протокол IS70/OOS30: доля holdout; null — WFA по всей истории (без отрезки). */
    val holdoutFraction: Double? = null,
)

/**
 * Отрезка dev-части для holdout-протокола IS70/OOS30 (`scripts/research_oos70_portfolio.ps1`):
 * первые `(1 − holdoutFraction)` элементов — обучающее окно, последние `holdoutFraction`
 * в walk-forward не участвуют (так, как это делает `StrategyParameters` для тикерного
 * `/holdout`). `null` → список целиком (протокол не применяется, прежнее поведение).
 * Порядок элементов обязан быть хронологическим — срез выполняется по индексу.
 */
internal fun <T> devSlice(
    items: List<T>,
    holdoutFraction: Double?,
): List<T> {
    if (holdoutFraction == null) return items
    require(holdoutFraction > 0.0 && holdoutFraction < 1.0) { "holdoutFraction must be in (0, 1)" }
    val devSize = ((1.0 - holdoutFraction) * items.size).toInt().coerceAtLeast(1)
    return items.take(devSize)
}

/** Метрики одного OOS-фолда walk-forward. */
data class MomentumFoldResult(
    val foldIndex: Int,
    val from: String,
    val to: String,
    val metrics: Map<String, Any>,
)

private fun PortfolioMomentumEngine.Trade.toMap(): Map<String, Any?> =
    mapOf(
        "ticker" to ticker,
        "side" to side,
        "entryTime" to entryTime.toString(),
        "exitTime" to exitTime.toString(),
        "qty" to qty,
        "entryPrice" to entryPrice,
        "exitPrice" to exitPrice,
        "holdBars" to holdBars,
        "grossPnl" to grossPnl,
        "commission" to commission,
        "slippage" to slippage,
        "funding" to funding,
        "netPnl" to netPnl,
        "closeReason" to closeReason,
    )
