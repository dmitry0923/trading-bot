package com.trading.bot.integration

import com.trading.bot.model.entity.MacroSnapshot
import com.trading.bot.repository.MacroSnapshotRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Интеграционные тесты MacroSnapshotRepository против реальной Postgres
 * (roadmap v2.4, раздел 13.11.2):
 *
 * - save возвращает id и сохраняет числовые значения без искажений;
 * - findBetween возвращает снапшоты по возрастанию captured_at, включая границы;
 * - findBetween пустой на окне без данных.
 */
@Tag("integration")
class MacroSnapshotRepositoryIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var repo: MacroSnapshotRepository

    @BeforeEach
    fun cleanup() {
        runBlocking { repo.deleteAll() }
    }

    @Test
    fun `save persists snapshot and findBetween returns ascending`() {
        val base = LocalDateTime.of(2026, 3, 1, 10, 0)
        val older = runBlocking { repo.save(snapshot(base.minusMinutes(30), "15.5", "70.0", "88.1")) }
        val middle = runBlocking { repo.save(snapshot(base.minusMinutes(15), "16.0", "75.0", "90.0")) }
        val newer = runBlocking { repo.save(snapshot(base, "16.5", "80.0", "93.7")) }

        assertNotNull(older.id)
        assertNotNull(middle.id)
        assertNotNull(newer.id)

        val all = runBlocking { repo.findBetween(base.minusDays(1), base.plusMinutes(1)) }
        assertEquals(3, all.size)
        assertEquals(listOf(base.minusMinutes(30), base.minusMinutes(15), base), all.map { it.capturedAt })
        assertEquals(0, BigDecimal("16.5").compareTo(all.last().cbrRate))
        assertEquals(0, BigDecimal("93.7").compareTo(all.last().usdRub))
        assertEquals(0, BigDecimal("80.0").compareTo(all.last().brentPrice))
    }

    @Test
    fun `findBetween includes boundaries`() {
        val base = LocalDateTime.of(2026, 3, 1, 10, 0)
        runBlocking {
            repo.save(snapshot(base.minusMinutes(15), "16.0", "75.0", "90.0"))
            repo.save(snapshot(base.plusMinutes(15), "16.5", "80.0", "93.0"))
        }

        val window = runBlocking { repo.findBetween(base.minusMinutes(15), base.plusMinutes(15)) }

        assertEquals(2, window.size)
    }

    @Test
    fun `findBetween is empty outside data range`() {
        val base = LocalDateTime.of(2026, 3, 1, 10, 0)
        runBlocking { repo.save(snapshot(base, "16.0", "75.0", "90.0")) }

        val empty = runBlocking { repo.findBetween(base.plusDays(1), base.plusDays(2)) }

        assertTrue(empty.isEmpty())
    }

    private fun snapshot(
        capturedAt: LocalDateTime,
        cbrRate: String,
        brentPrice: String,
        usdRub: String,
    ): MacroSnapshot =
        MacroSnapshot(
            capturedAt = capturedAt,
            cbrRate = BigDecimal(cbrRate),
            brentPrice = BigDecimal(brentPrice),
            usdRub = BigDecimal(usdRub),
        )
}
