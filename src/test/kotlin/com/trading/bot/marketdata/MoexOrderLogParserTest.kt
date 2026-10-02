package com.trading.bot.marketdata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Тесты парсера на реальных фрагментах бесплатного образца MOEX за 2024-10-01
 * (`src/test/resources/moex_orderlog/`, 5000 строк тиков + 2000 сделок CNYRUBF).
 *
 * Фикстуры вырезаны из настоящего архива без правки содержимого, поэтому
 * ожидания здесь — про наблюдаемое поведение источника, а не про выдуманные данные.
 */
class MoexOrderLogParserTest {
    private val fixturesDir: File = File("src/test/resources/moex_orderlog")

    private class Collected {
        val buckets = mutableListOf<MoexOrderLogParser.BboBucket>()
        val ticks = mutableListOf<MoexOrderLogParser.TradeTick>()
        var stats: MoexOrderLogParser.ParseStats? = null
    }

    private fun parse(
        ticker: String = "CNYRUBF",
        bucketMillis: Long = 1000,
        openSessionSkipMs: Long = 0,
        dir: File = fixturesDir,
    ): Collected {
        val collected = Collected()
        collected.stats =
            MoexOrderLogParser.parseDay(
                path = dir,
                ticker = ticker,
                bucketMillis = bucketMillis,
                openSessionSkipMs = openSessionSkipMs,
                bucketSink = { collected.buckets.add(it) },
                tickSink = { collected.ticks.add(it) },
            )
        return collected
    }

    @Test
    fun `parses every CNYRUBF tick and deal row of the fixture without malformed rows`() {
        val result = parse()

        val stats = requireNotNull(result.stats)
        assertEquals(0L, stats.malformedTick, "fixture has no malformed tick rows")
        assertEquals(0L, stats.malformedDeal, "fixture has no malformed deal rows")
        assertEquals(0L, stats.tickRowsForeignTicker, "fixture must contain a single ticker")
        assertEquals(0L, stats.dealRowsForeignTicker, "fixture must contain a single ticker")
        assertEquals(5000L, stats.tickRows)
        assertEquals(2000L, stats.dealRows)
    }

    @Test
    fun `each quote row lands in exactly one second bucket`() {
        val stats = requireNotNull(parse().stats)

        // Ни одна строка не теряется и не учитывается дважды: сумма котировок по
        // всем исходам (агрессивная заявка либо закрытие бакета) равна числу
        // разобранных строк тиков.
        val accounted =
            stats.quotesEmitted +
                stats.quotesDroppedOpening +
                stats.quotesDroppedSingleSided +
                stats.quotesDroppedCrossed +
                stats.quotesAggressive
        assertEquals(stats.tickRows, accounted, "every tick row must be accounted exactly once")
        assertTrue(stats.bboBuckets > 100, "fixture must span hundreds of seconds, got ${stats.bboBuckets}")
    }

    @Test
    fun `aggressive orders are excluded from the book instead of crossing it`() {
        // Заявка, цена которой пересекает уже известную противоположную сторону,
        // исполняется сразу и в стакане не остаётся. Если такие строки включать в
        // агрегат по MOMENT, стакан систематически скрещён: на полном дне без
        // фильтра 6829 из 17059 секунд имели bid >= ask.
        val stats = requireNotNull(parse().stats)

        assertTrue(
            stats.quotesAggressive > 0L,
            "fixture must contain aggressive orders, got ${stats.quotesAggressive}",
        )
        assertTrue(
            stats.bboBucketsCrossed.toDouble() / (stats.bboBuckets + stats.bboBucketsCrossed) < 0.02,
            "crossed buckets must be a rare artefact, got ${stats.bboBucketsCrossed}",
        )
    }

    @Test
    fun `best bid and ask are max B and min S of the moment, not the last printed row`() {
        // Первый MOMENT фикстуры несёт 34 bid-уровня (37 строк на две стороны).
        // Берется максимум, поэтому первый бакет обязан иметь bid 12.995, а не
        // 11.111 — строку, напечатанную первой. Это регрессия на баг «последняя
        // строка момента вместо лучшей».
        val first = parse().buckets.first()

        assertEquals(0, BigDecimal("13.219").compareTo(first.bid), "best bid of 18:58:02 must be 13.219")
        assertEquals(0, BigDecimal("13.224").compareTo(first.ask), "best ask of 18:58:02 must be 13.224")
    }

