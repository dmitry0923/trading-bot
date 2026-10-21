package com.trading.bot.infrastructure.cbr

import com.trading.bot.config.MacroConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import java.time.LocalDate

/**
 * Фоновая донакачка ряда ЦБ (P2 c1): окно из конфига, статусы метрик и fail-soft
 * при отказе источника (отказ не роняет приложение и не меняет текущую ставку).
 */
class CbrRatesRefreshSchedulerTest {
    private val config =
        MacroConfig().apply {
            liveRateEnabled = true
            cbrRefreshDays = 30
        }
    private val loader = Mockito.mock(CbrRatesHistoryLoader::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val scheduler = CbrRatesRefreshScheduler(config, loader, meterRegistry)

    @BeforeEach
    fun reset() {
        Mockito.reset(loader)
    }

    @Test
    fun `refresh loads configured window and marks ok`() {
        runBlocking {
            Mockito.`when`(loader.loadAndSave(any())).thenReturn(result(loaded = 5, upserted = 4))
        }

        runBlocking { scheduler.refresh() }

        runBlocking {
            Mockito.verify(loader).loadAndSave(30)
        }
        assertEquals(1.0, statusCount("OK"), 1e-9)
    }

    @Test
    fun `refresh marks empty when provider returns no rows`() {
        runBlocking {
            Mockito.`when`(loader.loadAndSave(any())).thenReturn(result(loaded = 0, upserted = 0))
        }

        runBlocking { scheduler.refresh() }

        assertEquals(1.0, statusCount("EMPTY"), 1e-9)
        assertEquals(null, meterRegistry.find("macro.cbr.rates.refresh").tag("status", "OK").counter())
    }

    @Test
    fun `refresh marks error and does not throw when loader fails`() {
        runBlocking {
            Mockito.`when`(loader.loadAndSave(any())).thenThrow(RuntimeException("cbr unreachable"))
        }

        runBlocking { scheduler.refresh() }

        assertEquals(1.0, statusCount("ERROR"), 1e-9)
    }

    @Test
    fun `refresh is skipped when live rate is disabled`() {
        config.liveRateEnabled = false

        runBlocking { scheduler.refresh() }

        runBlocking {
            Mockito.verify(loader, Mockito.never()).loadAndSave(any())
        }
        assertEquals(null, meterRegistry.find("macro.cbr.rates.refresh").counter())
    }

    private fun statusCount(status: String): Double {
        val found = meterRegistry.find("macro.cbr.rates.refresh")
        val tagged = found.tag("status", status)
        val counter = tagged.counter()
        return counter?.count() ?: 0.0
    }

    private fun result(
        loaded: Int,
        upserted: Int,
    ): CbrRatesHistoryLoader.LoadResult =
        CbrRatesHistoryLoader.LoadResult(
            from = LocalDate.parse("2026-09-18"),
            to = LocalDate.parse("2026-10-18"),
            loaded = loaded,
            upserted = upserted,
            keyRates = loaded,
            ruonia = loaded,
        )
}
