package com.trading.bot.integration

import com.trading.bot.model.entity.TradeEvent
import com.trading.bot.repository.TradeEventRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.DatabaseClient
import java.time.LocalDateTime
import java.util.UUID

/**
 * Интеграционные тесты [TradeEventRepository] против реальной Postgres:
 *
 * - конкурентные append одного агрегата получают РАЗЛИЧНЫЕ возрастающие
 *   `sequence_number` (уникальный индекс `(aggregate_id, sequence_number)`,
 *   Liquibase 041 + повтор при unique violation);
 * - порядок lineage (`ORDER BY sequence_number`) детерминирован и совпадает
 *   с числом записанных событий.
 */
@Tag("integration")
class TradeEventRepositoryIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var repo: TradeEventRepository

    @Autowired
    lateinit var databaseClient: DatabaseClient

    @Test
    fun `concurrent appends get distinct increasing sequence numbers`() {
        val aggregateId = UUID.randomUUID()
        val parallel = 8

        runBlocking {
            coroutineScope {
                (1..parallel)
                    .map { index ->
                        async {
                            repo.append(
                                TradeEvent(
                                    aggregateId = aggregateId,
                                    eventType = "POSITION_UPDATED",
                                    payload = """{"index":$index}""",
                                    occurredAt = LocalDateTime.now(),
                                    sequenceNumber = 0,
                                ),
                            )
                        }
                    }.awaitAll()
            }
        }

        val stored = runBlocking { repo.findByAggregateId(aggregateId) }
        val numbers = stored.map { it.sequenceNumber }

        assertEquals(parallel, numbers.size, "every append must be stored")
        assertEquals(
            (1L..parallel.toLong()).toList(),
            numbers,
            "sequence numbers must be unique, contiguous and ordered: $numbers",
        )
    }

    @Test
    fun `appends to different aggregates keep independent sequences`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        runBlocking {
            repo.append(event(first, 0))
            repo.append(event(first, 0))
            repo.append(event(second, 0))
        }

        assertEquals(listOf(1L, 2L), runBlocking { repo.findByAggregateId(first) }.map { it.sequenceNumber })
        assertEquals(listOf(1L), runBlocking { repo.findByAggregateId(second) }.map { it.sequenceNumber })
    }

    @Test
    fun `database rejects duplicate sequence numbers for the same aggregate`() {
        // Прямая проверка миграции 041: уникальность (aggregate_id, sequence_number)
        // держит БД, а не только код приложения.
        val aggregateId = UUID.randomUUID()
        runBlocking { repo.append(event(aggregateId, 0)) }

        val failure =
            assertThrows(DataIntegrityViolationException::class.java) {
                runBlocking {
                    databaseClient
                        .sql(
                            """
                            INSERT INTO trade_events (aggregate_id, event_type, payload, occurred_at, sequence_number)
                            VALUES (:aggregateId, 'POSITION_UPDATED', '{}'::jsonb, NOW(), 1)
                            """.trimIndent(),
                        ).bind("aggregateId", aggregateId)
                        .then()
                        .awaitSingleOrNull()
                }
            }

        assertNotNull(failure.message)
        assertEquals(1, runBlocking { repo.findByAggregateId(aggregateId) }.size)
    }

    private fun event(
        aggregateId: UUID,
        sequenceNumber: Long,
    ): TradeEvent =
        TradeEvent(
            aggregateId = aggregateId,
            eventType = "POSITION_OPENED",
            payload = """{"seq":$sequenceNumber}""",
            occurredAt = LocalDateTime.now(),
            sequenceNumber = sequenceNumber,
        )
}