    @Test
    fun `bbo buckets are never crossed and always have positive quote count`() {
        val result = parse()

        assertTrue(result.buckets.isNotEmpty(), "expected reconstructed BBO buckets")
        result.buckets.forEach { bucket ->
            assertTrue(
                bucket.bid < bucket.ask,
                "crossed or empty BBO at ${bucket.time}: bid=${bucket.bid} ask=${bucket.ask}",
            )
            assertTrue(bucket.quoteCount > 0, "quoteCount must be positive at ${bucket.time}")
        }
    }

    @Test
    fun `bbo buckets are strictly ordered and unique per second`() {
        val buckets = parse().buckets

        buckets.zipWithNext { previous, next ->
            assertTrue(next.time > previous.time, "buckets must be strictly increasing: ${previous.time} -> ${next.time}")
        }
    }

    @Test
    fun `missing side is carried forward so spread stays defined across moments`() {
        // Первый MOMENT файла содержит только B-заявки, второй — тоже только B.
        // Ask появляется позже; без carry-forward спред был бы null на всех
        // бакетах до этого момента.
        val buckets = parse().buckets

        val firstBucket = buckets.first()
        assertNotNull(firstBucket.ask, "ask must be carried forward into the very first bucket")
        assertNotNull(firstBucket.spreadBps, "spread must be computable for the very first bucket")
    }

    @Test
    fun `microstructure features match the shared domain formulas`() {
        val bucket = parse().buckets.first()

        val half = bucket.bid.add(bucket.ask).divide(BigDecimal(2), 8, java.math.RoundingMode.HALF_UP)
        val expectedSpread =
            bucket.ask
                .subtract(bucket.bid)
                .divide(half, 8, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal(10_000))
        assertEquals(0, expectedSpread.compareTo(bucket.spreadBps))
        assertNotNull(bucket.micropriceDeviationBps)
        assertTrue(
            bucket.micropriceDeviationBps!!.abs() < BigDecimal(10_000),
            "deviation must stay inside the book width, got ${bucket.micropriceDeviationBps}",
        )
    }

    @Test
    fun `opening artifact is dropped when skip is enabled`() {
        val withSkip = parse(openSessionSkipMs = 1_000).buckets
        val withoutSkip = parse(openSessionSkipMs = 0).buckets

        assertTrue(
            withSkip.size < withoutSkip.size,
            "skipping the first second must drop buckets: ${withSkip.size} vs ${withoutSkip.size}",
        )
    }

    @Test
    fun `deals are parsed with direction and identifiers`() {
        val ticks = parse().ticks

        assertTrue(ticks.isNotEmpty(), "expected trades from *_fut_deal.csv")
        ticks.forEach { tick ->
            assertEquals("CNYRUBF", tick.ticker)
            assertTrue(tick.dealId > 0, "dealId must be positive: ${tick.dealId}")
            assertTrue(tick.price.signum() > 0, "price must be positive: ${tick.price}")
            assertTrue(tick.volume > 0, "volume must be positive: ${tick.volume}")
            assertTrue(tick.direction == "B" || tick.direction == "S", "unexpected direction ${tick.direction}")
        }
    }

