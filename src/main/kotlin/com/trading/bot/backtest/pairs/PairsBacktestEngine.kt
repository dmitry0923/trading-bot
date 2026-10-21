package com.trading.bot.backtest.pairs

import com.trading.bot.application.FundingCosts
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Чистый research-движок market-neutral пары (FX relative-value).
 *
 * Торгуется не направление, а расхождение (`spread`) двух коррлированных ног:
 * `spread_i = log(A_i) − beta_i·log(B_i)`, где `beta_i` — rolling OLS по окну
 * [Params.lookbackBars]. Сигнал считается на закрытии бара `i`, исполняется на
 * открытии `i+1` (lookahead закрыт). Позиция market-neutral к рублю: long spread
 * = long A / short B, short spread = short A / long B.
 *
 * Движок не зависит от Spring/БД (на вход — уже выровненные бары) и
 * переиспользует [FundingCosts] для моментов начисления funding. Все издержки
 * (комиссия, слиппедж, funding) вычитаются из P&L сделки.
 *
 * См. `docs/25-fx-pairs-relative-value.md`.
 */
object PairsBacktestEngine {
    /** Спецификация ноги для расчёта P&L (из [com.trading.bot.config.InstrumentsConfig]). */
    data class LegSpec(
        val ticker: String,
        val lotSize: Int,
        /** Комиссия за контракт за сторону, руб. */
        val commissionPerContractSide: Double,
        /** Проскальзывание, bps на сторону. */
        val slippageBps: Double,
        /** Fallback-funding, руб/контракт/клиринг (если нет истории). */
        val fundingPerContractClearing: Double,
    )

    /** Параметры прогона движка. */
    data class Params(
        val initialCapital: Double = 100_000.0,
        val lookbackBars: Int = 240,
        val entryZ: Double = 2.0,
        val exitZ: Double = 0.5,
        val stopZ: Double = 4.0,
        val maxHoldBars: Int = 0,
        val capitalSlice: Double = 0.50,
        val betaMin: Double = 0.10,
    )

    /** Выровненный по времени бар обеих ног. */
    data class Bar(
        val time: LocalDateTime,
        val openA: Double,
        val closeA: Double,
        val openB: Double,
        val closeB: Double,
    )

    /** Одна закрытая сделка пары (трейд-лог). */
    data class PairTrade(
        val side: String,
        val entryTime: LocalDateTime,
        val exitTime: LocalDateTime,
        val qtyA: Int,
        val qtyB: Int,
        val entryPriceA: Double,
        val entryPriceB: Double,
        val exitPriceA: Double,
        val exitPriceB: Double,
        val beta: Double,
        val zEntry: Double,
        val zExit: Double,
        val holdBars: Int,
        /** P&L по slipped-ценам (без комиссии/funding), руб. */
        val grossPnl: Double,
        val commission: Double,
        /** Издержка проскальзывания (raw P&L − slipped P&L), руб. */
        val slippage: Double,
        /** Стоимость funding (положительная = списано с позиции), руб. */
        val funding: Double,
        val netPnl: Double,
        val closeReason: String,
    )

    /** Результат прогона пары. */
    data class Result(
        val legA: String,
        val legB: String,
        val bars: Int,
        val trades: List<PairTrade>,
        val equityCurve: List<Double>,
        val equityTimestamps: List<LocalDateTime>,
        val spreadReturnCorrelation: Double,
        val meanBeta: Double,
    )

    private class OpenPosition(
        val dirA: Int,
        val dirB: Int,
        val qtyA: Int,
        val qtyB: Int,
        val entryA: Double,
        val entryB: Double,
        val rawEntryA: Double,
        val rawEntryB: Double,
        val beta: Double,
        val entryIndex: Int,
        val entryTime: LocalDateTime,
        val zEntry: Double,
    )

