package com.trading.bot.marketdata

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertTrue

/**
 * Исследовательский стенд: предсказывает ли OBI/microprice движение цены на
 * реальном бесплатном дне MOEX Типа B (2024-10-01, CNYRUBF).
 *
 * Зачем стенд именно такой. Покупка платного архива имеет смысл только если
 * восстановленный стакан несёт информацию о будущей цене. Проверять это на
 * секундном агрегате бессмысленно: OBI и microprice живут на горизонте
 * миллисекунд, а не секунд. Поэтому здесь `bucketMillis = 1` — состояние
 * стакана после каждого MOMENT (MOMENT в файле Типа B содержит миллисекунды,
 * `yyyyMMddHHmmssSSS`).
 *
 * Метод. Для каждого наблюдения стакана в момент `t`:
 * - mid(t) = (bid + ask) / 2 — цена, которую видно в момент `t`;
 * - forward-return на горизонте H = (первая сделка с временем >= t + H − mid) / mid;
 * - признаки: OBI и отклонение microprice от mid в bps.
 *
 * Проверяются корреляция признака с forward-return, направление
 * (`знак OBI` против `знак forward-return`) и, главное, устойчивость: те же
 * метрики по половинам дня и по сессиям. Метрика, которая держится только на
 * одной половине дня, — шум.
 *
 * Контроль: перемешанный OBI (`placebo`). Если перемешивание даёт тот же
 * результат, что настоящий OBI, то связь создана не признаком, а устройством
 * расчёта.
 *
 * Тест печатает измерения, но не утверждает наличие edge: вывод о пригодности
 * покупки делается в `docs/22-paid-market-data.md` по совокупности замеров, а
 * проверяются здесь только механические инварианты (монотонность времени,
 * положительный спред, разделение на базис и placebo).
 */
class MoexOrderLogMicrostructureSignalTest {
    private val sampleDir = File("data/samples/moex_orderlog_20241001")

    private companion object {
        /** Повторов блочного бутстрэпа: больше 1000 уже не влияет на 95% CI. */
        const val RESAMPLES = 1000

        /**
         * Медианный шаг между наблюдениями стакана, мс (измерен на реальном
         * дне: p50 = 70 мс). Используется для перевода горизонта в число
         * наблюдений при выборе длины блока.
         */
        const val MEDIAN_STEP_MS = 70.0
    }

    @Test
    fun `obi and microprice are compared against future price on the real sample day`() {
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

        println("=== MOEX 2024-10-01 CNYRUBF, разрешение 1 мс ===")
        println("стакан: ${buckets.size} наблюдений, сделки: ${trades.size}, сессии: ${stats.sessions}")
        println(stats)

        assertTrue(buckets.size > 10_000, "миллисекундный стакан должен быть плотным: ${buckets.size}")
        assertTrue(trades.isNotEmpty())

        // Инварианты данных, на которые опирается всё измерение ниже.
        assertTrue(
            buckets.zipWithNext().all { (a, b) -> a.time <= b.time },
            "наблюдения стакана строго не убывают по времени",
        )
        assertTrue(
            trades.zipWithNext().all { (a, b) -> a.time <= b.time },
            "сделки не убывают по времени",
        )
        assertTrue(
            buckets.all { it.bid < it.ask },
            "восстановленный стакан не скрещён ни на одном наблюдении",
        )

        val obi = buckets.map { it.obi!!.toDouble() }
        val microDev = buckets.map { it.micropriceDeviationBps!!.toDouble() }
        val mid = buckets.map { (it.bid.toDouble() + it.ask.toDouble()) / 2.0 }

        // Разрешение исходных данных: без этого любой вывод о горизонте
        // бессмысленен.
        val deltas =
            buckets.zipWithNext().map { (a, b) -> Duration.between(a.time, b.time).toNanos() / 1_000_000.0 }
        println(
            "шаг между наблюдениями стакана, мс: p50=${percentile(deltas, 0.5)} " +
                "p90=${percentile(deltas, 0.9)} p99=${percentile(deltas, 0.99)}",
        )

        val midpoint = buckets.size / 2

        for (horizonMs in listOf(250L, 1000L, 5000L, 30_000L)) {
            val forward = forwardReturnsBps(buckets, trades, mid, horizonMs)
            val trailing = trailingReturnsBps(buckets, trades, mid, horizonMs)
            if (forward.size < 500 || trailing.size < forward.size) {
                println("H=${horizonMs}мс: наблюдений ${forward.size}, пропуск (мало сделок)")
                continue
            }
            val trail = trailing.subList(0, forward.size)
            val upShare = forward.count { it > 0 }.toDouble() / forward.size
            println("--- горизонт $horizonMs мс, наблюдений ${forward.size}, доля роста=${fmt(upShare)} ---")
            println("    автокорреляция forward на лаге 1 = ${fmt(autocorrelation(forward))}")

            for ((label, feature) in listOf("OBI" to obi, "micropriceDev" to microDev)) {
                report(label, feature, forward, trail, midpoint, upShare, horizonMs)
            }
        }

        println("=== OK ===")
    }

