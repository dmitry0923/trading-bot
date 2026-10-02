package com.trading.bot.integration

import com.trading.bot.marketdata.MoexOrderLogLoader
import com.trading.bot.repository.HistoricalBboRepository
import com.trading.bot.repository.TradeTickRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * End-to-end стенд загрузки: реальный бесплатный день MOEX Типа B
 * (2024-10-01, CNYRUBF) проходит путь «файл на диске → парсер → R2DBC →
 * TimescaleDB», после чего строки читаются обратно.
 *
 * Зачем именно этот тест. До него persistence проверялся только синтетикой:
 * подтверждалось, что репозитории умеют писать и что схема принимает строки,
 * но не подтверждалось, что загрузчик вообще доводит реальный файл до БД. На
 * бесплатном дне это единственная возможность поймать дефекты масштаба
 * (батчинг, 10 МБ файла, 16 857 ба��етов, 22 859 сделок) до покупки платного
 * архива, где ошибка стоит денег.
 *
 * Запускается только при локально распакованном срезе
 * `data/samples/moex_orderlog_20241001/` (каталог `data/` в `.gitignore`,
 * поэтому в CI пропускается через `assumeTrue`).
 */
@Tag("integration")
class MoexOrderLogLoaderEndToEndIntegrationTest : AbstractTestContainerTest() {
    private val sampleDir = File("data/samples/moex_orderlog_20241001")

    @Autowired
    lateinit var loader: MoexOrderLogLoader

    @Autowired
    lateinit var bboRepository: HistoricalBboRepository

    @Autowired
    lateinit var tickRepository: TradeTickRepository

    @Autowired
    lateinit var databaseClient: DatabaseClient

