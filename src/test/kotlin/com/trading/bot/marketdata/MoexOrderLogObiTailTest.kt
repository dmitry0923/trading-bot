package com.trading.bot.marketdata

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Collections
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertTrue

/**
 * Исследовательский стенд: сосредоточен ли эффект OBI в хвостах.
 *
 * Зачем. Замер по всем наблюдениям ([MoexOrderLogMicrostructureSignalTest]) дал
 * доход «вход по знаку OBI» 0.11…0.32 bps против круговых издержек 3…11 bps —
 * разрыв на порядок, и по такому результату покупка платного архива не
 * обоснована. Единственное правдоподобное объяснение возможной рентабельности:
 * эффект распределён неравномерно и сосредоточен на редких экстремумах
 * |OBI| >= k·σ. Сетка порогов k = 1.5/2.0/2.5/3.0 зафиксирована в
 * `docs/23-microstructure-forward-pilot.md` §3 **до** сбора, чтобы вывод не
 * подгонялся под инструмент; здесь считается ровно эта сетка, ничего не
 * подбирается.
 *
 * Что считается. Для каждого наблюдения стакана в момент `t`:
 * - признак `OBI` (доля объёма bid в сумме bid+ask);
 * - хвост по **z-score внутри сессии**: `|OBI − μ_сессии| >= k·σ_сессии`.
 *   Центрирование обязательно: σ необработанной OBI у CNYRUBF равен ~0.7, то
 *   есть книга односторонняя (нулевой объём на одной из сторон), и порог
 *   `k·σ` без вычитания среднего физически недостижим при k >= 1.5 — это
 *   отсекает ложный вывод «хвоста нет» из-за неверного порога;
 * - позиция `sign(OBI)`, выход — первая сделка не раньше `t + H`.
 *
 * Три варианта дохода, потому что они дают разные ответы:
 * - **по mid** — как в исходном стенде, спред не платится вовсе (недостижимо);
 * - **taker** — вход на touch (`ask` на покупку, `bid` на продажу), спред
 *   платится фактическим спредом стакана в момент входа; за выход добавляются
 *   комиссия и проскальзывание;
 * - **passive** — вход и выход лимитной заявкой на touch по восстановленному
 *   стакану. Это **оптимистичная граница**: приоритет в очереди и адверс-селекшн
 *   не моделируются, поэтому passive нельзя читать как достижимый доход.
 *
 * Контроли, без которых хвостовая цифра ничего не значит:
 * - **placebo** — перемешанный внутри сессии OBI с тем же отбором хвоста;
 * - **momentum** — тот же хвост, но позиция берётся по знаку прошлого
 *   движения: если доход тот же, OBI не добавляет ничего к momentum;
 * - **устойчивость по половинам дня** — знак должен совпадать;
 * - **блочный бутстрэп** — хвостовые наблюдения кластеризуются (OBI держится
 *   экстремальным десятки секунд), поэтому блоки режут исходный ряд по времени,
 *   а не отфильтрованный; дополнительно печатается число непрерывных эпизодов
 *   хвоста — это и есть реальный размер выборки.
 *
 * Тест печатает измерения и вердикт по критерию `docs/23` §4, но утверждает
 * только механические инварианты: решение о покупке данных принимается человеком
 * по совокупности измерений на независимых днях.
 */
class MoexOrderLogObiTailTest {
    private val sampleDir = File("data/samples/moex_orderlog_20241001")

