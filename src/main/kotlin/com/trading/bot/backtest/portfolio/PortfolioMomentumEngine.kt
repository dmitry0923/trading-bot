package com.trading.bot.backtest.portfolio

import com.trading.bot.application.FundingCosts
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Чистый research-движок cross-sectional momentum + carry портфеля фьючерсов.
 *
 * На каждом ребалансе (`rebalanceBars` баров) инструменты ранжируются по
 * cross-sectional z-score momentum (лог-доходность за `lookbackBars`) и, опционально,
 * по carry (знак/величина funding, нормированная на нотионал): `score = z(mom) + λ·z(carry)`.
 * Покупаются `longCount` лучших, продаются `shortCount` худших (равные нотионалы,
 * долларово-нейтрально). Сигнал считается на закрытии бара `i−1`, исполняется на
 * открытии `i` (lookahead закрыт). Все издержки (комиссия, слиппедж, funding)
 * вычитаются из P&L сделки.
 *
 * Движок не зависит от Spring/БД (на вход — выровненные по времени бары юниверса)
 * и переиспользует [FundingCosts] для моментов начисления funding. См.
 * `docs/26-cross-sectional-momentum-carry.md`.
 */
object PortfolioMomentumEngine {
    /** Спецификация инструмента юниверса (из [com.trading.bot.config.InstrumentsConfig]). */
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
        val initialCapital: Double = 1_000_000.0,
        /** Окно momentum (лог-доходность), бары. */
        val lookbackBars: Int = 1680,
        /** Инвертировать знак momentum-члена: true → cross-sectional mean-reversion (long отстающие). */
        val reversalEnabled: Boolean = false,
        /** Период между ребалансами, бары. */
        val rebalanceBars: Int = 420,
        /** Число длинных ног (лучшие по score). */
        val longCount: Int = 1,
        /** Число коротких ног (худшие по score). */
        val shortCount: Int = 1,
        /** Целевой суммарный нотионал книги как доля капитала (делится на число ног). */
        val capitalSlice: Double = 1.0,
        /** Вес carry-тилта λ в score (0 = читый momentum). */
        val carryWeight: Double = 0.0,
        /** Окно усреднения funding для carry, число последних клирингов. */
        val carryLookbackDays: Int = 5,
        /** Минимум валидных инструментов в сечении для формирования сигнала. */
        val minUniverse: Int = 3,
        /**
         * Вес momentum-терма в score: `w·sign·z(mom) + λ·z(carry)`. `0` выключает momentum
         * (carry-центричный режим при `carryWeight>0`), тогда momentum-lookback не требуется.
         */
        val momentumWeight: Double = 1.0,
        /** Веса ног обратно пропорционально их волатильности (risk-parity); иначе равные нотионалы. */
        val volScalingEnabled: Boolean = false,
        /** Окно оценки реализованной волатильности ноги, бары. */
        val volLookbackBars: Int = 420,
        /** Масштабировать суммарный нотионал книги к целевой волатильности (de-risk при высокой σ). */
        val volTargetEnabled: Boolean = false,
        /** Целевая годовая волатильность книги (доля). Скалирование только вниз (≤ 1). */
        val volTargetAnnualized: Double = 0.15,
        /** Число баров в году для аннуализации волатильности книги. */
        val volTargetBarsPerYear: Double = 21_000.0,
    )

    /** Выровненный по времени бар юниверса (индексы соответствуют [LegSpec] списка specs). */
    data class Bar(
        val time: LocalDateTime,
        val opens: DoubleArray,
        val closes: DoubleArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bar) return false
            return time == other.time && opens.contentEquals(other.opens) && closes.contentEquals(other.closes)
        }

        override fun hashCode(): Int {
            var result = time.hashCode()
            result = 31 * result + opens.contentHashCode()
            result = 31 * result + closes.contentHashCode()
            return result
        }
    }

    /** Одна закрытая нога портфеля (трейд-лог). */
    data class Trade(
        val ticker: String,
        val side: String,
        val entryTime: LocalDateTime,
        val exitTime: LocalDateTime,
        val qty: Int,
        val entryPrice: Double,
        val exitPrice: Double,
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

    /** Результат прогона портфеля. */
    data class Result(
        val universe: List<String>,
        val bars: Int,
        val trades: List<Trade>,
        val equityCurve: List<Double>,
        val equityTimestamps: List<LocalDateTime>,
        val rebalances: Int,
    )

    private data class Selection(
        val longs: List<Int>,
        val shorts: List<Int>,
    )

    private class OpenLeg(
        val idx: Int,
        val dir: Int,
        val qty: Int,
        val entryPrice: Double,
        val rawEntryPrice: Double,
        val entryIndex: Int,
        val entryTime: LocalDateTime,
    )

    /**
     * Прогоняет портфель.
     *
     * @param bars выровненные по времени бары юниверса
     * @param specs спецификации инструментов (порядок = индексы в [Bar])
     * @param params параметры прогона
     * @param funding funding по инструментам: дата клиринга → руб/контракт (пусто = spec fallback)
     * @param isTradingDay предикат торгового дня для клирингов funding
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun run(
        bars: List<Bar>,
        specs: List<LegSpec>,
        params: Params,
        funding: List<Map<LocalDate, Double>> = emptyList(),
        isTradingDay: (LocalDate) -> Boolean = { true },
    ): Result {
        val n = bars.size
        val u = specs.size
        val unknownUniverse = specs.map { it.ticker }
        if (n < params.lookbackBars + params.rebalanceBars + 2 || u < params.minUniverse) {
            return Result(unknownUniverse, n, emptyList(), emptyList(), emptyList(), 0)
        }

        val totalBookNotional = params.capitalSlice * params.initialCapital
        val rebalanceStart = params.lookbackBars + 1
        val trades = ArrayList<Trade>()
        val equity = ArrayList<Double>(n)
        val times = ArrayList<LocalDateTime>(n)
        val open = HashMap<Int, OpenLeg>()
        var cash = params.initialCapital
        var rebalances = 0

        for (i in 0 until n) {
            val bar = bars[i]
            val isRebalance = i >= rebalanceStart && (i - rebalanceStart) % params.rebalanceBars == 0
            if (isRebalance) {
                if (open.isNotEmpty()) {
                    for (leg in open.values) {
                        val trade = closeLeg(leg, bar.opens[leg.idx], i, bar.time, "REBALANCE", specs, funding, isTradingDay)
                        trades.add(trade)
                        cash += trade.netPnl
                    }
                    open.clear()
                }
                val selection = select(bars, i, specs, params, funding)
                if (selection != null) {
                    val notionals = notionalAllocation(bars, i - 1, selection.longs + selection.shorts, params, totalBookNotional)
                    val scale =
                        if (params.volTargetEnabled) {
                            volScaleFactor(bars, i - 1, selection, notionals, totalBookNotional, params)
                        } else {
                            1.0
                        }
                    for (idx in selection.longs) open[idx] = openLeg(idx, 1, bar, i, (notionals[idx] ?: 0.0) * scale, specs)
                    for (idx in selection.shorts) open[idx] = openLeg(idx, -1, bar, i, (notionals[idx] ?: 0.0) * scale, specs)
                    rebalances++
                }
            }

            var unrealized = 0.0
            for (leg in open.values) {
                unrealized += leg.dir * leg.qty * specs[leg.idx].lotSize * (bar.closes[leg.idx] - leg.entryPrice)
            }
            equity.add(cash + unrealized)
            times.add(bar.time)
        }

        if (open.isNotEmpty()) {
            val last = bars[n - 1]
            for (leg in open.values) {
                val trade = closeLeg(leg, last.closes[leg.idx], n - 1, last.time, "END_OF_PERIOD", specs, funding, isTradingDay)
                trades.add(trade)
                cash += trade.netPnl
            }
            equity[equity.lastIndex] = cash
        }

        return Result(
            universe = unknownUniverse,
            bars = n,
            trades = trades,
            equityCurve = equity,
            equityTimestamps = times,
            rebalances = rebalances,
        )
    }

    /** Считает score по сечению на баре [i] (по данным до [i]) и выбирает ноги. */
    private fun select(
        bars: List<Bar>,
        i: Int,
        specs: List<LegSpec>,
        params: Params,
        funding: List<Map<LocalDate, Double>>,
    ): Selection? {
        val ref = i - 1
        val u = specs.size
        val needMom = params.momentumWeight != 0.0
        val needCarry = params.carryWeight != 0.0
        if (!needMom && !needCarry) return null
        val sign = momentumSign(params)
        val base = ref - params.lookbackBars
        if (needMom && base < 0) return null
        val mom = DoubleArray(u) { Double.NaN }
        val carry = DoubleArray(u) { Double.NaN }
        for (k in 0 until u) {
            if (needMom) {
                val p0 = bars[base].closes[k]
                val p1 = bars[ref].closes[k]
                if (p0.isFinite() && p1.isFinite() && p0 > 0.0 && p1 > 0.0) {
                    mom[k] = ln(p1) - ln(p0)
                }
            }
            carry[k] = carryYield(k, bars[ref], specs[k], params, funding)
        }
        val valid =
            (0 until u).filter {
                (!needMom || !mom[it].isNaN()) && (!needCarry || !carry[it].isNaN())
            }
        if (valid.size < params.minUniverse || valid.size < params.longCount + params.shortCount) return null
        val score = DoubleArray(u) { Double.NaN }
        if (needMom) {
            val momZ = zscore(mom, valid) ?: return null
            for (k in valid) score[k] = params.momentumWeight * sign * momZ[k]
        } else {
            for (k in valid) score[k] = 0.0
        }
        if (needCarry) {
            val carryZ = zscore(carry, valid) ?: return null
            for (k in valid) score[k] = score[k] + params.carryWeight * carryZ[k]
        }
        val ranked = valid.sortedByDescending { score[it] }
        return Selection(
            longs = ranked.take(params.longCount),
            shorts = ranked.takeLast(params.shortCount),
        )
    }

    /**
     * Carry-привлекательность быть длинным: `−avgFunding / notional` (>0 = лонг получает
     * funding). Пустая история → spec-fallback (постоянный, на ранг не влияет).
     */
    private fun carryYield(
        k: Int,
        refBar: Bar,
        spec: LegSpec,
        params: Params,
        funding: List<Map<LocalDate, Double>>,
    ): Double {
        val notional = refBar.closes[k] * spec.lotSize
        if (!notional.isFinite() || notional <= 0.0) return Double.NaN
        val map = funding.getOrElse(k) { emptyMap() }
        if (map.isEmpty()) return -spec.fundingPerContractClearing / notional
        val day = refBar.time.toLocalDate()
        val recent =
            map.entries
                .asSequence()
                .filter { !it.key.isAfter(day) }
                .sortedByDescending { it.key }
                .take(params.carryLookbackDays)
                .toList()
        if (recent.isEmpty()) return Double.NaN
        val avg = recent.map { it.value }.average()
        return -avg / notional
    }

    /** Cross-sectional z-score по валидным индексам; null, если дисперсия вырождена. */
    private fun zscore(
        values: DoubleArray,
        valid: List<Int>,
    ): DoubleArray? {
        val mean = valid.map { values[it] }.average()
        var sumSquared = 0.0
        for (idx in valid) {
            val d = values[idx] - mean
            sumSquared += d * d
        }
        val variance = sumSquared / valid.size
        if (!variance.isFinite() || variance <= EPS) return null
        val sd = sqrt(variance)
        return DoubleArray(values.size) { idx ->
            if (values[idx].isNaN()) Double.NaN else (values[idx] - mean) / sd
        }
    }

    private fun momentumSign(params: Params): Double = if (params.reversalEnabled) -1.0 else 1.0

    /**
     * Распределяет [total] нотионала по выбранным ногам: равными долями, либо (при
     * [Params.volScalingEnabled]) обратно пропорционально реализованной волатильности ноги
     * (risk-parity). При недоступной волатильности любой из ног — безопасный откат к равным долям.
     */
    private fun notionalAllocation(
        bars: List<Bar>,
        ref: Int,
        selected: List<Int>,
        params: Params,
        total: Double,
    ): Map<Int, Double> {
        val equal = total / maxOf(1, selected.size)
        if (!params.volScalingEnabled) return selected.associateWith { equal }
        val vols = selected.map { realizedVol(bars, ref, it, params.volLookbackBars) }
        if (vols.any { it.isNaN() || it <= EPS }) return selected.associateWith { equal }
        val invSum = vols.sumOf { 1.0 / it }
        if (!invSum.isFinite() || invSum <= EPS) return selected.associateWith { equal }
        return selected.indices.associate { selected[it] to total * (1.0 / vols[it]) / invSum }
    }

    /**
     * Множитель (≤ 1) к суммарному нотионалу книги: при [Params.volTargetEnabled] снижает
     * экспозицию, если реализованная аннуализированная волатильность книги (по текущим весам
     * ног) выше [Params.volTargetAnnualized]. При нехватке истории/вырожденной σ — 1.0
     * (fail-safe, экспозиция не меняется).
     */
    private fun volScaleFactor(
        bars: List<Bar>,
        ref: Int,
        selection: Selection,
        alloc: Map<Int, Double>,
        total: Double,
        params: Params,
    ): Double {
        if (total <= EPS) return 1.0
        val start = ref - params.volLookbackBars
        if (start < 1) return 1.0
        val signed = HashMap<Int, Double>(selection.longs.size + selection.shorts.size)
        for (idx in selection.longs) signed[idx] = 1.0
        for (idx in selection.shorts) signed[idx] = -1.0
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (t in (start + 1)..ref) {
            var r = 0.0
            var ok = false
            for (idx in signed.keys) {
                val p0 = bars[t - 1].closes[idx]
                val p1 = bars[t].closes[idx]
                if (p0.isFinite() && p1.isFinite() && p0 > 0.0 && p1 > 0.0) {
                    val w = (signed[idx] ?: 0.0) * (alloc[idx] ?: 0.0) / total
                    r += w * (ln(p1) - ln(p0))
                    ok = true
                }
            }
            if (ok) {
                sum += r
                sumSq += r * r
                count++
            }
        }
        if (count < 2) return 1.0
        val mean = sum / count
        val variance = sumSq / count - mean * mean
        if (!variance.isFinite() || variance <= EPS) return 1.0
        val annualVol = sqrt(variance) * sqrt(params.volTargetBarsPerYear)
        if (!annualVol.isFinite() || annualVol <= EPS) return 1.0
        return (params.volTargetAnnualized / annualVol).coerceIn(0.0, 1.0)
    }

    /** Реализованная волатильность (σ лог-доходностей) инструмента [k] на окне до [ref] включительно. */
    private fun realizedVol(
        bars: List<Bar>,
        ref: Int,
        k: Int,
        lookback: Int,
    ): Double {
        val start = ref - lookback
        if (start < 1) return Double.NaN
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (t in (start + 1)..ref) {
            val p0 = bars[t - 1].closes[k]
            val p1 = bars[t].closes[k]
            if (p0.isFinite() && p1.isFinite() && p0 > 0.0 && p1 > 0.0) {
                val r = ln(p1) - ln(p0)
                sum += r
                sumSq += r * r
                count++
            }
        }
        if (count < 2) return Double.NaN
        val mean = sum / count
        val variance = sumSq / count - mean * mean
        if (!variance.isFinite() || variance <= EPS) return Double.NaN
        return sqrt(variance)
    }

    private fun openLeg(
        idx: Int,
        dir: Int,
        bar: Bar,
        index: Int,
        targetNotional: Double,
        specs: List<LegSpec>,
    ): OpenLeg {
        val spec = specs[idx]
        val notional = bar.opens[idx] * spec.lotSize
        val qty = if (notional > 0.0) maxOf(1, (targetNotional / notional).roundToInt()) else 1
        return OpenLeg(
            idx = idx,
            dir = dir,
            qty = qty,
            entryPrice = slipped(bar.opens[idx], dir, spec.slippageBps),
            rawEntryPrice = bar.opens[idx],
            entryIndex = index,
            entryTime = bar.time,
        )
    }

    @Suppress("LongParameterList")
    private fun closeLeg(
        leg: OpenLeg,
        rawExit: Double,
        index: Int,
        exitTime: LocalDateTime,
        reason: String,
        specs: List<LegSpec>,
        funding: List<Map<LocalDate, Double>>,
        isTradingDay: (LocalDate) -> Boolean,
    ): Trade {
        val spec = specs[leg.idx]
        val exitPrice = slipped(rawExit, -leg.dir, spec.slippageBps)
        val rawGross = leg.dir * leg.qty * spec.lotSize * (rawExit - leg.rawEntryPrice)
        val slippedGross = leg.dir * leg.qty * spec.lotSize * (exitPrice - leg.entryPrice)
        val commission = leg.qty * spec.commissionPerContractSide * 2.0
        val cost = fundingCost(leg, exitTime, spec, funding.getOrElse(leg.idx) { emptyMap() }, isTradingDay)
        return Trade(
            ticker = spec.ticker,
            side = if (leg.dir > 0) "LONG" else "SHORT",
            entryTime = leg.entryTime,
            exitTime = exitTime,
            qty = leg.qty,
            entryPrice = leg.entryPrice,
            exitPrice = exitPrice,
            holdBars = index - leg.entryIndex,
            grossPnl = slippedGross,
            commission = commission,
            slippage = rawGross - slippedGross,
            funding = cost,
            netPnl = slippedGross - commission - cost,
            closeReason = reason,
        )
    }

    private fun fundingCost(
        leg: OpenLeg,
        exitTime: LocalDateTime,
        spec: LegSpec,
        funding: Map<LocalDate, Double>,
        isTradingDay: (LocalDate) -> Boolean,
    ): Double {
        val dates = FundingCosts.clearingDates(leg.entryTime, exitTime, isTradingDay)
        var cost = 0.0
        for (d in dates) {
            val rate = funding[d] ?: spec.fundingPerContractClearing
            cost += leg.dir * leg.qty * rate
        }
        return cost
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

    private const val EPS = 1e-12
}
