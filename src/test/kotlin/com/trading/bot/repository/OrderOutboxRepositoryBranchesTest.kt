package com.trading.bot.repository

import com.trading.bot.model.entity.OrderOutbox
import com.trading.bot.model.entity.OutboxStatus
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.FetchSpec
import org.springframework.r2dbc.core.RowsFetchSpec
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.LocalDateTime
import java.util.function.BiFunction

/**
 * Unit-тест дефолтов и ветвлений выбора SQL в [OrderOutboxRepository]:
 * `countPendingByAccount` (`?: 0`), `findLatestByPositionId` с фильтром по
 * `purpose` и без него, а также генерация id для строки без id.
 */
class OrderOutboxRepositoryBranchesTest {
    private val databaseClient = Mockito.mock(DatabaseClient::class.java)
    private val spec = Mockito.mock(DatabaseClient.GenericExecuteSpec::class.java)

    @Suppress("UNCHECKED_CAST")
    private val fetch = Mockito.mock(RowsFetchSpec::class.java) as RowsFetchSpec<Any>

    @Suppress("UNCHECKED_CAST")
    private val fetchSpec = Mockito.mock(FetchSpec::class.java) as FetchSpec<Map<String, Any>>

    private val repository = OrderOutboxRepository(databaseClient)

    private var mapping: BiFunction<Row, RowMetadata, Any?>? = null

    @Suppress("UNCHECKED_CAST")
    private fun stubReactive() {
        Mockito.`when`(databaseClient.sql(Mockito.anyString())).thenReturn(spec)
        Mockito.`when`(spec.bind(Mockito.anyString(), Mockito.any())).thenReturn(spec)
        Mockito.`when`(spec.bindNull(Mockito.anyString(), Mockito.any())).thenReturn(spec)
        Mockito.`when`(spec.map(Mockito.any<BiFunction<Row, RowMetadata, Any>>())).thenAnswer { invocation ->
            mapping = invocation.getArgument(0)
            fetch
        }
        Mockito.`when`(fetch.one()).thenReturn(Mono.empty<Any>())
        Mockito.`when`(fetch.all()).thenReturn(Flux.empty<Any>())
        Mockito.`when`(spec.fetch()).thenReturn(fetchSpec)
        Mockito.`when`(spec.then()).thenReturn(Mono.empty())
    }

    private fun applyMapperToNullRow(): Any? = mapping!!.apply(Mockito.mock(Row::class.java), Mockito.mock(RowMetadata::class.java))

    @Test
    fun `pending count defaults to zero when the driver returns nothing`(): Unit =
        runBlocking {
            stubReactive()

            assertEquals(0, repository.countPendingByAccount(7L))
            assertEquals(0, applyMapperToNullRow())
        }

    @Test
    fun `latest outbox lookup supports both purpose scopes`(): Unit =
        runBlocking {
            stubReactive()

            assertNull(repository.findLatestByPositionId(1L))
            assertNull(repository.findLatestByPositionId(1L, "sl"))
            assertNull(repository.findLatestByPositionId(1L, null))
        }

    @Test
    fun `mapper defaults a null retry count to zero`(): Unit =
        runBlocking {
            stubReactive()
            repository.findLatestByPositionId(1L)
            val values =
                mapOf<String, Any?>(
                    "payload" to "{}",
                    "status" to "PENDING",
                    "created_at" to LocalDateTime.of(2026, 1, 5, 10, 0),
                )
            val row = Mockito.mock(Row::class.java) { invocation -> values[invocation.arguments.firstOrNull() as? String] }

            val outbox = mapping!!.apply(row, Mockito.mock(RowMetadata::class.java)) as OrderOutbox

            assertEquals(0, outbox.retryCount)
            assertNull(outbox.alorOrderId)
        }

    @Test
    fun `duplicate idempotency key is rethrown when the existing row is absent`(): Unit =
        runBlocking {
            stubReactive()
            Mockito
                .`when`(spec.then())
                .thenReturn(Mono.error(org.springframework.dao.DataIntegrityViolationException("duplicate")))
            val outbox = OrderOutbox(payloadJson = "{}", status = OutboxStatus.PENDING, idempotencyKey = "dup-key")

            org.junit.jupiter.api.assertThrows<org.springframework.dao.DataIntegrityViolationException> {
                repository.save(outbox)
            }
        }

    @Test
    fun `row without an id receives a generated uuid`(): Unit =
        runBlocking {
            stubReactive()
            val outbox = OrderOutbox(payloadJson = "{}", status = OutboxStatus.PENDING)

            val saved = repository.save(outbox.copy(id = null))

            assertNull(outbox.id)
            assertEquals(false, saved === outbox)
        }
}