    /**
     * Прогоняет пару.
     *
     * @param bars выровненные по времени бары (обе ноги)
     * @param specA спецификация ноги A
     * @param specB спецификация ноги B
     * @param params параметры прогона
     * @param fundingA funding ноги A: дата клиринга → руб/контракт (пусто = spec fallback)
     * @param fundingB funding ноги B: дата клиринга → руб/контракт (пусто = spec fallback)
     * @param isTradingDay предикат торгового дня для клирингов funding
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun run(
        bars: List<Bar>,
        specA: LegSpec,
        specB: LegSpec,
        params: Params,
        fundingA: Map<LocalDate, Double> = emptyMap(),
        fundingB: Map<LocalDate, Double> = emptyMap(),
        isTradingDay: (LocalDate) -> Boolean = { true },
    ): Result {
        val n = bars.size
        if (n < params.lookbackBars + 2) {
            return Result(specA.ticker, specB.ticker, n, emptyList(), emptyList(), emptyList(), 0.0, 0.0)
        }

        val la = DoubleArray(n) { ln(bars[it].closeA) }
        val lb = DoubleArray(n) { ln(bars[it].closeB) }
        val z = DoubleArray(n) { Double.NaN }
        val betaArr = DoubleArray(n) { Double.NaN }
        computeRollingStats(la, lb, params.lookbackBars, z, betaArr)

        val trades = ArrayList<PairTrade>()
        val equity = ArrayList<Double>(n)
        val equityTimes = ArrayList<LocalDateTime>(n)
        val targetNotional = params.capitalSlice * params.initialCapital
        var cash = params.initialCapital
        var open: OpenPosition? = null
        var pendingEnterSign = 0
        var pendingExitReason: String? = null

        for (i in 0 until n) {
            val bar = bars[i]
            if (pendingExitReason != null && open != null) {
                val trade = closePosition(open, bar, i, z[i], pendingExitReason!!, specA, specB, fundingA, fundingB, isTradingDay)
                trades.add(trade)
                cash += trade.netPnl
                open = null
                pendingExitReason = null
            } else if (pendingEnterSign != 0 && open == null) {
                val dirA = if (pendingEnterSign > 0) 1 else -1
                open = openPosition(dirA, bar, i, betaArr[i], z[i], targetNotional, specA, specB, params.betaMin)
                pendingEnterSign = 0
            }

            val unrealized = open?.let { unrealized(it, bar.closeA, bar.closeB, specA, specB) } ?: 0.0
            equity.add(cash + unrealized)
            equityTimes.add(bar.time)

            if (i + 1 < n) {
                val zi = z[i]
                if (!zi.isNaN()) {
                    val current = open
                    if (current == null) {
                        if (zi >= params.entryZ) {
                            pendingEnterSign = -1
                        } else if (zi <= -params.entryZ) {
                            pendingEnterSign = 1
                        }
                    } else {
                        val reason =
                            when {
                                abs(zi) >= params.stopZ -> "STOP"
                                abs(zi) <= params.exitZ -> "CONVERGED"
                                params.maxHoldBars > 0 && (i - current.entryIndex) >= params.maxHoldBars -> "MAX_HOLD"
                                else -> null
                            }
                        if (reason != null) pendingExitReason = reason
                    }
                }
            }
        }

        val last = open
        if (last != null) {
            val exitBar = bars[n - 1]
            val trade =
                closePositionAt(
                    last,
                    exitBar.time,
                    n - 1,
                    exitBar.closeA,
                    exitBar.closeB,
                    z[n - 1],
                    "END_OF_PERIOD",
                    specA,
                    specB,
                    fundingA,
                    fundingB,
                    isTradingDay,
                )
            trades.add(trade)
            cash += trade.netPnl
            equity[equity.lastIndex] = cash
        }

        return Result(
            legA = specA.ticker,
            legB = specB.ticker,
            bars = n,
            trades = trades,
            equityCurve = equity,
            equityTimestamps = equityTimes,
            spreadReturnCorrelation = logReturnCorrelation(la, lb),
            meanBeta = betaArr.filter { !it.isNaN() }.average().takeIf { !it.isNaN() } ?: 0.0,
        )
    }

    /** Rolling OLS beta и z-score спреда по окну [lookbackBars]; warmup → NaN. */
    private fun computeRollingStats(
        la: DoubleArray,
        lb: DoubleArray,
        lookbackBars: Int,
        z: DoubleArray,
        betaArr: DoubleArray,
    ) {
        val n = la.size
        val m = lookbackBars.toDouble()
        for (i in lookbackBars - 1 until n) {
            val start = i - lookbackBars + 1
            var sumX = 0.0
            var sumY = 0.0
            var sumXX = 0.0
            var sumXY = 0.0
            for (j in start..i) {
                val x = lb[j]
                val y = la[j]
                sumX += x
                sumY += y
                sumXX += x * x
                sumXY += x * y
            }
            val meanX = sumX / m
            val meanY = sumY / m
            val varX = sumXX / m - meanX * meanX
            if (varX <= EPS) continue
            val beta = (sumXY / m - meanX * meanY) / varX
            betaArr[i] = beta

            var sumS = 0.0
            for (j in start..i) sumS += la[j] - beta * lb[j]
            val meanS = sumS / m
            var sumSS = 0.0
            for (j in start..i) {
                val d = la[j] - beta * lb[j] - meanS
                sumSS += d * d
            }
            val varS = sumSS / m
            if (varS <= EPS) continue
            z[i] = (la[i] - beta * lb[i] - meanS) / sqrt(varS)
        }
    }

