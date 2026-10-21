package com.trading.bot.integration

import com.trading.bot.model.entity.CbrRateRecord
import com.trading.bot.repository.CbrRatesHistoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Интеграционные тесты [CbrRatesHistoryRepository] против реальной Postgres
 * (P2 c1 плана 18_10_2026):
 *
 * - saveAll/findBetween возвращают ряд по возрастанию даты;
 * - повторная запись дозаполняет частичные строки (merge по rate_date);
 * - findLatestKeyRate игнорирует даты без ключевой ставки;
 * - coverage отдаёт число дат и границы; окно без данных пусто.
 */
@Tag("integration")
class CbrRatesHistoryRepositoryIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var repo: CbrRatesHistoryRepository

    @BeforeEach
    fun cleanup() {
        runBlocking { repo.deleteAll() }
    }

    @Test
    fun `saveAll persists rows and findBetween returns ascending`() {
        runBlocking {
            repo.saveAll(
                listOf(
                    record("2026-10-16", keyRate = "14.00", ruonia = "13.80"),
                    record("2026-10-14", keyRate = "13.50", ruonia = "13.40"),
                    record("2026-10-15", keyRate = "14.00", ruonia = "13.84"),
                ),
            )
        }

        val rows = runBlocking { repo.findBetween(LocalDate.parse("2026-10-14"), LocalDate.parse("2026-10-16")) }

        assertEquals(3, rows.size)
        assertEquals(
            listOf("2026-10-14", "2026-10-15", "2026-10-16"),
            rows.map { it.rateDate.toString() },
        )
        assertEquals(0, BigDecimal("13.84").compareTo(rows[1].ruonia))
        assertEquals("CBR", rows[0].source)
    }

    @Test
    fun `saveAll merges partial rows for the same date`() {
        val date = LocalDate.parse("2026-10-15")
        runBlocking {
            repo.saveAll(listOf(record("2026-10-15", keyRate = "14.00")))
            repo.saveAll(listOf(record("2026-10-15", ruonia = "13.84")))
        }

        val rows = runBlocking { repo.findBetween(date, date) }

        assertEquals(1, rows.size)
        assertEquals(0, BigDecimal("14.00").compareTo(rows[0].keyRate))
        assertEquals(0, BigDecimal("13.84").compareTo(rows[0].ruonia))
    }

    @Test
    fun `saveAll does not null out already known fields`() {
        val date = LocalDate.parse("2026-10-15")
        runBlocking {
            repo.saveAll(listOf(record("2026-10-15", keyRate = "14.00", ruonia = "13.84")))
            repo.saveAll(listOf(record("2026-10-15", keyRate = "14.00")))
        }

        val rows = runBlocking { repo.findBetween(date, date) }

        assertEquals(1, rows.size)
        assertEquals(0, BigDecimal("13.84").compareTo(rows[0].ruonia))
    }

    @Test
    fun `findLatestKeyRate ignores dates without key rate`() {
        runBlocking {
            repo.saveAll(listOf(record("2026-10-15", keyRate = "14.00")))
            repo.saveAll(listOf(record("2026-10-16", ruonia = "13.80")))
        }

        val latest = runBlocking { repo.findLatestKeyRate() }

        assertEquals(0, BigDecimal("14.00").compareTo(latest))
    }

    @Test
    fun `findLatestKeyRate is null when only ruonia is present`() {
        runBlocking { repo.saveAll(listOf(record("2026-10-16", ruonia = "13.80"))) }

        assertNull(runBlocking { repo.findLatestKeyRate() })
    }

    @Test
    fun `coverage reports count and bounds`() {
        runBlocking {
            repo.saveAll(
                listOf(
                    record("2026-10-14", keyRate = "13.50"),
                    record("2026-10-16", keyRate = "14.00"),
                ),
            )
        }

        val coverage = runBlocking { repo.coverage() }

        assertEquals(2L, coverage.count)
        assertEquals("2026-10-14", coverage.from.toString())
        assertEquals("2026-10-16", coverage.to.toString())
    }

    @Test
    fun `coverage of empty table reports zero and no bounds`() {
        val coverage = runBlocking { repo.coverage() }

        assertEquals(0L, coverage.count)
        assertNull(coverage.from)
        assertNull(coverage.to)
    }

    @Test
    fun `findBetween is empty outside data range`() {
        runBlocking { repo.saveAll(listOf(record("2026-10-15", keyRate = "14.00"))) }

        val empty = runBlocking { repo.findBetween(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-10")) }

        assertTrue(empty.isEmpty())
    }

    private fun record(
        date: String,
        keyRate: String? = null,
        ruonia: String? = null,
    ): CbrRateRecord =
        CbrRateRecord(
            rateDate = LocalDate.parse(date),
            keyRate = keyRate?.toBigDecimal(),
            ruonia = ruonia?.toBigDecimal(),
        )
}