    @Test
    fun `deal ids are unique so the ticks table upsert stays idempotent`() {
        val ticks = parse().ticks

        val duplicates = ticks.groupBy { it.dealId }.filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty(), "duplicate dealId would break ON CONFLICT: ${duplicates.keys.take(5)}")
    }

    @Test
    fun `unknown ticker yields no rows and no buckets`() {
        val result = parse(ticker = "NOSUCHTICKER")

        assertTrue(result.buckets.isEmpty(), "no BBO for a foreign ticker")
        assertTrue(result.ticks.isEmpty(), "no trades for a foreign ticker")
        assertEquals(0L, requireNotNull(result.stats).malformedTick)
    }

    @Test
    fun `wider bucket aggregates quotes into fewer seconds`() {
        val oneSecond = parse(bucketMillis = 1000)
        val fiveSeconds = parse(bucketMillis = 5000)

        assertTrue(
            fiveSeconds.buckets.size < oneSecond.buckets.size,
            "5s buckets must be coarser: ${fiveSeconds.buckets.size} vs ${oneSecond.buckets.size}",
        )
        assertEquals(
            oneSecond.buckets.sumOf { it.quoteCount } +
                requireNotNull(oneSecond.stats).quotesDroppedSingleSided +
                requireNotNull(oneSecond.stats).quotesDroppedCrossed,
            fiveSeconds.buckets.sumOf { it.quoteCount } +
                requireNotNull(fiveSeconds.stats).quotesDroppedSingleSided +
                requireNotNull(fiveSeconds.stats).quotesDroppedCrossed,
            "re-aggregation must preserve the total number of quote rows",
        )
    }

    @Test
    fun `missing both sides at session start is not emitted`() {
        val result = parse()

        // Первый бакет может иметь одну сторону только если carry-forward не сработал;
        // в реальном файле вторая сторона появляется в том же или следующем MOMENT.
        val stats = requireNotNull(result.stats)
        assertEquals(0L, stats.bboBucketsSingleSided, "single-sided buckets must not be emitted")
    }

    @Test
    fun `parser is deterministic across repeated runs`() {
        val first = parse().buckets.map { it.time to it.quoteCount }
        val second = parse().buckets.map { it.time to it.quoteCount }

        assertEquals(first, second)
    }

    @Test
    fun `parse stats render without infinite recursion`() {
        // Регрессия: `toString()` интерполировал сам объект (`$this`) и падал
        // с StackOverflowError при первом же выводе статистики.
        val text = requireNotNull(parse().stats).toString()

        assertTrue(text.startsWith("MoexOrderLogParser["), "unexpected rendering: $text")
        assertTrue(text.contains("tickRows=5000"), "counters must be visible: $text")
        assertTrue(text.contains("quotesEmitted="), "quote accounting must be visible: $text")
    }

    @Test
    fun `unsupported source format fails loudly instead of silently loading nothing`() {
        val notAnArchive = File.createTempFile("orderlog", ".csv")
        try {
            val error =
                runCatching {
                    MoexOrderLogParser.parseDay(
                        path = notAnArchive,
                        ticker = "CNYRUBF",
                        bucketSink = {},
                        tickSink = {},
                    )
                }.exceptionOrNull()
            assertNotNull(error, "bare .csv must be rejected, not silently ignored")
            assertTrue(
                error is IllegalArgumentException,
                "expected IllegalArgumentException, got ${requireNotNull(error).javaClass.name}",
            )
        } finally {
            notAnArchive.delete()
        }
    }

    @Test
    fun `bucket start is aligned to the requested width`() {
        val buckets = parse(bucketMillis = 5000).buckets

        buckets.forEach { bucket ->
            val epochSecond = bucket.time.atZone(java.time.ZoneId.of("Europe/Moscow")).toEpochSecond()
            assertEquals(0L, Math.floorMod(epochSecond, 5L), "bucket start must be aligned: ${bucket.time}")
        }
    }

    @Test
    fun `non positive bucket width is rejected before reading the file`() {
        // Без проверки bucketMillis = 0 упал бы ArithmeticException внутри
        // горячего цикла, а отрицательная ширина молча склеивала соседние
        // моменты. Ошибка обязана возникать на входе, до чтения файла.
        listOf(0L, -1L).forEach { width ->
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    MoexOrderLogParser.parseDay(
                        path = File("src/test/resources/moex_orderlog/отсутствующий-файл.csv"),
                        ticker = "CNYRUBF",
                        bucketMillis = width,
                        bucketSink = { },
                        tickSink = { },
                    )
                }
            assertTrue(
                error.message!!.contains("bucketMillis"),
                "сообщение должно называть параметр: ${error.message}",
            )
        }
    }

    @Test
    fun `first and last timestamps fall inside the fixture trading day`() {
        val result = parse()

        val bucketTimes = result.buckets.map { it.time }
        assertTrue(bucketTimes.isNotEmpty())
        // Вечерняя сессия 2024-10-01 может нести MOMENT предыдущих суток,
        // поэтому допускаем 2024-09-30..2024-10-02.
        val earliest = LocalDateTime.of(2024, 9, 30, 0, 0)
        val latest = LocalDateTime.of(2024, 10, 2, 23, 59)
        assertTrue(bucketTimes.all { it >= earliest && it <= latest }, "unexpected bucket range")
    }
}
