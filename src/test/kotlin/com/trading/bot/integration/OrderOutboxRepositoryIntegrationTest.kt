package com.trading.bot.integration

import com.trading.bot.model.entity.OrderOutbox
import com.trading.bot.model.entity.OutboxStatus
import com.trading.bot.repository.OrderOutboxRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

/**
 * Интеграционные тесты OrderOutboxRepository против реальной Postgres:
 *
 * - уникальный idempotency_key (Gap C): повторный save с тем же ключом возвращает
 *   существующую строку и НЕ создаёт второй ряд (защита от двойного ордера);
 * - claimRetryable с экспоненциальным backoff (Gap D): FAILED-строка подбирается
 *   только после истечения LEAST(2^retry_count * base, max) + jitter;
 * - PENDING-строки старше cutoff подбираются независимо от backoff;
 * - claim переводит строку в PROCESSING (вторая попытка её не забирает), а зависшие
 *   PROCESSING (краш во время доставки) снова подбираются после 60 сек, ПРИБАВЛЯЯ
 *   retry_count (иначе переотправка идёт без State Reconciliation — риск дубля ордера);
 * - `processed_at` пишется временем БД (`now()`), а не JVM: backoff-предикат сравнивает
 *   его с `now()`, поэтому обе стороны должны быть в одной системе времени.
 *
 * Время в этих тестах (2026-10-26, дефект «смешанных источников времени») сдвигается
 * ТОЛЬКО временем БД — `shiftProcessedAt` использует `now() - interval`, чтобы тест был
 * корректен при любой зоне JVM относительно контейнера (МСК против UTC).
 */
