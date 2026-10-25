package com.trading.bot.integration

import com.trading.bot.tuning.StrategyVersion
import com.trading.bot.tuning.StrategyVersionRepository
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
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * Интеграционные тесты [StrategyVersionRepository] против реальной Postgres:
 *
 * - save присваивает id и round-trip'ит JSONB-колонки (parameters/metrics);
 * - повторный save того же периода делает UPSERT (partial unique index
 *   `WHERE NOT rolled_back`), не создавая дубль;
 * - откатанные версии исключены из «последней активной», но видны в аудите;
 * - выборка всех версий идёт по убыванию deployed_at.
 */
@Tag("integration")
class StrategyVersionRepositoryIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var repository: StrategyVersionRepository

    @Autowired
    lateinit var databaseClient: DatabaseClient

    private val objectMapper = ObjectMapper()

    /** jsonb приходит из Postgres нормализованным (`{"k": 1}`) — читаем значение, а не текст. */
    private fun jsonInt(
        json: String,
        field: String,
    ): Int = objectMapper.readTree(json).get(field).asInt()

    @BeforeEach
    fun cleanup() {
        runBlocking {
            databaseClient
                .sql("DELETE FROM strategy_versions")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    @Test
    fun `save assigns id and round-trips jsonb columns`() {
        val saved =
            runBlocking {
                repository.save(
                    version(
                        period = "2026-10",
                        parameters = """{"slPoints":150,"tpPoints":300}""",
                        gateStatus = "PASS",
                        notes = "first deployment",
                    ),
                )
            }

        assertNotNull(saved.id)
        val loaded = runBlocking { repository.findByVersion("2026-10") }
        assertNotNull(loaded)
        assertEquals(saved.id, loaded!!.id)
        assertEquals("PASS", loaded.gateStatus)
        assertEquals("first deployment", loaded.notes)
        assertEquals(150, jsonInt(loaded.parameters, "slPoints"))
        assertEquals(30, jsonInt(loaded.metrics, "tradesCount"))
        assertTrue(!loaded.rolledBack)
    }

    @Test
    fun `saving the same period upserts instead of duplicating`() {
        val first =
            runBlocking {
                repository.save(version(period = "2026-09", gateStatus = "WARN", notes = "initial"))
            }
        val second =
            runBlocking {
                repository.save(
                    version(
                        period = "2026-09",
                        parameters = """{"slPoints":120,"tpPoints":360}""",
                        gateStatus = "ALERT",
                        notes = "re-tuned",
                    ),
                )
            }

        assertEquals(first.id, second.id, "conflict target must update the existing row")
        val all = runBlocking { repository.findAllByOrderByDeployedAtDesc() }
        assertEquals(1, all.size, "active version per period is unique")
        assertEquals("ALERT", all.first().gateStatus)
        assertEquals("re-tuned", all.first().notes)
        assertEquals(120, jsonInt(all.first().parameters, "slPoints"), all.first().parameters)
    }

    @Test
    fun `rolled back versions are excluded from active lookup but kept for audit`() {
        val active =
            runBlocking {
                repository.save(
                    version(
                        period = "2026-08",
                        deployedAt = OffsetDateTime.now().minusDays(2),
                    ),
                )
            }
        val rolledBack =
            runBlocking {
                repository.save(
                    version(
                        period = "2026-07",
                        deployedAt = OffsetDateTime.now().minusDays(3),
                        rolledBack = true,
                        notes = "rollback: regression",
                    ),
                )
            }

        val latestActive = runBlocking { repository.findFirstByRolledBackFalseOrderByDeployedAtDesc() }
        assertEquals(active.id, latestActive?.id)

        val audit = runBlocking { repository.findByRolledBackTrue() }
        assertEquals(1, audit.size)
        assertEquals(rolledBack.id, audit.first().id)
        assertEquals("rollback: regression", audit.first().notes)

        // Откат не мешает пере-деплою того же периода (partial unique index).
        val redeployed =
            runBlocking {
                repository.save(version(period = "2026-07", notes = "redeployed after fix"))
            }
        assertNotNull(redeployed.id)
        val rowsWithSamePeriod =
            runBlocking {
                databaseClient
                    .sql("SELECT count(*) AS c FROM strategy_versions WHERE version = '2026-07'")
                    .map { row, _ -> row.get("c", Long::class.javaObjectType) ?: 0L }
                    .one()
                    .awaitSingle()
            }
        assertEquals(2L, rowsWithSamePeriod, "rolled back row and new active row coexist")
    }

    @Test
    fun `findAll returns newest deployment first`() {
        runBlocking {
            repository.save(version(period = "2026-05", deployedAt = OffsetDateTime.now().minusDays(10)))
            repository.save(version(period = "2026-06", deployedAt = OffsetDateTime.now().minusDays(1)))
        }

        val all = runBlocking { repository.findAllByOrderByDeployedAtDesc() }

        assertEquals(listOf("2026-06", "2026-05"), all.map { it.version })
        assertNull(runBlocking { repository.findByVersion("2020-01") })
    }

    private fun version(
        period: String,
        parameters: String = """{"slPoints":150}""",
        gateStatus: String = "PASS",
        deployedAt: OffsetDateTime = OffsetDateTime.now(),
        rolledBack: Boolean = false,
        notes: String? = null,
    ): StrategyVersion =
        StrategyVersion(
            version = period,
            parameters = parameters,
            gateStatus = gateStatus,
            metrics = """{"tradesCount":30,"profitFactor":2.0}""",
            deployedAt = deployedAt,
            rolledBack = rolledBack,
            notes = notes,
        )
}