    /**
     * Метрики признака против forward-return: корреляции, направление,
     * контроль на импульс, устойчивость по половинам дня и доверительный
     * интервал блочным бутстрэпом. Печатает, ничего не утверждая.
     */
    private fun report(
        label: String,
        feature: List<Double>,
        forward: List<Double>,
        trailing: List<Double>,
        midpoint: Int,
        upShare: Double,
        horizonMs: Long,
    ) {
        val xs = feature
        // Признак, forward- и trailing-ряды могут отличаться по длине: конец
        // дня теряет наблюдения, у которых нет сделки на горизонте. Все
        // метрики считаются по общей длине, иначе индексы выйдут за границу.
        val n = minOf(xs.size, forward.size, trailing.size)
        val pearsonR = correlation(xs, forward)
        val spearman = correlation(rank(xs), rank(forward))
        val hit = forward.indices.count { i -> if (xs[i] > 0) forward[i] > 0 else forward[i] < 0 }.toDouble() / n

        val firstHalf = correlation(xs.take(midpoint), forward.take(midpoint))
        val secondHalf = correlation(xs.drop(midpoint), forward.drop(midpoint))

        // Ловушка импульса. OBI может коррелировать с будущим движением
        // просто потому, что коррелирует с прошедшим: тот же поток заявок
        // сдвинул и стакан, и цену. Если так, стакано��ный признак ничего не
        // добавляет к momentum. Частичная корреляция исключает этот вклад.
        val pastR = correlation(xs, trailing)
        val fwdPast = correlation(forward, trailing)
        val partial =
            if (abs(fwdPast) < 0.999) {
                (pearsonR - pastR * fwdPast) / sqrt((1.0 - pastR * pastR) * (1.0 - fwdPast * fwdPast))
            } else {
                Double.NaN
            }

        // Placebo: тот же расчёт на перемешанном признаке. Сигнал, который
        // не отличается от рандома, — не сигнал.
        val shuffled = xs.toMutableList()
        java.util.Collections.shuffle(shuffled, Random(20241001))
        val placebo = correlation(shuffled, forward)

        val ci = blockBootstrapCi(xs, forward, blockSize(n, horizonMs))

        // Решающая для покупки величина: перевешивает ли эффект издержки.
        // Корреляция сама по себе не торгуется — нужен условный средний
        // forward-return по знаку признака, то есть доход стратегии «вход по
        // знаку OBI, выход через горизонт». CNYRUBF: спред 0.75…9.1 bps,
        // комиссия 1.5 ₽/контракт/сторона + проскальзывание ~1 bp, поэтому
        // порог круговой издержки — единицы bps на сделку.
        var signedSum = 0.0
        var wins = 0
        for (i in 0 until n) {
            if (xs[i] == 0.0) continue
            val position = if (xs[i] > 0) 1.0 else -1.0
            signedSum += position * forward[i]
            if (position * forward[i] > 0) wins++
        }
        val edgeBps = signedSum / n
        val edgeCi = blockBootstrapMeanCi(xs, forward, blockSize(n, horizonMs))

        println(
            "  $label: pearson=${fmt(pearsonR)} spearman=${fmt(spearman)} " +
                "hit=${fmt(hit)} (базис=${fmt(upShare)}) placebo=${fmt(placebo)}",
        )
        println(
            "    контроль импульса: corr(признак, прошлое)=${fmt(pastR)} " +
                "частичная corr с будущим=${fmt(partial)}",
        )
        println("    блочный бутстрэп 95% CI: [${fmt(ci.first)}, ${fmt(ci.second)}]")
        println(
            "    доход «вход по знаку признака»: ${fmt(edgeBps)} bps/наблюдение, " +
                "доля прибыльных ${fmt(wins.toDouble() / n)}, бутстрэп 95% CI [${fmt(edgeCi.first)}, ${fmt(edgeCi.second)}]",
        )
        println(
            "    по половинам дня: 1-я=${fmt(firstHalf)} 2-я=${fmt(secondHalf)} " +
                "→ ${if (firstHalf * secondHalf > 0) "знак совпадает" else "ЗНАК РАЗНЫХ ЗНАКОВ"}",
        )
    }

