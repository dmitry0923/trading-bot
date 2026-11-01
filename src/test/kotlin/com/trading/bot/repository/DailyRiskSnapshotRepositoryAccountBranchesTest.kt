package com.trading.bot.repository

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.RowsFetchSpec
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.LocalDate
import java.util.function.BiFunction

/**
 * Unit-тест ветвлений [DailyRiskSnapshotRepository] по `accountId = null`:
 * legacy-режим (одна общая строка) и multi-account (строка на аккаунт).
 * SQL выбирается по этому признаку, поэтому оба пути должны исполняться.
 */
class DailyRiskSnapshotRepositoryAccountBranchesTest {
    private val databaseClient = Mockito.mock(DatabaseClient::class.java)
    private val spec = Mockito.mock(DatabaseClient.GenericExecuteSpec::class.java)

    @Suppress("UNCHECKED_CAST")
    private val fetch = Mockito.mock(RowsFetchSpec::class.java) as RowsFetchSpec<Any>

    private val repository = DailyRiskSnapshotRepository(databaseClient)

    @Suppress("UNCHECKED_CAST")
    private fun stubReactive() {
        Mockito.`when`(databaseClient.sql(Mockito.anyString())).thenReturn(spec)
        Mockito.`when`(spec.bind(Mockito.anyString(), Mockito.any())).thenReturn(spec)
        Mockito.`when`(spec.bindNull(Mockito.anyString(), Mockito.any())).thenReturn(spec)
        Mockito.`when`(spec.map(Mockito.any<BiFunction<Row, RowMetadata, Any>>())).thenReturn(fetch)
        Mockito.`when`(fetch.one()).thenReturn(Mono.empty<Any>())
        Mockito.`when`(fetch.all()).thenReturn(Flux.empty<Any>())
        Mockito.`when`(spec.then()).thenReturn(Mono.empty())
    }

    @Test
    fun `legacy and account scoped reads both hit the database`() {
        stubReactive()
        val day = LocalDate.of(2026, 1, 5)

        assertEquals(null, repository.findByDate(day))
        assertEquals(null, repository.findByDate(day, 7L))
        assertTrue(repository.findRecent(3).isEmpty())
        assertTrue(repository.findRecent(3, 7L).isEmpty())
    }

    @Test
    fun `suspend variants cover both account scopes`(): Unit =
        runBlocking {
            stubReactive()
            val day = LocalDate.of(2026, 1, 5)

            assertNull(repository.findByDateAwait(day))
            assertNull(repository.findByDateAwait(day, 7L))
            repository.upsertAwait(day, BigDecimal.ZERO, false, BigDecimal.ZERO)
            repository.upsertAwait(day, BigDecimal.ZERO, false, BigDecimal.ZERO, accountId = 7L)
            repository.upsertAwait(day, BigDecimal.ZERO, false, BigDecimal.ZERO, accountId = 7L, peakEquity = BigDecimal.ONE)
        }

    @Test
    fun `legacy and account scoped upserts both hit the database`() {
        stubReactive()
        val day = LocalDate.of(2026, 1, 5)

        repository.upsert(day, BigDecimal.ZERO, false, BigDecimal.ZERO)
        repository.upsert(day, BigDecimal.ZERO, false, BigDecimal.ZERO, accountId = 7L)
        repository.upsert(day, BigDecimal.ZERO, false, BigDecimal.ZERO, accountId = 7L, peakEquity = BigDecimal.ONE)
    }
}