    @BeforeEach
    fun cleanup() {
        runBlocking {
            databaseClient
                .sql("DELETE FROM orderbook_bbo")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            databaseClient
                .sql("DELETE FROM ticks")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    @Test
    fun `real free sample day lands in the database intact`() {
        assumeTrue(
            sampleDir.resolve("20241001_CNYRUBF_fut_tick.csv").exists(),
            "полный срез бесплатного образца не распакован локально",
        )

        val started = System.currentTimeMillis()
        val result = runBlocking { loader.load(sampleDir, "CNYRUBF") }
        val elapsedMs = System.currentTimeMillis() - started

        val stats = result.stats
        println(
            "=== E2E загрузка 2024-10-01 CNYRUBF за ${elapsedMs / 1000.0} с ===\n" +
                "bbo=$result.bboBuckets/${stats.bboBuckets} ticks=$result.ticks/${stats.dealRows}\n" +
                "tickRows=${stats.tickRows} dealRows=${stats.dealRows} sessions=${stats.sessions}\n" +
                "aggressive=${stats.quotesAggressive} crossedMoments=${stats.momentsCrossed} " +
                "malformed=${stats.malformedTick + stats.malformedDeal}",
        )

        // 1. Ни одна сделка и ни один бакет не потеряны на пути в БД: счётчики
        //    загрузчика совпадают с результатом парсера.
        assertEquals(stats.dealRows.toInt(), result.ticks, "все сделки дня записаны в ticks")
        assertEquals(stats.bboBuckets.toInt(), result.bboBuckets, "все BBO-бакеты записаны в orderbook_bbo")
        assertEquals(22_859, result.ticks, "ожидаемое число сделок бесплатного дня")
        assertEquals(16_857L, result.bboBuckets.toLong(), "ожидаемое число BBO-бакетов")
        assertEquals(0L, stats.malformedTick + stats.malformedDeal, "битых строк нет")

        // 2. Фактические строки в БД совпадают с тем, что сообщил загрузчик.
        val ticksInDb = count("ticks")
        val bboInDb = count("orderbook_bbo")
        assertEquals(result.ticks, ticksInDb, "строк в ticks столько же, сколько записал загрузчик")
        assertEquals(result.bboBuckets, bboInDb, "строк в orderbook_bbo столько же")

        // 3. Границы времени совпадают с замерами парсера: файл торгового дня
        //    начинается вечерней сессией 2024-09-30 и уходит в вечер 2024-10-01.
        val (firstBbo, lastBbo) = bboBounds()
        println("bbo: $firstBbo .. $lastBbo")
        assertTrue(
            firstBbo >= LocalDateTime.parse("2024-09-30T19:05:00"),
            "первый бакет $firstBbo раньше ожидаемого",
        )
        assertTrue(
            lastBbo <= LocalDateTime.parse("2024-10-01T18:50:00"),
            "последний бакет $lastBbo позже ожидаемого",
        )

        // 4. Признаки записались вместе с котировками, а не «нулями»:
        //    без размеров в стакане OBI/microprice не считаются.
        val nullObi = scalar("SELECT count(*) FROM orderbook_bbo WHERE obi IS NULL").toInt()
        val nullMicroprice = scalar("SELECT count(*) FROM orderbook_bbo WHERE microprice IS NULL").toInt()
        assertEquals(0, nullObi, "OBI посчитан во всех бакетах")
        assertEquals(0, nullMicroprice, "microprice посчитан во всех бакетах")

        // 5. `price` у исторического BBO остаётся NULL по всей таблице: цена
        //    исполнения берётся point-in-time из ticks, а не из котировки.
        val nonNullPrice = scalar("SELECT count(*) FROM orderbook_bbo WHERE price IS NOT NULL").toInt()
        assertEquals(0, nonNullPrice, "price в orderbook_bbo всегда NULL")

        // 6. Спред записанных бакетов остаётся в разумных границах — та же
        //    проверка, что и на парсере, но уже на данных, дошедших до БД.
        val maxSpread = scalar("SELECT max(spread_bps) FROM orderbook_bbo")
        val minSpread = scalar("SELECT min(spread_bps) FROM orderbook_bbo")
        println("спред в БД: min=$minSpread max=$maxSpread")
        assertTrue(BigDecimal(maxSpread) < BigDecimal(20), "максимальный спред $maxSpread")
        assertTrue(BigDecimal(minSpread) > BigDecimal("0.10"), "минимальный спред $minSpread")

        // 7. Сделки читаются обратно репозиторием, и диапазон покрывает день.
        val from = LocalDateTime.parse("2024-09-30T00:00:00")
        val to = LocalDateTime.parse("2024-10-02T00:00:00")
        val loadedTicks = runBlocking { tickRepository.findByTickerAndTimeBetween("CNYRUBF", from, to) }
        assertEquals(result.ticks, loadedTicks.size, "репозиторий читает весь день")
        assertTrue(loadedTicks.all { it.ticker == "CNYRUBF" }, "только CNYRUBF")
        assertTrue(loadedTicks.all { it.direction == "B" || it.direction == "S" }, "направление всегда B или S")
        assertTrue(loadedTicks.zipWithNext().all { (a, b) -> !a.time.isAfter(b.time) }, "сделки отсортированы по времени")

        // 8. Повторная загрузка того же дня ничего не дублирует — это и есть
        //    требование к докачке: ретрай не должен ни удваивать строки, ни
        //    перетирать уже записанные.
        val repeat = runBlocking { loader.load(sampleDir, "CNYRUBF") }
        assertEquals(0, repeat.ticks, "повторная загрузка не вставляет сделки заново")
        assertEquals(0, repeat.bboBuckets, "повторная загрузка не вставляет бакеты заново")
        assertEquals(result.ticks, count("ticks"), "число сделок в БД не изменилось")
        assertEquals(result.bboBuckets, count("orderbook_bbo"), "число бакетов в БД не изменилось")

        println("=== E2E OK ===")
    }

    @Test
    fun `loader is a no-op on an empty directory instead of inventing rows`() {
        val empty = File(System.getProperty("java.io.tmpdir"), "moex-empty-${System.nanoTime()}")
        empty.mkdirs()

        val result = runBlocking { loader.load(empty, "CNYRUBF") }

        assertNotNull(result)
        assertEquals(0, result.ticks)
        assertEquals(0, result.bboBuckets)
        assertEquals(0, count("ticks"))
        assertEquals(0, count("orderbook_bbo"))
    }

    private fun count(table: String): Int = scalar("SELECT count(*) FROM $table").toInt()

    /**
     * Скаляр запроса строкой. R2DBC отдаёт `count(*)` как `Long`, агрегаты
     * NUMERIC — как `BigDecimal`, поэтому значение приводится к тексту, а
     * разбирается вызывающим кодом.
     */
    private fun scalar(sql: String): String =
        runBlocking {
            databaseClient
                .sql(sql)
                .map { row -> row.get(0).toString() }
                .one()
                .awaitSingle()
        }

    private fun bboBounds(): Pair<LocalDateTime, LocalDateTime> =
        runBlocking {
            val (lo, hi) =
                databaseClient
                    .sql("SELECT min(ts) AS lo, max(ts) AS hi FROM orderbook_bbo")
                    .map { it["lo"].toString() to it["hi"].toString() }
                    .one()
                    .awaitSingle()
            LocalDateTime.parse(lo) to LocalDateTime.parse(hi)
        }
}