    /**
     * Блочный бутстрэп для условного среднего дохода стратегии «вход по знаку
     * признака». Отличается от [blockBootstrapCi] тем, что агрегирует не
     * корреляцию, а среднее знакового forward-return.
     */
    private fun blockBootstrapMeanCi(
        xs: List<Double>,
        ys: List<Double>,
        blockSize: Int,
    ): Pair<Double, Double> {
        if (blockSize < 2 || xs.size < blockSize * 10) return 0.0 to 0.0
        val random = Random(11)
        val blocks = xs.size / blockSize
        val values = ArrayList<Double>(RESAMPLES)
        repeat(RESAMPLES) {
            var sum = 0.0
            var count = 0
            repeat(blocks) {
                val start = random.nextInt(blocks) * blockSize
                for (i in 0 until blockSize) {
                    val idx = start + i
                    if (xs[idx] == 0.0) continue
                    val position = if (xs[idx] > 0) 1.0 else -1.0
                    sum += position * ys[idx]
                    count++
                }
            }
            values += if (count > 0) sum / count else 0.0
        }
        values.sort()
        return values[(RESAMPLES * 0.025).toInt()] to values[(RESAMPLES * 0.975).toInt()]
    }

    /**
     * Размер блока блочного бутстрэпа.
     *
     * Блок обязан быть длиннее окна, иначе перекрывающиеся forward-окна
     * переходят между блоками и интервал оказывается слишком узким: на
     * горизонте 30 с блок в 165 наблюдений (~11 с) короче самого окна и
     * занижает неопределённость. Отсюда правило: не менее 5 горизонтов,
     * плюс нижняя граница, чтобы блоков осталось достаточно для 95% CI.
     */
    private fun blockSize(
        n: Int,
        horizonMs: Long,
    ): Int {
        val perHorizon = (horizonMs * 5 / MEDIAN_STEP_MS).toInt().coerceAtLeast(1)
        return maxOf(n / 400, perHorizon).coerceAtLeast(1)
    }

    /**
     * Доверительный интервал корреляции блочным бутстрэпом.
     *
     * Наивный доверительный интервал по n наблюдениям здесь неприменим:
     * наблюдения перекрываются по окнам и идут с шагом p50 = 70 мс, поэтому
     * эффективная выборка на порядки меньше n.
     */
    private fun blockBootstrapCi(
        xs: List<Double>,
        ys: List<Double>,
        blockSize: Int,
    ): Pair<Double, Double> {
        if (blockSize < 2 || xs.size < blockSize * 10) return 0.0 to 0.0
        val random = Random(7)
        val blocks = xs.size / blockSize
        val values = ArrayList<Double>(RESAMPLES)
        repeat(RESAMPLES) {
            val bx = ArrayList<Double>(blocks * blockSize)
            val by = ArrayList<Double>(blocks * blockSize)
            repeat(blocks) {
                val start = random.nextInt(blocks) * blockSize
                for (i in 0 until blockSize) {
                    bx += xs[start + i]
                    by += ys[start + i]
                }
            }
            values += correlation(bx, by)
        }
        values.sort()
        return values[(RESAMPLES * 0.025).toInt()] to values[(RESAMPLES * 0.975).toInt()]
    }