    /** Открывает позицию на открытии бара [index] с beta-взвешенным сайзингом. */
    private fun openPosition(
        dirA: Int,
        bar: Bar,
        index: Int,
        beta: Double,
        zEntry: Double,
        targetNotional: Double,
        specA: LegSpec,
        specB: LegSpec,
        betaMin: Double,
    ): OpenPosition? {
        if (beta.isNaN() || beta < betaMin) return null
        val dirB = -dirA
        val (qtyA, qtyB) = sizeLegs(bar.openA, bar.openB, specA.lotSize, specB.lotSize, beta, targetNotional)
        return OpenPosition(
            dirA = dirA,
            dirB = dirB,
            qtyA = qtyA,
            qtyB = qtyB,
            entryA = slipped(bar.openA, dirA, specA.slippageBps),
            entryB = slipped(bar.openB, dirB, specB.slippageBps),
            rawEntryA = bar.openA,
            rawEntryB = bar.openB,
            beta = beta,
            entryIndex = index,
            entryTime = bar.time,
            zEntry = zEntry,
        )
    }

    /** Закрывает позицию по открытию бара [index] (штатный выход). */
    @Suppress("LongParameterList")
    private fun closePosition(
        pos: OpenPosition,
        bar: Bar,
        index: Int,
        zExit: Double,
        reason: String,
        specA: LegSpec,
        specB: LegSpec,
        fundingA: Map<LocalDate, Double>,
        fundingB: Map<LocalDate, Double>,
        isTradingDay: (LocalDate) -> Boolean,
    ): PairTrade {
        val rawExitA = bar.openA
        val rawExitB = bar.openB
        val exitA = slipped(rawExitA, -pos.dirA, specA.slippageBps)
        val exitB = slipped(rawExitB, -pos.dirB, specB.slippageBps)
        return buildTrade(
            pos,
            bar.time,
            index,
            zExit,
            rawExitA,
            rawExitB,
            exitA,
            exitB,
            reason,
            specA,
            specB,
            fundingA,
            fundingB,
            isTradingDay,
        )
    }

    /** Форсированное закрытие в конце периода по close-ценам. */
    @Suppress("LongParameterList")
    private fun closePositionAt(
        pos: OpenPosition,
        exitTime: LocalDateTime,
        index: Int,
        rawExitA: Double,
        rawExitB: Double,
        zExit: Double,
        reason: String,
        specA: LegSpec,
        specB: LegSpec,
        fundingA: Map<LocalDate, Double>,
        fundingB: Map<LocalDate, Double>,
        isTradingDay: (LocalDate) -> Boolean,
    ): PairTrade {
        val exitA = slipped(rawExitA, -pos.dirA, specA.slippageBps)
        val exitB = slipped(rawExitB, -pos.dirB, specB.slippageBps)
        return buildTrade(
            pos,
            exitTime,
            index,
            zExit,
            rawExitA,
            rawExitB,
            exitA,
            exitB,
            reason,
            specA,
            specB,
            fundingA,
            fundingB,
            isTradingDay,
        )
    }

