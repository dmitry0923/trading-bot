package com.trading.bot.controller

import com.trading.bot.infrastructure.cbr.CbrRatesHistoryLoader
import com.trading.bot.model.entity.CbrRateRecord
import com.trading.bot.repository.CbrRatesHistoryRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [MacroRatesController] — дефолты и зажим окна бэкфилла/серии, статус и выдача ряда.
 */
class MacroRatesControllerTest {
    private val loader = mock<CbrRatesHistoryLoader>()
    private val repository = mock<CbrRatesHistoryRepository>()
    private val registry = SimpleMeterRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneId.of("Europe/Moscow"))
    private val controller = MacroRatesController(loader, repository, registry, clock)

    private fun anyDate(): LocalDate {
        org.mockito.Mockito.any(LocalDate::class.java)
        return LocalDate.of(2026, 1, 1)
    }

    @Test
    fun `backfill clamps the requested window and reports the load stats`(): Unit =
        runBlocking {
            whenever(loader.loadAndSave(any())).thenReturn(
                CbrRatesHistoryLoader.LoadResult(
                    from = LocalDate.of(2024, 10, 20),
                    to = LocalDate.of(2026, 10, 20),
                    loaded = 10,
                    upserted = 8,
                    keyRates = 10,
                    ruonia = 9,
                ),
            )

            val defaults = controller.ratesHistory(null)
            val lower = controller.ratesHistory(0)
            val upper = controller.ratesHistory(99_999)

            assertEquals(730, defaults["days"])
            assertEquals(1, lower["days"])
            assertEquals(3_650, upper["days"])
            assertEquals(10, defaults["loaded"])
            assertEquals(8, defaults["upserted"])
            assertEquals(LocalDate.of(2024, 10, 20), defaults["from"])
            assertEquals(3.0, registry.counter("api.macro.rates_history").count())
        }

    @Test
    fun `status and series read the repository`(): Unit =
        runBlocking {
            whenever(repository.coverage()).thenReturn(
                CbrRatesHistoryRepository.Coverage(
                    count = 2L,
                    from = LocalDate.of(2024, 10, 20),
                    to = LocalDate.of(2026, 10, 20),
                ),
            )
            whenever(repository.findLatestKeyRate()).thenReturn(BigDecimal("14.0"))
            whenever(repository.findBetween(anyDate(), anyDate())).thenReturn(
                listOf(CbrRateRecord(rateDate = LocalDate.of(2026, 10, 20), keyRate = BigDecimal("14.0"))),
            )

            val status = controller.ratesHistoryStatus()
            val series = controller.ratesHistorySeries(0)

            assertEquals(2L, status["count"])
            assertEquals(0, BigDecimal("14.0").compareTo(status["latestKeyRate"] as BigDecimal))
            assertEquals(1, series["count"])
            assertEquals(1, (series["rows"] as List<*>).size)
        }
}
