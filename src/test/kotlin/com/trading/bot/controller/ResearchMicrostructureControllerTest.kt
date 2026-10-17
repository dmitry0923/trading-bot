package com.trading.bot.controller

import com.trading.bot.model.dto.MicrostructureCoverage
import com.trading.bot.model.entity.MicrostructureSnapshot
import com.trading.bot.service.MicrostructureResearchService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDateTime

class ResearchMicrostructureControllerTest {
    private val service = Mockito.mock(MicrostructureResearchService::class.java)
    private val controller = ResearchMicrostructureController(service)

    @BeforeEach
    fun reset() {
        Mockito.reset(service)
    }

    @Test
    fun `coverage returns ticker and sources`() {
        runBlocking {
            Mockito
                .`when`(service.coverage("CNYRUBF"))
                .thenReturn(
                    listOf(
                        MicrostructureCoverage(
                            source = "microstructure_snapshots",
                            ticker = "CNYRUBF",
                            rows = 12,
                            from = LocalDateTime.of(2024, 10, 1, 7, 0),
                            to = LocalDateTime.of(2024, 10, 1, 23, 50),
                        ),
                    ),
                )
        }

        val response = runBlocking { controller.coverage("CNYRUBF") }

        assertEquals("CNYRUBF", response["ticker"])
        val sources = response["sources"] as List<*>
        assertEquals(1, sources.size)
        assertEquals(12L, (sources[0] as Map<*, *>)["rows"])
    }

    @Test
    fun `snapshots returns count and rows`() {
        runBlocking {
            Mockito.`when`(service.snapshots("CNYRUBF", null, null, 5)).thenReturn(listOf(snapshot()))
        }

        val response = runBlocking { controller.snapshots("CNYRUBF", null, null, 5) }

        assertEquals(1, response["count"])
        assertEquals("CNYRUBF", response["ticker"])
    }

    @Test
    fun `ticks delegates to service`() {
        runBlocking {
            Mockito.`when`(service.ticks("CNYRUBF", null, null, null)).thenReturn(emptyList())
        }

        val response = runBlocking { controller.ticks("CNYRUBF", null, null, null) }

        assertEquals(0, response["count"])
    }

    @Test
    fun `export returns csv attachment with source and ticker in filename`() {
        runBlocking {
            Mockito.`when`(service.exportCsv("snapshots", "CNYRUBF", null, null, null)).thenReturn("ticker,time\n")
        }

        val response = runBlocking { controller.export("snapshots", "CNYRUBF", null, null, null) }

        assertEquals("text/csv", response.headers.contentType.toString())
        assertTrue(
            response.headers.getFirst("Content-Disposition")!!.contains("microstructure_snapshots_CNYRUBF.csv"),
        )
        assertEquals("ticker,time\n", response.body)
    }

    @Test
    fun `export maps unknown source to bad request`() {
        runBlocking {
            Mockito
                .`when`(service.exportCsv("orderbook", "CNYRUBF", null, null, null))
                .thenThrow(IllegalArgumentException("unknown microstructure source: orderbook"))
        }

        val error =
            assertThrows<ResponseStatusException> {
                runBlocking { controller.export("orderbook", "CNYRUBF", null, null, null) }
            }

        assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
    }

    private fun snapshot(): MicrostructureSnapshot =
        MicrostructureSnapshot(
            ticker = "CNYRUBF",
            time = LocalDateTime.of(2024, 10, 1, 10, 0),
            updateCount = 3,
            price = BigDecimal("66000.00"),
            bid = BigDecimal("65990"),
            ask = BigDecimal("66010"),
            bidSize = 5,
            askSize = 7,
            spreadBps = BigDecimal("1.2"),
            obi = BigDecimal("0.25"),
            microprice = BigDecimal("66000.5"),
            micropriceDeviationBps = null,
        )
}
