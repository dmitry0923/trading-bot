package com.trading.bot.repository

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.FetchSpec
import org.springframework.r2dbc.core.RowsFetchSpec
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.util.function.BiFunction

/**
 * Unit-тест дефолтов счётчиков [PositionRepository].
 *
 * Схема БД отдаёт `count(*)` и `EXISTS` всегда, но репозиторий обязан не падать,
 * если драйвер вернул `NULL` в колонке: `row.get(...) ?: 0` / `?: false`.
 * Тест перехватывает mapper, который репозиторий передаёт в R2DBC, и вызывает его
 * с пустой строкой — так проверяются обе ветви без живой БД.
 */
class PositionRepositoryCountBranchesTest {
    private val databaseClient = Mockito.mock(DatabaseClient::class.java)
    private val spec = Mockito.mock(DatabaseClient.GenericExecuteSpec::class.java)

    @Suppress("UNCHECKED_CAST")
    private val fetch = Mockito.mock(RowsFetchSpec::class.java) as RowsFetchSpec<Any>

    @Suppress("UNCHECKED_CAST")
    private val fetchSpec = Mockito.mock(FetchSpec::class.java) as FetchSpec<Map<String, Any>>
    private val repository = PositionRepository(databaseClient)

    private var mapping: BiFunction<Row, RowMetadata, Any?>? = null

    @Suppress("UNCHECKED_CAST")
    private fun stubEmptyResult() {
        Mockito.`when`(databaseClient.sql(Mockito.anyString())).thenReturn(spec)
        Mockito.`when`(spec.bind(Mockito.anyString(), Mockito.any())).thenReturn(spec)
        Mockito.`when`(spec.map<Any>(Mockito.any<BiFunction<Row, RowMetadata, Any>>())).thenAnswer { invocation ->
            mapping = invocation.getArgument(0)
            fetch
        }
        Mockito.`when`(fetch.one()).thenReturn(Mono.empty<Any>())
        Mockito.`when`(fetch.all()).thenReturn(Flux.empty<Any>())
        Mockito.`when`(spec.fetch()).thenReturn(fetchSpec)
    }

    private fun applyMapperToNullRow(): Any? = mapping!!.apply(Mockito.mock(Row::class.java), Mockito.mock(RowMetadata::class.java))

    @Test
    fun `counts default to zero when the driver returns no value`(): Unit =
        runBlocking {
            stubEmptyResult()

            assertEquals(0, repository.findOpenCount())
            assertEquals(0, applyMapperToNullRow())

            assertEquals(0, repository.findOpenCountByAccount(null))
            assertEquals(0, applyMapperToNullRow())

            assertEquals(0, repository.findOpenCountByAccount(7L))
            assertEquals(0, applyMapperToNullRow())
        }

    @Test
    fun `account count and closed aggregates default to zero`(): Unit =
        runBlocking {
            stubEmptyResult()

            assertEquals(0, repository.countByAccount(7L))
            assertEquals(0, applyMapperToNullRow())

            runCatching { repository.findClosedAggregates(null) }
            val aggregates = applyMapperToNullRow() as PositionRepository.ClosedPositionAggregates

            assertEquals(0, BigDecimal.ZERO.compareTo(aggregates.totalRealized))
            assertEquals(0, BigDecimal.ZERO.compareTo(aggregates.peakRealized))
        }

    @Test
    fun `update statements report both affected row counts`(): Unit =
        runBlocking {
            stubEmptyResult()

            Mockito.`when`(fetchSpec.rowsUpdated()).thenReturn(Mono.just(1L))
            assertTrue(repository.claimForClose(1L))
            assertTrue(repository.releaseCloseClaim(1L))

            Mockito.`when`(fetchSpec.rowsUpdated()).thenReturn(Mono.just(0L))
            assertFalse(repository.claimForClose(1L))
            assertFalse(repository.releaseCloseClaim(1L))
        }

    @Test
    fun `existence defaults to false and a missing ticker is an error`(): Unit =
        runBlocking {
            stubEmptyResult()

            assertFalse(repository.hasOpenPosition("SBER"))
            assertEquals(false, applyMapperToNullRow())

            assertTrue(repository.findOpenTickersDistinct().isEmpty())
            assertThrows<IllegalStateException> { applyMapperToNullRow() }
        }
}
