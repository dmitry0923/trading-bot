package com.trading.bot.integration

import com.trading.bot.repository.DailyRiskSnapshotRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Интеграционные тесты DailyRiskSnapshotRepository против реальной Postgres:
 *
 * - upsert ведёт историю по одной строке на торговую дату (roadmap 13.7.2);
 * - повторный upsert той же даты обновляет строку, а не создаёт новую;
 * - findRecent возвращает окно за N дней по возрастанию даты и отсекает старые.
 */
class DailyRiskSnapshotRepositoryIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var repo: DailyRiskSnapshotRepository

    @BeforeEach
    fun cleanup() {
        repo.deleteAll()
    }

    @Test
    fun `upsert accumulates per-date history and findRecent returns ascending`() {
        val today = LocalDate.now()
        repo.upsert(today.minusDays(2), BigDecimal("-500"), true, BigDecimal("-500"))
        repo.upsert(today.minusDays(1), BigDecimal("300"), false, BigDecimal.ZERO)
        repo.upsert(today, BigDecimal("-1200"), true, BigDecimal("-1200"))

        val recent = repo.findRecent(30)

        assertEquals(3, recent.size)
        assertEquals(listOf(today.minusDays(2), today.minusDays(1), today), recent.map { it.tradeDate })
        assertEquals(0, BigDecimal("-1200").compareTo(recent.last().dailyPnl))
        assertTrue(recent.last().limitReached)
    }

    @Test
    fun `findRecent window filters out older dates`() {
        val today = LocalDate.now()
        repo.upsert(today.minusDays(40), BigDecimal("-100"), false, BigDecimal.ZERO)
        repo.upsert(today.minusDays(3), BigDecimal("50"), false, BigDecimal.ZERO)

        val recent = repo.findRecent(30)

        assertEquals(1, recent.size)
        assertEquals(today.minusDays(3), recent.first().tradeDate)
    }

    @Test
    fun `upsert on the same date updates the row instead of inserting a duplicate`() {
        val today = LocalDate.now()
        repo.upsert(today, BigDecimal("100"), false, BigDecimal.ZERO)
        repo.upsert(today, BigDecimal("-900"), true, BigDecimal("-900"))

        val recent = repo.findRecent(30)

        assertEquals(1, recent.size)
        assertEquals(0, BigDecimal("-900").compareTo(recent.first().dailyPnl))
        assertTrue(recent.first().limitReached)
    }
}