    @Suppress("LongParameterList")
    private fun buildTrade(
        pos: OpenPosition,
        exitTime: LocalDateTime,
        index: Int,
        zExit: Double,
        rawExitA: Double,
        rawExitB: Double,
        exitA: Double,
        exitB: Double,
        reason: String,
        specA: LegSpec,
        specB: LegSpec,
        fundingA: Map<LocalDate, Double>,
        fundingB: Map<LocalDate, Double>,
        isTradingDay: (LocalDate) -> Boolean,
    ): PairTrade {
        val rawGross =
            pos.dirA * pos.qtyA * specA.lotSize * (rawExitA - pos.rawEntryA) +
                pos.dirB * pos.qtyB * specB.lotSize * (rawExitB - pos.rawEntryB)
        val slippedGross =
            pos.dirA * pos.qtyA * specA.lotSize * (exitA - pos.entryA) +
                pos.dirB * pos.qtyB * specB.lotSize * (exitB - pos.entryB)
        val commission =
            pos.qtyA * specA.commissionPerContractSide * 2.0 +
                pos.qtyB * specB.commissionPerContractSide * 2.0
        val funding = fundingCost(pos, exitTime, specA, specB, fundingA, fundingB, isTradingDay)
        val net = slippedGross - commission - funding
        return PairTrade(
            side = if (pos.dirA > 0) "LONG_SPREAD" else "SHORT_SPREAD",
            entryTime = pos.entryTime,
            exitTime = exitTime,
            qtyA = pos.qtyA,
            qtyB = pos.qtyB,
            entryPriceA = pos.entryA,
            entryPriceB = pos.entryB,
            exitPriceA = exitA,
            exitPriceB = exitB,
            beta = pos.beta,
            zEntry = pos.zEntry,
            zExit = zExit,
            holdBars = index - pos.entryIndex,
            grossPnl = slippedGross,
            commission = commission,
            slippage = rawGross - slippedGross,
            funding = funding,
            netPnl = net,
            closeReason = reason,
        )
    }

    private fun fundingCost(
        pos: OpenPosition,
        exitTime: LocalDateTime,
        specA: LegSpec,
        specB: LegSpec,
        fundingA: Map<LocalDate, Double>,
        fundingB: Map<LocalDate, Double>,
        isTradingDay: (LocalDate) -> Boolean,
    ): Double {
        val dates = FundingCosts.clearingDates(pos.entryTime, exitTime, isTradingDay)
        var cost = 0.0
        for (d in dates) {
            val rateA = fundingA[d] ?: specA.fundingPerContractClearing
            val rateB = fundingB[d] ?: specB.fundingPerContractClearing
            cost += pos.dirA * pos.qtyA * rateA + pos.dirB * pos.qtyB * rateB
        }
        return cost
    }

    private fun unrealized(
        pos: OpenPosition,
        closeA: Double,
        closeB: Double,
        specA: LegSpec,
        specB: LegSpec,
    ): Double =
        pos.dirA * pos.qtyA * specA.lotSize * (closeA - pos.entryA) +
            pos.dirB * pos.qtyB * specB.lotSize * (closeB - pos.entryB)

    /** Целевой нотионал → целое число контрактов каждой ноги (beta-взвешенный хедж). */
    private fun sizeLegs(
        openA: Double,
        openB: Double,
        lotA: Int,
        lotB: Int,
        beta: Double,
        targetNotional: Double,
    ): Pair<Int, Int> {
        val notionalA = openA * lotA
        val notionalB = openB * lotB
        return if (notionalB >= notionalA) {
            val qtyB = maxOf(1, (targetNotional / notionalB).roundToInt())
            val qtyA = maxOf(1, (beta * qtyB * notionalB / notionalA).roundToInt())
            qtyA to qtyB
        } else {
            val qtyA = maxOf(1, (targetNotional / notionalA).roundToInt())
            val qtyB = maxOf(1, (qtyA * notionalA / (beta * notionalB)).roundToInt())
            qtyA to qtyB
        }
    }

    /** Покупка — цена вверх на slippageBps, продажа — вниз. */
    private fun slipped(
        price: Double,
        side: Int,
        bps: Double,
    ): Double {
        val factor = if (side > 0) 1.0 + bps / 10_000.0 else 1.0 - bps / 10_000.0
        return price * factor
    }

    /** Пирсоновская корреляция лог-доходностей двух ног. */
    private fun logReturnCorrelation(
        la: DoubleArray,
        lb: DoubleArray,
    ): Double {
        val n = la.size
        if (n < 3) return 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumYY = 0.0
        var sumXY = 0.0
        val m = (n - 1).toDouble()
        for (i in 1 until n) {
            val x = la[i] - la[i - 1]
            val y = lb[i] - lb[i - 1]
            sumX += x
            sumY += y
            sumXX += x * x
            sumYY += y * y
            sumXY += x * y
        }
        val cov = sumXY / m - (sumX / m) * (sumY / m)
        val varX = sumXX / m - (sumX / m) * (sumX / m)
        val varY = sumYY / m - (sumY / m) * (sumY / m)
        if (varX <= EPS || varY <= EPS) return 0.0
        return cov / sqrt(varX * varY)
    }

    private const val EPS = 1e-12
}