    private companion object {
        /** Сетка порогов из docs/23 §3. Не подбирается. */
        val TAIL_SIGMA = listOf(1.5, 2.0, 2.5, 3.0)

        /**
         * Сетка в абсолютных единицах OBI, добавленная после того, как σ-сетка
         * оказалась пустой по построению (см. KDoc): у CNYRUBF OBI распределена
         * почти равномерно на [-1, 1], поэтому «хвост» = книга почти целиком на
         * одной стороне, а не редкий выброс относительно среднего.
         */
        val TAIL_ABSOLUTE = listOf(0.5, 0.7, 0.9)

        val HORIZONS_MS = listOf(250L, 1000L, 5000L, 30_000L)

        /** Критерий успеха из docs/23 §4, bps на сделку после издержек. */
        const val SUCCESS_EDGE_BPS = 5.0

        /** Минимум наблюдений в хвосте, ниже которого доверительный интервал бессмыслен. */
        const val MIN_TAIL_OBS = 40

        const val RESAMPLES = 1000

        const val MEDIAN_STEP_MS = 70.0

        /** CNYRUBF: комиссия 1.5 ₽/контракт/сторона при номинале ≈ 13 200 ₽. */
        const val COMMISSION_BPS = 1.5 / 13_200.0 * 10_000.0

        const val SLIPPAGE_BPS = 1.0

        /** Стакан старше этого не считается состоянием на `t + horizon`. */
        val MAX_BOOK_AGE: Duration = Duration.ofSeconds(2)

        val SESSION_GAP: Duration = Duration.ofSeconds(MoexOrderLogParser.DEFAULT_SESSION_GAP_SECONDS)
    }

    @Test
    fun `obi tail edge is measured on the real sample day`() {
        assumeTrue(
            sampleDir.resolve("20241001_CNYRUBF_fut_tick.csv").exists(),
            "полный срез бесплатного образца не распакован локально",
        )

        val buckets = mutableListOf<MoexOrderLogParser.BboBucket>()
        val trades = mutableListOf<MoexOrderLogParser.TradeTick>()
        val stats =
            MoexOrderLogParser.parseDay(
                path = sampleDir,
                ticker = "CNYRUBF",
                bucketMillis = 1,
                bucketSink = { buckets += it },
                tickSink = { trades += it },
            )

        val n = buckets.size
        println("=== MOEX 2024-10-01 CNYRUBF, хвосты OBI ===")
        println("стакан: $n наблюдений, сделки: ${trades.size}, сессии: ${stats.sessions}")

        assertTrue(n > 10_000, "миллисекундный стакан должен быть плотным: $n")
        assertTrue(buckets.zipWithNext().all { (a, b) -> a.time <= b.time }, "время стакана не убывает")

        val obi = DoubleArray(n) { buckets[it].obi?.toDouble() ?: 0.0 }
        val bid = DoubleArray(n) { buckets[it].bid.toDouble() }
        val ask = DoubleArray(n) { buckets[it].ask.toDouble() }
        val mid = DoubleArray(n) { (bid[it] + ask[it]) / 2.0 }
        val sessions = sessionIndex(buckets)
        val med = sessionMedian(obi, sessions)
        val std = sessionStdSigma(obi, sessions)
        val sigma = sessionRobustSigma(obi, sessions, med)
        val z = DoubleArray(n) { (obi[it] - med[it]) / sigma[it] }
        print("сессии:")
        for (s in sessions.distinct()) {
            val idx = sessions.indices.filter { sessions[it] == s }
            val sd = sigma[idx.first()]
            val maxZ = idx.maxOf { abs(obi[it] - med[it]) / sd }
            print(
                " s$s n=${idx.size} median=${fmt(med[idx.first()])} sigma_std=${fmt(std[idx.first()])} " +
                    "sigma_MAD=${fmt(sd)} max|z|=${fmt(maxZ)}",
            )
        }
        println()
        println(
            "OBI: доля односторонней книги (|OBI| > 0.99) = " +
                fmt(obi.count { abs(it) > 0.99 }.toDouble() / n * 100) + "%",
        )

        var best: TailRow? = null
        for (horizonMs in HORIZONS_MS) {
            val variant = buildVariant(buckets, trades, obi, bid, ask, mid, horizonMs)
            println(
                "--- горизонт $horizonMs мс: покрытие forward=${fmt(coverage(variant.grossMid))} " +
                    "passive=${fmt(coverage(variant.passive))} ---",
            )
            var previousCount = Int.MAX_VALUE
            for (k in TAIL_SIGMA) {
                val row = tailRow(TailMode.SIGMA, k, obi, z, med, sigma, sessions, variant, horizonMs)
                println(row)
                assertTrue(
                    row.count <= previousCount,
                    "при большем пороге хвост не может вырасти: k=$k, было $previousCount, стало ${row.count}",
                )
                previousCount = row.count
                consider(best, row)?.let { best = it }
            }
            previousCount = Int.MAX_VALUE
            for (threshold in TAIL_ABSOLUTE) {
                val row = tailRow(TailMode.ABSOLUTE, threshold, obi, z, med, sigma, sessions, variant, horizonMs)
                println(row)
                assertTrue(
                    row.count <= previousCount,
                    "при большем пороге хвост не может вырасти: |OBI| >= $threshold, стало ${row.count} > $previousCount",
                )
                previousCount = row.count
                consider(best, row)?.let { best = it }
            }
        }

        println("=== РЕШЕНИЕ (docs/23 §4) ===")
        if (best == null) {
            println(
                "ни одна ячейка сетки не даёт нижнюю границу 95% CI выше $SUCCESS_EDGE_BPS bps " +
                    "для taker-варианта → хвост издержки не окупает, покупка данных не обоснована",
            )
        } else {
            println("лучшая ячейка: $best")
            println(
                "критерий успеха выполнен в ячейке, но §4 требует >= 2 последовательных недель и " +
                    ">= 20 торговых дней: на одном дне (2024-10-01) это не проверяется",
            )
        }
        println("=== OK ===")
    }

