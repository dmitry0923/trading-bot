package com.trading.bot.integration

import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.PartitionMaintenanceService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Интеграционные тесты партиционирования positions/agent_logs (миграция 019):
 *
 * - после Liquibase обе таблицы — партиционированные родители (relkind 'p')
 *   с месячными партициями и DEFAULT-партициями;
 * - INSERT через репозиторий попадает в месячную партицию по opened_at;
 * - PartitionMaintenanceService создаёт будущие партиции идемпотентно.
 */
class PartitionMaintenanceIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var databaseClient: DatabaseClient

    @Autowired
    lateinit var positionRepo: PositionRepository

    @Autowired
    lateinit var partitionMaintenance: PartitionMaintenanceService

    private val currentMonth = YearMonth.now()

    @BeforeEach
    fun cleanup() {
        runBlocking { positionRepo.deleteAll() }
    }

    private suspend fun relkind(table: String): String =
        databaseClient
            .sql("SELECT relkind FROM pg_class WHERE relname = :table")
            .bind("table", table)
            .map { row, _ -> row.get("relkind", String::class.java) ?: "" }
            .one()
            .awaitSingle()

    private suspend fun partitionExists(name: String): Boolean =
        databaseClient
            .sql("SELECT to_regclass(current_schema() || '.' || :name) IS NOT NULL AS found")
            .bind("name", name)
            .map { row, _ -> row.get("found", Boolean::class.javaObjectType) ?: false }
            .one()
            .awaitSingle()

    private suspend fun countIn(table: String): Long =
        databaseClient
            .sql("SELECT count(*) AS cnt FROM $table")
            .map { row, _ -> row.get("cnt", Long::class.javaObjectType) ?: 0L }
            .one()
            .awaitSingle()

    @Test
    fun `positions and agent_logs are partitioned parents with monthly and default partitions`() {
        runBlocking {
            assertEquals("p", relkind("positions"))
            assertEquals("p", relkind("agent_logs"))

            val month = currentMonth.format(DateTimeFormatter.ofPattern("yyyyMM"))
            assertTrue(partitionExists("positions_$month"))
            assertTrue(partitionExists("agent_logs_$month"))
            assertTrue(partitionExists("positions_default"))
            assertTrue(partitionExists("agent_logs_default"))
        }
    }

    @Test
    fun `insert routes into the monthly partition by opened_at`() {
        runBlocking {
            val openedAt = LocalDateTime.of(currentMonth.atDay(15), LocalTime.NOON)
            val saved =
                positionRepo.save(
                    Position(
                        ticker = "SBER",
                        direction = PositionDirection.LONG,
                        quantity = 1,
                        entryPrice = BigDecimal("250.00"),
                        instrumentType = InstrumentType.STOCK,
                        openedAt = openedAt,
                    ),
                )

            assertNotNull(saved.id)
            val month = currentMonth.format(DateTimeFormatter.ofPattern("yyyyMM"))
            assertEquals(1L, countIn("positions_$month"))
            assertEquals(openedAt, positionRepo.findById(saved.id!!).openedAt)
        }
    }

    @Test
    fun `maintenance service creates future partitions idempotently`() {
        runBlocking {
            val future = YearMonth.of(2030, 6)

            val created = partitionMaintenance.ensureMonths(future, 1)

            assertTrue(created.contains("positions_203006"))
            assertTrue(created.contains("agent_logs_203006"))
            assertTrue(partitionExists("positions_203006"))
            assertTrue(partitionExists("agent_logs_203006"))

            val secondRun = partitionMaintenance.ensureMonths(future, 1)
            assertTrue(secondRun.isEmpty())
        }
    }
}
