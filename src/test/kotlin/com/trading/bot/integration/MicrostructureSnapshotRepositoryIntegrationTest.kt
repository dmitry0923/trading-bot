package com.trading.bot.integration

import com.trading.bot.repository.MicrostructureSnapshotRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Интеграционные тесты MicrostructureSnapshotRepository против реальной Postgres:
 *
 * - nullable-колонки (L1 может прийти без bid/ask или размеров) пишутся как NULL;
 * - повторная запись того же бакета идемпотентна (ON CONFLICT DO NOTHING);
 * - findByTickerAndTimeBetween отдаёт хронологический порядок и строгую
 *   правую границу (для point-in-time признаков без lookahead).
 */
@Tag("integration")
class MicrostructureSnapshotRepositoryIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var repo: MicrostructureSnapshotRepository

    @Autowired
    lateinit var databaseClient: DatabaseClient

    @BeforeEach
    fun cleanup() {
        runBlocking {
            databaseClient
                .sql("DELETE FROM microstructure_snapshots")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    @Test
    fun `saveAll persists snapshot and findByTickerAndTimeBetween returns ascending`() {
        val base = LocalDateTime.of(2026, 9, 30, 10, 0, 0)
        val inserted =
            runBlocking {
                repo.saveAll(
                    listOf(
                        snapshot(base.minusSeconds(2), "0.250000", "100.005"),
                        snapshot(base, "-0.750000", "99.995"),
                    ),
                )
            }

        assertEquals(2, inserted)
        val loaded = runBlocking { repo.findByTickerAndTimeBetween("CNYRUBF", base.minusDays(1), base.plusMinutes(1)) }
        assertEquals(listOf(base.minusSeconds(2), base), loaded.map { it.time })
        assertEquals(0, BigDecimal("-0.75").compareTo(loaded.last().obi))
        assertEquals(7L, loaded.last().bidSize)
    }

    @Test
    fun `null book fields are stored as null`() {
        val base = LocalDateTime.of(2026, 9, 30, 11, 0, 0)
        val snapshot =
            MicrostructureSnapshotFactory.sparse(base)
        assertEquals(1, runBlocking { repo.saveAll(listOf(snapshot)) })

        val loaded = runBlocking { repo.findByTickerAndTimeBetween("CNYRUBF", base.minusDays(1), base.plusDays(1)) }
        assertEquals(1, loaded.size)
        assertNotNull(loaded.first().price)
        assertNull(loaded.first().bid)
        assertNull(loaded.first().ask)
        assertNull(loaded.first().bidSize)
        assertNull(loaded.first().askSize)
        assertNull(loaded.first().obi)
        assertNull(loaded.first().microprice)
        assertNull(loaded.first().micropriceDeviationBps)
    }

    @Test
    fun `repeated save of the same bucket is idempotent`() {
        val base = LocalDateTime.of(2026, 9, 30, 12, 0, 0)
        val snapshot = snapshot(base, "0.100000", "100.0")

        assertEquals(1, runBlocking { repo.saveAll(listOf(snapshot)) })
        assertEquals(0, runBlocking { repo.saveAll(listOf(snapshot)) })

        val loaded = runBlocking { repo.findByTickerAndTimeBetween("CNYRUBF", base.minusDays(1), base.plusDays(1)) }
        assertEquals(1, loaded.size)
        // Агрегаты не перетираются повторной записью
        assertEquals(0, BigDecimal("0.1").compareTo(loaded.first().obi))
    }

    @Test
    fun `findByTickerAndTimeBetween excludes the open bucket at the right boundary`() {
        val base = LocalDateTime.of(2026, 9, 30, 13, 0, 0)
        runBlocking {
            repo.saveAll(
                listOf(
                    snapshot(base.minusSeconds(1), "0.100000", "100.0"),
                    snapshot(base, "0.200000", "100.0"),
                ),
            )
        }

        val window = runBlocking { repo.findByTickerAndTimeBetween("CNYRUBF", base.minusDays(1), base) }

        // Бакет, начавшийся ровно в to, ещё не закрыт и не должен попадать в признаки
        assertEquals(1, window.size)
        assertEquals(base.minusSeconds(1), window.first().time)
    }

    @Test
    fun `findByTickerAndTimeBetween is empty outside data range`() {
        val base = LocalDateTime.of(2026, 9, 30, 14, 0, 0)
        runBlocking { repo.saveAll(listOf(snapshot(base, "0.100000", "100.0"))) }

        val empty = runBlocking { repo.findByTickerAndTimeBetween("CNYRUBF", base.plusDays(1), base.plusDays(2)) }

        assertTrue(empty.isEmpty())
    }

    private fun snapshot(
        time: LocalDateTime,
        obi: String,
        microprice: String,
    ) = MicrostructureSnapshotFactory.full(
        time = time,
        obi = BigDecimal(obi),
        microprice = BigDecimal(microprice),
    )
}
