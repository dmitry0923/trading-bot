package com.trading.bot.integration

import com.trading.bot.model.entity.OrderOutbox
import com.trading.bot.model.entity.OutboxStatus
import com.trading.bot.repository.OrderOutboxRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime

/**
 * Интеграционные тесты OrderOutboxRepository против реальной Postgres:
 *
 * - уникальный idempotency_key (Gap C): повторный save с тем же ключом возвращает
 *   существующую строку и НЕ создаёт второй ряд (защита от двойного ордера);
 * - findRetryable с экспоненциальным backoff (Gap D): FAILED-строка подбирается
 *   только после истечения LEAST(2^retry_count * base, max) + jitter;
 * - PENDING-строки старше cutoff подбираются независимо от backoff.
 */
class OrderOutboxRepositoryIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var repo: OrderOutboxRepository

    @BeforeEach
    fun cleanup() {
        runBlocking { repo.deleteAll() }
    }

    private fun row(
        key: String,
        status: OutboxStatus = OutboxStatus.PENDING,
        retryCount: Int = 0,
        createdAt: LocalDateTime = LocalDateTime.now(),
        processedAt: LocalDateTime? = null,
    ): OrderOutbox =
        OrderOutbox(
            payloadJson = """{"ticker":"Si","side":"sell","qty":1,"price":"92000","type":"limit","idempotencyKey":"$key"}""",
            status = status,
            idempotencyKey = key,
            retryCount = retryCount,
            createdAt = createdAt,
            processedAt = processedAt,
        )

    @Test
    fun `duplicate idempotency key returns existing row without second insert`() {
        runBlocking {
            val first = repo.save(row("idem-dup-1"))
            val second = repo.save(row("idem-dup-1"))

            assertEquals(first.id, second.id)
            assertEquals("idem-dup-1", second.idempotencyKey)
            val all = repo.findRetryable(5, olderThanSeconds = 0)
            assertEquals(1, all.size)
        }
    }

    @Test
    fun `different idempotency keys create separate rows`() {
        runBlocking {
            val a = repo.save(row("idem-a"))
            val b = repo.save(row("idem-b"))

            assertTrue(a.id != b.id)
            assertEquals(2, repo.findRetryable(5, olderThanSeconds = 0).size)
        }
    }

    @Test
    fun `findRetryable skips FAILED row before exponential backoff elapses`() {
        runBlocking {
            // retry_count=1 → интервал = LEAST(2^1 * 10, 120) = 20s (без jitter).
            val staleProcessed = LocalDateTime.now().minusSeconds(5)
            repo.save(
                row(
                    "idem-backoff-1",
                    status = OutboxStatus.FAILED,
                    retryCount = 1,
                    createdAt = LocalDateTime.now().minusHours(1),
                    processedAt = staleProcessed,
                ),
            )

            val ready = repo.findRetryable(5, backoffBaseSeconds = 10, backoffMaxSeconds = 120, jitterSeconds = 0)
            assertTrue(ready.none { it.idempotencyKey == "idem-backoff-1" })
        }
    }

    @Test
    fun `findRetryable picks FAILED row after backoff window`() {
        runBlocking {
            // retry_count=1 → интервал 20s; прошло 40s → готов к повторной доставке.
            val oldProcessed = LocalDateTime.now().minusSeconds(40)
            repo.save(
                row(
                    "idem-backoff-2",
                    status = OutboxStatus.FAILED,
                    retryCount = 1,
                    createdAt = LocalDateTime.now().minusHours(1),
                    processedAt = oldProcessed,
                ),
            )

            val ready = repo.findRetryable(5, backoffBaseSeconds = 10, backoffMaxSeconds = 120, jitterSeconds = 0)
            assertTrue(ready.any { it.idempotencyKey == "idem-backoff-2" })
        }
    }

    @Test
    fun `findRetryable picks FAILED row after exponential backoff growth`() {
        runBlocking {
            // retry_count=4 → интервал = LEAST(16 * 10, 120) = 120s.
            val oldProcessed = LocalDateTime.now().minusSeconds(130)
            repo.save(
                row(
                    "idem-backoff-3",
                    status = OutboxStatus.FAILED,
                    retryCount = 4,
                    createdAt = LocalDateTime.now().minusHours(2),
                    processedAt = oldProcessed,
                ),
            )

            val ready = repo.findRetryable(5, backoffBaseSeconds = 10, backoffMaxSeconds = 120, jitterSeconds = 0)
            assertTrue(ready.any { it.idempotencyKey == "idem-backoff-3" })
        }
    }

    @Test
    fun `findRetryable skips FAILED row at max retries`() {
        runBlocking {
            val oldProcessed = LocalDateTime.now().minusSeconds(200)
            repo.save(
                row(
                    "idem-exhausted",
                    status = OutboxStatus.FAILED,
                    retryCount = 5,
                    createdAt = LocalDateTime.now().minusHours(3),
                    processedAt = oldProcessed,
                ),
            )

            val ready = repo.findRetryable(5, backoffBaseSeconds = 10, backoffMaxSeconds = 120, jitterSeconds = 0)
            assertTrue(ready.none { it.idempotencyKey == "idem-exhausted" })
        }
    }

    @Test
    fun `findRetryable picks stale PENDING regardless of retries`() {
        runBlocking {
            val oldCreated = LocalDateTime.now().minusMinutes(10)
            repo.save(row("idem-pending", status = OutboxStatus.PENDING, createdAt = oldCreated))

            val ready = repo.findRetryable(5, olderThanSeconds = 30)
            assertTrue(ready.any { it.idempotencyKey == "idem-pending" })
        }
    }
}