    private fun autocorrelation(series: List<Double>): Double {
        if (series.size < 3) return 0.0
        val m = series.average()
        var cov = 0.0
        var v = 0.0
        for (i in 1 until series.size) {
            cov += (series[i] - m) * (series[i - 1] - m)
        }
        for (value in series) v += (value - m) * (value - m)
        return if (v <= 0.0) 0.0 else cov / v
    }

    /**
     * Forward-return в bps для каждого наблюдения стакана: первая сделка не
     * раньше `t + horizon`, цена сравнивается с mid в момент `t`.
     *
     * Строгое `>=` и неравенство `t + H` исключают сделки, вызванные тем самым
     * обновлением стакана: они эндогенны и предсказывать ничего не могут.
     */
    private fun forwardReturnsBps(
        buckets: List<MoexOrderLogParser.BboBucket>,
        trades: List<MoexOrderLogParser.TradeTick>,
        mid: List<Double>,
        horizonMs: Long,
    ): List<Double> {
        val tradePrices = trades.map { it.price.toDouble() }
        val result = ArrayList<Double>(buckets.size)
        var cursor = 0
        for (i in buckets.indices) {
            val target = buckets[i].time.plusNanos(horizonMs * 1_000_000)
            // Курсор движется только вперёд: наблюдения отсортированы по времени.
            val stamp = target.toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L + target.nano
            while (cursor < trades.size && epochNanos(trades[cursor].time) < stamp) cursor++
            if (cursor >= trades.size) break
            val m = mid[i]
            if (m <= 0.0) continue
            result += (tradePrices[cursor] - m) / m * 10_000.0
        }
        return result
    }

    /**
     * Backward-return в bps: цена последней сделки не позже `t − horizon`
     * против mid в момент `t`. Нужен для контроля на импульс — признак,
     * объясняющийся прошлым движением, ничего не добавляет к прогнозу.
     */
    private fun trailingReturnsBps(
        buckets: List<MoexOrderLogParser.BboBucket>,
        trades: List<MoexOrderLogParser.TradeTick>,
        mid: List<Double>,
        horizonMs: Long,
    ): List<Double> {
        val tradePrices = trades.map { it.price.toDouble() }
        val result = ArrayList<Double>(buckets.size)
        var cursor = 0
        for (i in buckets.indices) {
            val from = buckets[i].time.minusNanos(horizonMs * 1_000_000)
            val stamp = from.toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L + from.nano
            while (cursor < trades.size && epochNanos(trades[cursor].time) <= stamp) cursor++
            if (cursor >= trades.size) break
            val m = mid[i]
            if (m <= 0.0) continue
            result += (m - tradePrices[cursor]) / m * 10_000.0
        }
        return result
    }

    private fun epochNanos(time: LocalDateTime): Long = time.toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L + time.nano

    private fun correlation(
        xs: List<Double>,
        ys: List<Double>,
    ): Double {
        val n = minOf(xs.size, ys.size)
        if (n < 2) return 0.0
        val mx = xs.take(n).average()
        val my = ys.take(n).average()
        var cov = 0.0
        var vx = 0.0
        var vy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            cov += dx * dy
            vx += dx * dx
            vy += dy * dy
        }
        if (vx <= 0.0 || vy <= 0.0) return 0.0
        return cov / sqrt(vx * vy)
    }

    private fun rank(values: List<Double>): List<Double> {
        val order = values.indices.sortedWith(compareBy { values[it] })
        val out = MutableList(values.size) { 0.0 }
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && values[order[j + 1]] == values[order[i]]) j++
            val avg = (i + j) / 2.0 + 1.0
            for (k in i..j) out[order[k]] = avg
            i = j + 1
        }
        return out
    }

    private fun percentile(
        values: List<Double>,
        q: Double,
    ): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun fmt(v: Double): String = "%.4f".format(v)
}
