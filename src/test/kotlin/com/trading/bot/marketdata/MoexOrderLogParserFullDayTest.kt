package com.trading.bot.marketdata

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ручной стенд парсера на полном бесплатном дне MOEX (2024-10-01, CNYRUBF).
 *
 * Запускается только если локально распакован срез из бесплатного образца:
 * `data/samples/moex_orderlog_20241001/20241001_CNYRUBF_fut_tick.csv` и
 * `..._fut_deal.csv` (каталог `data/` в `.gitignore`, поэтому в CI тест
 * пропускается через `assumeTrue`).
 *
 * Нужен не для покрытия, а чтобы сверить парсер с замерами, сделанными до
 * реализации: 216 653 строки котировок, 22 859 сделок, MOMENT от
 * 2024-09-30T18:58:02.963 до 2024-10-01T18:49:59.230.
 *
 * Обратите внимание: файл торгового дня `20241001` содержит и вечернюю сессию
 * следующих суток (18:49), поэтому «последний бакет около 10:00» — неверное
 * ожидание, граница определяется последним MOMENT источника.
 */
class MoexOrderLogParserFullDayTest {
    private val sampleDir = File("data/samples/moex_orderlog_20241001")

    @Test
    fun `parses the whole free sample day and matches the pre-implementation measurements`() {
        Assumptions.assumeTrue(
            sampleDir.resolve("20241001_CNYRUBF_fut_tick.csv").exists(),
            "полный срез бесплатного образца не распакован локально",
        )

        var firstBucket: MoexOrderLogParser.BboBucket? = null
        var lastBucket: MoexOrderLogParser.BboBucket? = null
        var maxQuoteCount = 0L
        var maxSpreadBps: BigDecimal? = null
        var minSpreadBps: BigDecimal? = null
        var nullObi = 0L
        var nullMicroprice = 0L
        var buckets = 0L
        val ticks = mutableListOf<MoexOrderLogParser.TradeTick>()

        val stats =
            MoexOrderLogParser.parseDay(
                path = sampleDir,
                ticker = "CNYRUBF",
                bucketSink = { bucket ->
                    if (firstBucket == null) firstBucket = bucket
                    lastBucket = bucket
                    buckets++
                    if (bucket.quoteCount > maxQuoteCount) maxQuoteCount = bucket.quoteCount
                    bucket.spreadBps?.let { if (maxSpreadBps == null || it > maxSpreadBps) maxSpreadBps = it }
                    bucket.spreadBps?.let { if (minSpreadBps == null || it < minSpreadBps) minSpreadBps = it }
                    if (bucket.obi == null) nullObi++
                    if (bucket.microprice == null) nullMicroprice++
                },
                tickSink = { ticks.add(it) },
            )

        println("=== MOEX 2024-10-01 CNYRUBF, полный день ===")
        println(stats)
        println("ticks=${ticks.size} buckets=$buckets maxQuoteCountPerSecond=$maxQuoteCount")
        println("spread bps: min=$minSpreadBps max=$maxSpreadBps")
        println("nullObi=$nullObi nullMicroprice=$nullMicroprice")
        println("first=${firstBucket?.time} last=${lastBucket?.time}")

        // Замеры, сделанные до реализации парсера (docs/22, раздел 1.4).
        assertEquals(216_653L, stats.tickRows, "все строки котировок CNYRUBF должны быть разобраны")
        assertEquals(22_859L, stats.dealRows, "все сделки CNYRUBF должны быть разобраны")
        assertEquals(0L, stats.malformedTick, "в разобранном файле нет битых строк")
        assertEquals(0L, stats.malformedDeal)
        assertEquals(0L, stats.tickRowsForeignTicker, "в выборке только CNYRUBF")
        assertEquals(22_859, ticks.size)
        assertEquals(
            0L,
            ticks.map { it.dealId }.toSet().let { it.size.toLong() - ticks.size },
            "идентификаторы сделок уникальны",
        )

        // Ни одна строка источника не потеряна и не посчитана дважды: каждая
        // котировка либо агрессивная, либо учтена в исходе закрытия бакета.
        assertEquals(
            stats.tickRows,
            stats.quotesEmitted +
                stats.quotesDroppedOpening +
                stats.quotesDroppedSingleSided +
                stats.quotesDroppedCrossed +
                stats.quotesAggressive,
            "учёт исходов котировок должен сходиться с числом строк",
        )

        // Агрессивные заявки (исполнены сразу) — ожидаемо ~17% строк. Их
        // отсутствие означало бы, что фильтр перестал работать и стакан снова
        // скрещивается исполненными заявками.
        assertTrue(
            stats.quotesAggressive > 30_000L,
            "агрессивные заявки должны отфильтровываться, найдено ${stats.quotesAggressive}",
        )

        // Главная проверка реконструкции: скрещённых секунд должно быть мало.
        // Без агрессивной фильтрации их было 6829 из 17059 (40%), спред p50
        // упирался в десятки bps.
        val closed =
            stats.bboBuckets +
                stats.bboBucketsDroppedOpening +
                stats.bboBucketsSingleSided +
                stats.bboBucketsCrossed
        val crossedShare = stats.bboBucketsCrossed.toDouble() / closed
        assertTrue(closed > 0, "должен быть закрыт хотя бы один бакет")
        assertTrue(
            crossedShare < 0.02,
            "скрещённых бакетов должно быть меньше 2% (получено $crossedShare, ${stats.bboBucketsCrossed} из $closed)",
        )

        // Внутридневная сессия: стакан двусторонний, поэтому односторонних
        // закрытых бакетов на реальном дне быть не должно.
        assertEquals(
            0L,
            stats.bboBucketsSingleSided,
            "односторонние бакеты означают сломанный carry-forward",
        )

        // Диапазон времени: файл покрывает вечернюю сессию предыдущих суток,
        // дневную сессию и начало вечерней сессии следующих суток.
        //
        // Первый эмитированный бакет — 19:05, а не 18:58:02, и это особенность
        // источника, а не ошибка парсера: в 18:58:02 MOEX печатает стартовую
        // лестницу (85 строк, отбрасывается фильтром открытия), после чего в
        // потоке MOMENT нет ни одной строки до 19:05. В 18:58:03…19:04:59
        // обновлений стакана не было — значит и бакета с bookRows > 0 нет.
        val first = requireNotNull(firstBucket)
        val last = requireNotNull(lastBucket)
        assertTrue(
            first.time >= LocalDateTime.parse("2024-09-30T19:05:00") &&
                first.time <= LocalDateTime.parse("2024-09-30T19:06:00"),
            "начало $first",
        )
        assertTrue(
            last.time >= LocalDateTime.parse("2024-10-01T18:45:00") &&
                last.time <= LocalDateTime.parse("2024-10-01T18:50:00"),
            "конец $last",
        )
        assertTrue(stats.sessions >= 3, "файл покрывает несколько сессий: ${stats.sessions}")

        // Признаки должны быть заполнены практически везде: без размеров в стакане
        // OBI/microprice не считаются, и это должно быть видно в счётчиках.
        assertTrue(nullObi == 0L, "OBI посчитан во всех бакетах, иначе carry-forward сломан: $nullObi")
        assertTrue(nullMicroprice == 0L, "microprice посчитан во всех бакетах: $nullMicroprice")

        // Спред фьючерса CNYRUBF — единицы bps; сотни bps означали бы, что в
        // агрегат попал артефакт открытия сессии или агрессивная заявка.
        val maxSpread = requireNotNull(maxSpreadBps)
        val minSpread = requireNotNull(minSpreadBps)
        assertTrue(
            maxSpread < BigDecimal(20),
            "максимальный спред должен быть в пределах десятков bps, получено $maxSpread",
        )
        assertTrue(
            minSpread > BigDecimal("0.10"),
            "минимальный спред должен быть положительным, получено $minSpread",
        )
        println("=== OK ===")
    }
}