    /** Per-наблюдение P&L всех вариантов на одном горизонте; NaN — данных нет. */
    private class Variant(
        val grossMid: DoubleArray,
        val netTaker: DoubleArray,
        val passive: DoubleArray,
        val momentum: DoubleArray,
    )

    private fun buildVariant(
        buckets: List<MoexOrderLogParser.BboBucket>,
        trades: List<MoexOrderLogParser.TradeTick>,
        obi: DoubleArray,
        bid: DoubleArray,
        ask: DoubleArray,
        mid: DoubleArray,
        horizonMs: Long,
    ): Variant {
        val n = buckets.size
        val exit = forwardExitPrice(buckets, trades, horizonMs)
        val exitBook = bookIndexAtOrBefore(buckets, horizonMs)
        val trailing = trailingExitPrice(buckets, trades, horizonMs)

        val grossMid = DoubleArray(n) { Double.NaN }
        val netTaker = DoubleArray(n) { Double.NaN }
        val passive = DoubleArray(n) { Double.NaN }
        val momentum = DoubleArray(n) { Double.NaN }

        for (i in 0 until n) {
            val position = if (obi[i] > 0) 1.0 else -1.0
            val exitPrice = exit[i]
            val entry = if (position > 0) ask[i] else bid[i]
            if (!exitPrice.isNaN() && mid[i] > 0.0) {
                grossMid[i] = position * (exitPrice - mid[i]) / mid[i] * 10_000.0
                val past = trailing[i]
                if (!past.isNaN()) {
                    val pastPosition = if (past > 0) 1.0 else -1.0
                    momentum[i] = pastPosition * (exitPrice - mid[i]) / mid[i] * 10_000.0
                }
            }
            if (!exitPrice.isNaN() && entry > 0.0) {
                val gross = position * (exitPrice - entry) / entry * 10_000.0
                netTaker[i] = gross - 2 * COMMISSION_BPS - 2 * SLIPPAGE_BPS
            }

            val j = exitBook[i]
            if (j >= 0 && entry > 0.0) {
                val stale = Duration.between(buckets[j].time, buckets[i].time.plusNanos(horizonMs * 1_000_000))
                val exitTouch = if (position > 0) bid[j] else ask[j]
                if (!stale.isNegative && stale <= MAX_BOOK_AGE) {
                    passive[i] = position * (exitTouch - entry) / entry * 10_000.0 - 2 * COMMISSION_BPS
                }
            }
        }
        return Variant(grossMid, netTaker, passive, momentum)
    }