@Tag("integration")
class OrderOutboxRepositoryIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var repo: OrderOutboxRepository

    @Autowired
    lateinit var databaseClient: DatabaseClient

    @BeforeEach
    fun cleanup() {
        runBlocking { repo.deleteAll() }
    }

    private fun row(
        key: String,
        status: OutboxStatus = OutboxStatus.PENDING,
        retryCount: Int = 0,
        createdAt: LocalDateTime = LocalDateTime.now(),
    ): OrderOutbox =
        OrderOutbox(
            payloadJson = """{"ticker":"Si","side":"sell","qty":1,"price":"92000","type":"limit","idempotencyKey":"$key"}""",
            status = status,
            idempotencyKey = key,
            retryCount = retryCount,
            createdAt = createdAt,
        )

    /** Сдвигает `processed_at` в прошлое ВРЕМЕНЕМ БД (одна система времени с backoff-предикатом). */
    private suspend fun shiftProcessedAt(
        id: UUID,
        secondsAgo: Long,
    ) {
        databaseClient
            .sql("UPDATE order_outbox SET processed_at = now() - make_interval(secs => :secs) WHERE id = :id")
            .bind("secs", secondsAgo.toDouble())
            .bind("id", id)
            .then()
            .awaitSingleOrNull()
    }

    private suspend fun dbNow(): LocalDateTime =
        databaseClient
            .sql("SELECT now()::timestamp AS ts")
            .map { row, _ -> row.get("ts", LocalDateTime::class.java)!! }
            .one()
            .awaitSingle()

    @Test
    fun `linkPosition attaches entry outbox to created position and is idempotent`() {
        runBlocking {
            val saved = repo.save(row("idem-link"))
            repo.linkPosition(saved.id!!, 42L)

            val linked = repo.findLatestByPositionId(42L)
            assertEquals(saved.id, linked?.id)
            assertEquals(42L, linked?.positionId)
            val normalized = linked?.payloadJson?.replace(" ", "")
            assertTrue(normalized?.contains("\"positionId\":42") == true, "payload=$normalized")

            // Повторная привязка (ретрай/реконсиляция) не перетирает уже связанную строку.
            repo.linkPosition(saved.id, 99L)
            assertEquals(42L, repo.findLatestByPositionId(42L)?.positionId)
        }
    }

    @Test
    fun `findSentWithoutPosition returns only delivered entry rows without link`() {
        runBlocking {
            // Крайние случаи: SENT без ордера, SENT со связью, PENDING с ордером —
            // ни один не должен попасть в стартовый re-link (скан-10 P1).
            val orphan = repo.save(row("idem-orphan"))
            repo.markSent(orphan.id!!, "ord-orphan")

            val linked = repo.save(row("idem-linked"))
            repo.markSent(linked.id!!, "ord-linked")
            repo.linkPosition(linked.id!!, 11L)

            // FAILED-строка с ордером: не SENT → стартовый re-link её не трогает.
            // (`markSent` → `markFailed` больше не проходит: терминальный SENT иммутабелен.)
            val failed = repo.save(row("idem-failed-order"))
            repo.markFailed(failed.id!!, "network")
            databaseClient
                .sql("UPDATE order_outbox SET alor_order_id = 'ord-failed' WHERE id = :id")
                .bind("id", failed.id)
                .then()
                .awaitSingleOrNull()

            val noOrder = repo.save(row("idem-sent-no-order"))
            repo.markSent(noOrder.id!!, null)

            val orphans = repo.findSentWithoutPosition()

            assertEquals(listOf(orphan.id), orphans.map { it.id })
            assertEquals("ord-orphan", orphans.single().alorOrderId)
        }
    }

    @Test
    fun `late markFailed cannot downgrade a delivered row`() {
        runBlocking {
            // Запоздавший воркер (истёк TTL лока, строку уже доставил другой инстанс)
            // раньше превращал SENT в FAILED → повторная отправка исполненного ордера
            // (скан-14 P1).
            val sent = repo.save(row("idem-late-fail"))
            repo.markSent(sent.id!!, "ord-late")

            repo.markFailed(sent.id!!, "stale worker error")

            val reloaded = repo.findById(sent.id!!)
            assertEquals(OutboxStatus.SENT, reloaded?.status, "терминальная доставка не перетирается")
            assertEquals(0, reloaded?.retryCount, "счётчик попыток не растёт на терминальной строке")
        }
    }

    @Test
    fun `late markSent cannot overwrite a terminal blocked row`() {
        runBlocking {
            val blocked = repo.save(row("idem-late-sent"))
            repo.markBlocked(blocked.id!!, "interlock deny")

            repo.markSent(blocked.id!!, "ord-should-not-apply")

            val reloaded = repo.findById(blocked.id!!)
            assertEquals(OutboxStatus.BLOCKED, reloaded?.status, "BLOCKED терминален")
            assertEquals(null, reloaded?.alorOrderId)
        }
    }

    @Test
    fun `duplicate idempotency key returns existing row without second insert`() {
        runBlocking {
            val first = repo.save(row("idem-dup-1"))
            val second = repo.save(row("idem-dup-1"))

            assertEquals(first.id, second.id)
            assertEquals("idem-dup-1", second.idempotencyKey)
            val all = repo.claimRetryable(5, olderThanSeconds = 0)
            assertEquals(1, all.size)
        }
    }

    @Test
    fun `different idempotency keys create separate rows`() {
        runBlocking {
            val a = repo.save(row("idem-a"))
            val b = repo.save(row("idem-b"))

            assertTrue(a.id != b.id)
            assertEquals(2, repo.claimRetryable(5, olderThanSeconds = 0).size)
        }
    }

    @Test
    fun `claimRetryable skips FAILED row before exponential backoff elapses`() {
        runBlocking {
            // retry_count=1 → интервал = LEAST(2^1 * 10, 120) = 20s (без jitter).
            val saved =
                repo.save(
                    row(
                        "idem-backoff-1",
                        status = OutboxStatus.FAILED,
                        retryCount = 1,
                        createdAt = LocalDateTime.now().minusHours(1),
                    ),
                )
            shiftProcessedAt(saved.id!!, secondsAgo = 5)

            val ready = repo.claimRetryable(5, backoffBaseSeconds = 10, backoffMaxSeconds = 120, jitterSeconds = 0)
            assertTrue(ready.none { it.idempotencyKey == "idem-backoff-1" })
        }
    }

    @Test
    fun `claimRetryable picks FAILED row after backoff window`() {
        runBlocking {
            // retry_count=1 → интервал 20s; прошло 40s → готов к повторной доставке.
            val saved =
                repo.save(
                    row(
                        "idem-backoff-2",
                        status = OutboxStatus.FAILED,
                        retryCount = 1,
                        createdAt = LocalDateTime.now().minusHours(1),
                    ),
                )
            shiftProcessedAt(saved.id!!, secondsAgo = 40)

            val ready = repo.claimRetryable(5, backoffBaseSeconds = 10, backoffMaxSeconds = 120, jitterSeconds = 0)
            assertTrue(ready.any { it.idempotencyKey == "idem-backoff-2" })
        }
    }

    @Test
    fun `claimRetryable picks FAILED row after exponential backoff growth`() {
        runBlocking {
            // retry_count=4 → интервал = LEAST(16 * 10, 120) = 120s.
            val saved =
                repo.save(
                    row(
                        "idem-backoff-3",
                        status = OutboxStatus.FAILED,
                        retryCount = 4,
                        createdAt = LocalDateTime.now().minusHours(2),
                    ),
                )
            shiftProcessedAt(saved.id!!, secondsAgo = 130)

            val ready = repo.claimRetryable(5, backoffBaseSeconds = 10, backoffMaxSeconds = 120, jitterSeconds = 0)
            assertTrue(ready.any { it.idempotencyKey == "idem-backoff-3" })
        }
    }

    @Test
    fun `claimRetryable skips FAILED row at max retries`() {
        runBlocking {
            val saved =
                repo.save(
                    row(
                        "idem-exhausted",
                        status = OutboxStatus.FAILED,
                        retryCount = 5,
                        createdAt = LocalDateTime.now().minusHours(3),
                    ),
                )
            shiftProcessedAt(saved.id!!, secondsAgo = 200)

            val ready = repo.claimRetryable(5, backoffBaseSeconds = 10, backoffMaxSeconds = 120, jitterSeconds = 0)
            assertTrue(ready.none { it.idempotencyKey == "idem-exhausted" })
        }
    }

    @Test
    fun `claimRetryable picks stale PENDING regardless of retries`() {
        runBlocking {
            val oldCreated = LocalDateTime.now().minusMinutes(10)
            repo.save(row("idem-pending", status = OutboxStatus.PENDING, createdAt = oldCreated))

            val ready = repo.claimRetryable(5, olderThanSeconds = 30)
            assertTrue(ready.any { it.idempotencyKey == "idem-pending" })
        }
    }

    @Test
    fun `claimRetryable marks rows as PROCESSING so second claim skips them`() {
        runBlocking {
            val oldCreated = LocalDateTime.now().minusMinutes(10)
            repo.save(row("idem-claimed", status = OutboxStatus.PENDING, createdAt = oldCreated))

            val first = repo.claimRetryable(5, olderThanSeconds = 30)
            assertEquals(1, first.size)
            assertEquals(OutboxStatus.PROCESSING, first[0].status)

            val second = repo.claimRetryable(5, olderThanSeconds = 30)
            assertTrue(second.none { it.idempotencyKey == "idem-claimed" })
        }
    }

    @Test
    fun `claimRetryable reclaims stale PROCESSING rows after 60 seconds`() {
        runBlocking {
            val saved =
                repo.save(
                    row(
                        "idem-stale-processing",
                        status = OutboxStatus.PROCESSING,
                        createdAt = LocalDateTime.now().minusMinutes(10),
                    ),
                )
            shiftProcessedAt(saved.id!!, secondsAgo = 300)

            val reclaimed = repo.claimRetryable(5, olderThanSeconds = 30)
            assertTrue(reclaimed.any { it.idempotencyKey == "idem-stale-processing" })
        }
    }

    @Test
    fun `claimRetryable increments retry_count when reclaiming stale PROCESSING row`() {
        runBlocking {
            // Краш во время доставки: строка осталась PROCESSING. Повторный захват ОБЯЗАН
            // прибавить retry_count — dispatch выполняет reconcileOrderByIdempotencyKey
            // только при retryCount > 0 (иначе переотправка без сверки = риск дубля ордера).
            val saved =
                repo.save(
                    row(
                        "idem-stale-processing-retry",
                        status = OutboxStatus.PROCESSING,
                        createdAt = LocalDateTime.now().minusMinutes(10),
                    ),
                )
            assertEquals(0, saved.retryCount)
            shiftProcessedAt(saved.id!!, secondsAgo = 300)

            val reclaimed = repo.claimRetryable(5, olderThanSeconds = 30)

            val row = reclaimed.first { it.idempotencyKey == "idem-stale-processing-retry" }
            assertEquals(1, row.retryCount, "захват зависшей PROCESSING-строки должен прибавить retry_count")
            assertEquals(OutboxStatus.PROCESSING, row.status)
        }
    }

    @Test
    fun `claimRetryable counts a PENDING claim as an attempt so resend reconciles first`() {
        runBlocking {
            // Инкремент retry_count только у зависших PROCESSING: FAILED уже считает попытку
            // в markFailed, а инкремент при захвате съедал бы бюджет ретраев вдвое быстрее.
            val pending =
                repo.save(
                    row("idem-pending-retry0", createdAt = LocalDateTime.now().minusMinutes(10)),
                )
            val failedRow =
                repo.save(
                    row(
                        "idem-failed-retry1",
                        status = OutboxStatus.FAILED,
                        retryCount = 1,
                        createdAt = LocalDateTime.now().minusHours(1),
                    ),
                )
            shiftProcessedAt(failedRow.id!!, secondsAgo = 40)

            val claimed = repo.claimRetryable(5, olderThanSeconds = 30, backoffBaseSeconds = 10, jitterSeconds = 0)

            // PENDING тоже инкрементит счётчик: после краха между сетевым вызовом и markSent
            // переотправка обязана сначала сверить заявку по idempotency key (скан-10 P1).
            assertEquals(1, claimed.first { it.id == pending.id }.retryCount)
            assertEquals(1, claimed.first { it.id == failedRow.id }.retryCount)
        }
    }

    @Test
    fun `claimRetryable skips fresh PROCESSING rows`() {
        runBlocking {
            val saved =
                repo.save(
                    row(
                        "idem-fresh-processing",
                        status = OutboxStatus.PROCESSING,
                        createdAt = LocalDateTime.now().minusMinutes(1),
                    ),
                )
            shiftProcessedAt(saved.id!!, secondsAgo = 1)

            val reclaimed = repo.claimRetryable(5, olderThanSeconds = 30)
            assertTrue(reclaimed.none { it.idempotencyKey == "idem-fresh-processing" })
        }
    }

    @Test
    fun `markFailed writes processed_at in DB time`() {
        runBlocking {
            // Backoff-предикат claimRetryable сравнивает processed_at с БД-шным now():
            // значение, записанное временем JVM из другой зоны, откладывало бы ретрай на часы.
            val saved = repo.save(row("idem-db-time", status = OutboxStatus.PENDING))
            repo.markFailed(saved.id!!, "rate limit")

            val stored =
                databaseClient
                    .sql("SELECT processed_at FROM order_outbox WHERE id = :id")
                    .bind("id", saved.id)
                    .map { row, _ -> row.get("processed_at", LocalDateTime::class.java)!! }
                    .one()
                    .awaitSingle()

            val skew = Duration.between(stored, dbNow()).abs()
            assertTrue(skew.seconds < 60, "processed_at должен быть временем БД (расхождение ${skew.seconds}s)")
        }
    }
}