    /** Режим отбора хвоста: относительный порог в σ или абсолютный порог |OBI|. */
    private enum class TailMode { SIGMA, ABSOLUTE }

    private fun consider(
        best: TailRow?,
        row: TailRow,
    ): TailRow? = if (row.ciLow > SUCCESS_EDGE_BPS && (best == null || row.netTaker > best.netTaker)) row else best

    private class TailRow(
        val mode: TailMode,
        val k: Double,
        val horizonMs: Long,
        val count: Int,
        val share: Double,
        val episodes: Int,
        val grossMid: Double,
        val netTaker: Double,
        val netPassive: Double,
        val momentum: Double,
        val placebo: Double,
        val ciLow: Double,
        val ciHigh: Double,
        val firstHalf: Double,
        val secondHalf: Double,
    ) {
        override fun toString(): String =
            "  $mode${if (mode == TailMode.SIGMA) " k=$k" else " |OBI| >= $k"}: " +
                "хвост $count (${fmt(share * 100)}%), эпизодов $episodes | " +
                "gross(mid)=${fmt(grossMid)} net(taker)=${fmt(netTaker)} " +
                "net(passive, оптимист.)=${fmt(netPassive)} momentum=${fmt(momentum)} " +
                "placebo=${fmt(placebo)} | CI95=[${fmt(ciLow)}, ${fmt(ciHigh)}] " +
                "половины дня: 1=${fmt(firstHalf)} 2=${fmt(secondHalf)}"
    }

    private fun tailRow(
        mode: TailMode,
        k: Double,
        obi: DoubleArray,
        z: DoubleArray,
        med: DoubleArray,
        sigma: DoubleArray,
        sessions: IntArray,
        variant: Variant,
        horizonMs: Long,
    ): TailRow {
        val selected = BooleanArray(z.size)
        var count = 0
        for (i in z.indices) {
            val inTail = if (mode == TailMode.SIGMA) abs(z[i]) >= k else abs(obi[i]) >= k
            if (inTail && !variant.netTaker[i].isNaN()) {
                selected[i] = true
                count++
            }
        }
        if (count < MIN_TAIL_OBS) {
            return TailRow(mode, k, horizonMs, count, 0.0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val shuffled = shuffledWithinSessions(obi, sessions)
        val placeboSelected =
            BooleanArray(z.size) { i ->
                val tail = if (mode == TailMode.SIGMA) abs((obi[i] - med[i]) / sigma[i]) >= k else abs(shuffled[i]) >= k
                tail && !variant.netTaker[i].isNaN()
            }

        val last = selected.indexOfLast { it }
        val midpoint = last / 2
        val block = blockSize(z.size, horizonMs)
        val ci = blockBootstrapCi(variant.netTaker, selected, block)
        return TailRow(
            mode = mode,
            k = k,
            horizonMs = horizonMs,
            count = count,
            share = count.toDouble() / z.size,
            episodes = episodes(selected),
            grossMid = mean(variant.grossMid, selected),
            netTaker = mean(variant.netTaker, selected),
            netPassive = mean(variant.passive, selected),
            momentum = mean(variant.momentum, selected),
            placebo = mean(variant.netTaker, placeboSelected),
            ciLow = ci.first,
            ciHigh = ci.second,
            firstHalf = mean(variant.netTaker, selected, 0, midpoint),
            secondHalf = mean(variant.netTaker, selected, midpoint, selected.size),
        )
    }

    /** Индекс сессии по разрыву в 30 минут — тем же правилом, что и парсер. */
    private fun sessionIndex(buckets: List<MoexOrderLogParser.BboBucket>): IntArray {
        val sessions = IntArray(buckets.size)
        var current = 0
        for (i in buckets.indices) {
            if (i > 0 && Duration.between(buckets[i - 1].time, buckets[i].time) > SESSION_GAP) current++
            sessions[i] = current
        }
        return sessions
    }

    /** σ OBI внутри сессии: порог не должен зависеть от того, какая сессия сейчас идёт. */
    private fun sessionMedian(
        obi: DoubleArray,
        sessions: IntArray,
    ): DoubleArray {
        val median = DoubleArray(obi.size)
        for (s in sessions.distinct()) {
            val value = median(obi.indices.filter { sessions[it] == s }.map { obi[it] })
            for (i in obi.indices) {
                if (sessions[i] == s) median[i] = value
            }
        }
        return median
    }

    /** Стандартное отклонение внутри сессии: печатается для контраста с робастной σ. */
    private fun sessionStdSigma(
        obi: DoubleArray,
        sessions: IntArray,
    ): DoubleArray {
        val sigma = DoubleArray(obi.size)
        for (s in sessions.distinct()) {
            val values = obi.indices.filter { sessions[it] == s }.map { obi[it] }
            val average = values.average()
            val sd = sqrt(values.sumOf { (it - average) * (it - average) } / values.size)
            for (i in obi.indices) {
                if (sessions[i] == s) sigma[i] = sd
            }
        }
        return sigma
    }

    /**
     * Робастная σ внутри сессии: 1.4826·MAD.
     *
     * Обязательна именно для OBI: книга CNYRUBF часто односторонняя
     * (|OBI| > 0.99 почти у всех наблюдений), поэтому σ по дисперсии ≈ 0.67,
     * и при ней |z| физически не превышает ~1.5 — сетка k = 2.0…3.0 оказывается
     * пустой по построению, а не по отсутствию эффекта. MAD к выбросам устойчив,
     * поэтому порог в единицах MAD соответствует смыслу «редкий экстремум».
     */
    private fun sessionRobustSigma(
        obi: DoubleArray,
        sessions: IntArray,
        median: DoubleArray,
    ): DoubleArray {
        val sigma = DoubleArray(obi.size)
        for (s in sessions.distinct()) {
            val first = obi.indices.first { sessions[it] == s }
            val centre = median[first]
            val deviations =
                obi.indices
                    .filter { sessions[it] == s }
                    .map { abs(obi[it] - centre) }
                    .sorted()
            val mad = median(deviations)
            val sd = (1.4826 * mad).coerceAtLeast(1e-6)
            for (i in obi.indices) {
                if (sessions[i] == s) sigma[i] = sd
            }
        }
        return sigma
    }

    private fun median(sortedOrNot: List<Double>): Double {
        if (sortedOrNot.isEmpty()) return 0.0
        val sorted = sortedOrNot.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun shuffledWithinSessions(
        obi: DoubleArray,
        sessions: IntArray,
    ): DoubleArray {
        val out = obi.copyOf()
        val random = Random(20241001)
        for (s in sessions.distinct()) {
            val idx = obi.indices.filter { sessions[it] == s }
            val values = idx.map { obi[it] }
            Collections.shuffle(values, random)
            idx.forEachIndexed { position, i -> out[i] = values[position] }
        }
        return out
    }

    /** Число непрерывных эпизодов хвоста: реальный размер выборки, а не число точек. */
    private fun episodes(selected: BooleanArray): Int {
        var count = 0
        var previous = false
        for (flag in selected) {
            if (flag && !previous) count++
            previous = flag
        }
        return count
    }

    private fun forwardExitPrice(
        buckets: List<MoexOrderLogParser.BboBucket>,
        trades: List<MoexOrderLogParser.TradeTick>,
        horizonMs: Long,
    ): DoubleArray {
        val out = DoubleArray(buckets.size) { Double.NaN }
        var cursor = 0
        for (i in buckets.indices) {
            val stamp = epochNanos(buckets[i].time.plusNanos(horizonMs * 1_000_000))
            while (cursor < trades.size && epochNanos(trades[cursor].time) < stamp) cursor++
            if (cursor >= trades.size) break
            out[i] = trades[cursor].price.toDouble()
        }
        return out
    }

    /** Индекс последнего наблюдения стакана с временем не позже `t + horizon`. */
    private fun bookIndexAtOrBefore(
        buckets: List<MoexOrderLogParser.BboBucket>,
        horizonMs: Long,
    ): IntArray {
        val out = IntArray(buckets.size) { -1 }
        var cursor = 0
        for (i in buckets.indices) {
            val stamp = epochNanos(buckets[i].time.plusNanos(horizonMs * 1_000_000))
            while (cursor < buckets.size && epochNanos(buckets[cursor].time) <= stamp) cursor++
            out[i] = cursor - 1
        }
        return out
    }

    private fun trailingExitPrice(
        buckets: List<MoexOrderLogParser.BboBucket>,
        trades: List<MoexOrderLogParser.TradeTick>,
        horizonMs: Long,
    ): DoubleArray {
        val out = DoubleArray(buckets.size) { Double.NaN }
        var cursor = 0
        for (i in buckets.indices) {
            val stamp = epochNanos(buckets[i].time.minusNanos(horizonMs * 1_000_000))
            while (cursor < trades.size && epochNanos(trades[cursor].time) <= stamp) cursor++
            if (cursor >= trades.size) break
            out[i] = trades[cursor].price.toDouble()
        }
        return out
    }

    /**
     * Блочный бутстрэп условного среднего.
     *
     * Блоки режут исходный ряд по времени, суммы внутри блока считаются только по
     * отобранным хвостовым наблюдениям: так сохраняется кластеризация (экстремум
     * OBI держится десятки секунд) и интервал не схлопывается, как было бы при
     * бутстрэпе по отфильтрованному ряду.
     */
    private fun blockBootstrapCi(
        values: DoubleArray,
        selected: BooleanArray,
        blockSize: Int,
    ): Pair<Double, Double> {
        if (blockSize < 2 || values.size < blockSize * 10) return 0.0 to 0.0
        val blocks = values.size / blockSize
        val blockSum = DoubleArray(blocks)
        val blockCount = IntArray(blocks)
        for (b in 0 until blocks) {
            var sum = 0.0
            var count = 0
            for (i in b * blockSize until (b + 1) * blockSize) {
                if (!selected[i] || values[i].isNaN()) continue
                sum += values[i]
                count++
            }
            blockSum[b] = sum
            blockCount[b] = count
        }
        val random = Random(13)
        val out = ArrayList<Double>(RESAMPLES)
        repeat(RESAMPLES) {
            var sum = 0.0
            var count = 0
            repeat(blocks) {
                val b = random.nextInt(blocks)
                sum += blockSum[b]
                count += blockCount[b]
            }
            out += if (count > 0) sum / count else 0.0
        }
        out.sort()
        return out[(RESAMPLES * 0.025).toInt()] to out[(RESAMPLES * 0.975).toInt()]
    }

    /**
     * Размер блока блочного бутстрэпа: не менее 5 горизонтов, иначе перекрывающиеся
     * окна переходят между блоками и интервал оказывается слишком узким. Та же
     * логика и с теми же числами, что в [MoexOrderLogMicrostructureSignalTest].
     */
    private fun blockSize(
        n: Int,
        horizonMs: Long,
    ): Int {
        val perHorizon = (horizonMs * 5 / MEDIAN_STEP_MS).toInt().coerceAtLeast(1)
        return maxOf(n / 400, perHorizon).coerceAtLeast(1)
    }

    private fun mean(
        values: DoubleArray,
        selected: BooleanArray,
        from: Int = 0,
        until: Int = values.size,
    ): Double {
        var sum = 0.0
        var count = 0
        for (i in from until minOf(until, values.size)) {
            if (!selected[i] || values[i].isNaN()) continue
            sum += values[i]
            count++
        }
        return if (count > 0) sum / count else 0.0
    }

    private fun coverage(values: DoubleArray): Double = values.count { !it.isNaN() }.toDouble() / values.size

    private fun epochNanos(time: LocalDateTime): Long = time.toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L + time.nano
}

private fun fmt(v: Double): String = "%.3f".format(v)
