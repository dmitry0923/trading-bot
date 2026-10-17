package com.trading.bot.service

import com.trading.bot.model.dto.MicrostructureCoverage
import com.trading.bot.model.entity.MicrostructureSnapshot
import com.trading.bot.model.entity.OrderbookBbo
import com.trading.bot.model.entity.TradeTick
import com.trading.bot.repository.HistoricalBboRepository
import com.trading.bot.repository.MicrostructureSnapshotRepository
import com.trading.bot.repository.TradeTickRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import java.math.BigDecimal
import java.time.LocalDateTime

class MicrostructureResearchServiceTest {
    private val snapshotRepository = Mockito.mock(MicrostructureSnapshotRepository::class.java)
    private val bboRepository = Mockito.mock(HistoricalBboRepository::class.java)
    private val tickRepository = Mockito.mock(TradeTickRepository::class.java)

    private val service =
        MicrostructureResearchService(
            snapshotRepository,
            bboRepository,
            tickRepository,
        )

    @BeforeEach
    fun reset() {
        Mockito.reset(snapshotRepository, bboRepository, tickRepository)
    }

    @Test
    fun `coverage returns three sources in stable order`() {
        val from = LocalDateTime.of(2024, 10, 1, 7, 0)
        val to = LocalDateTime.of(2024, 10, 1, 23, 50)
        runBlocking {
            Mockito.`when`(snapshotRepository.coverage("CNYRUBF")).thenReturn(coverage("microstructure_snapshots", from, to, 10))
            Mockito.`when`(bboRepository.coverage("CNYRUBF")).thenReturn(coverage("orderbook_bbo", from, to, 20))
            Mockito.`when`(tickRepository.coverage("CNYRUBF")).thenReturn(coverage("ticks", from, to, 30))
        }

        val result = runBlocking { service.coverage("CNYRUBF") }

        assertEquals(listOf("microstructure_snapshots", "orderbook_bbo", "ticks"), result.map { it.source })
        assertEquals(30L, result[2].rows)
    }

    @Test
    fun `coverage surfaces empty source bounds as null`() {
        runBlocking {
            Mockito.`when`(snapshotRepository.coverage("CNYRUBF")).thenReturn(coverage("microstructure_snapshots", null, null, 0))
            Mockito.`when`(bboRepository.coverage("CNYRUBF")).thenReturn(coverage("orderbook_bbo", null, null, 0))
            Mockito.`when`(tickRepository.coverage("CNYRUBF")).thenReturn(coverage("ticks", null, null, 0))
        }

        val result = runBlocking { service.coverage("CNYRUBF") }

        assertTrue(result.all { it.rows == 0L && it.from == null && it.to == null })
    }

    @Test
    fun `snapshots uses full window and default limit when unspecified`() {
        runBlocking {
            Mockito.`when`(snapshotRepository.findByTickerAndTimeBetween(any(), any(), any(), any())).thenReturn(emptyList())
        }

        runBlocking { service.snapshots("CNYRUBF", null, null, null) }

        val fromCaptor = argumentCaptor<LocalDateTime>()
        val toCaptor = argumentCaptor<LocalDateTime>()
        val limitCaptor = argumentCaptor<Int>()
        runBlocking {
            Mockito
                .verify(snapshotRepository)
                .findByTickerAndTimeBetween(any(), fromCaptor.capture(), toCaptor.capture(), limitCaptor.capture())
        }
        assertTrue(fromCaptor.firstValue.isBefore(LocalDateTime.of(2000, 1, 1, 0, 0)))
        assertTrue(toCaptor.firstValue.isAfter(LocalDateTime.of(2099, 1, 1, 0, 0)))
        assertEquals(MicrostructureResearchService.DEFAULT_LIMIT, limitCaptor.firstValue)
    }

    @Test
    fun `snapshots passes explicit window and clamps limit`() {
        val from = LocalDateTime.of(2024, 10, 1, 7, 0)
        val to = LocalDateTime.of(2024, 10, 1, 8, 0)
        runBlocking {
            Mockito.`when`(snapshotRepository.findByTickerAndTimeBetween(any(), any(), any(), any())).thenReturn(emptyList())
        }

        runBlocking { service.snapshots("CNYRUBF", from, to, 0) }
        runBlocking { service.snapshots("CNYRUBF", from, to, 5) }
        runBlocking { service.snapshots("CNYRUBF", from, to, 5_000_000) }

        val fromCaptor = argumentCaptor<LocalDateTime>()
        val toCaptor = argumentCaptor<LocalDateTime>()
        val limitCaptor = argumentCaptor<Int>()
        runBlocking {
            Mockito
                .verify(snapshotRepository, Mockito.times(3))
                .findByTickerAndTimeBetween(any(), fromCaptor.capture(), toCaptor.capture(), limitCaptor.capture())
        }
        assertEquals(listOf(from, from, from), fromCaptor.allValues)
        assertEquals(listOf(to, to, to), toCaptor.allValues)
        assertEquals(listOf(1, 5, MicrostructureResearchService.MAX_LIMIT), limitCaptor.allValues)
    }

    @Test
    fun `exportCsv snapshots writes header and rows with empty nulls`() {
        runBlocking {
            Mockito.`when`(snapshotRepository.findByTickerAndTimeBetween(any(), any(), any(), any())).thenReturn(listOf(snapshot()))
        }

        val csv = runBlocking { service.exportCsv("snapshots", "CNYRUBF", null, null, 10) }
        val lines = csv.trimEnd().split("\n")

        assertEquals(
            "ticker,time,update_count,price,bid,ask,bid_size,ask_size," +
                "spread_bps,obi,microprice,microprice_deviation_bps",
            lines[0],
        )
        assertEquals("CNYRUBF,2024-10-01T10:00,3,66000.00,65990,66010,5,7,1.2,0.25,66000.5,", lines[1])
    }

    @Test
    fun `exportCsv bbo writes header and rows`() {
        runBlocking {
            Mockito.`when`(bboRepository.findByTickerAndTimeBetween(any(), any(), any(), any())).thenReturn(listOf(bbo()))
        }

        val csv = runBlocking { service.exportCsv("bbo", "CNYRUBF", null, null, 10) }
        val lines = csv.trimEnd().split("\n")

        assertTrue(lines[0].startsWith("ticker,time,quote_count,price,bid,ask,bid_size,ask_size,spread_bps,obi,microprice"))
        assertEquals("CNYRUBF,2024-10-01T10:00,42,,65990,66010,5,7,1.2,0.25,66000.5,0.3", lines[1])
    }

    @Test
    fun `exportCsv ticks writes header and rows`() {
        runBlocking {
            Mockito.`when`(tickRepository.findByTickerAndTimeBetween(any(), any(), any(), any())).thenReturn(listOf(tick()))
        }

        val csv = runBlocking { service.exportCsv("ticks", "CNYRUBF", null, null, 10) }
        val lines = csv.trimEnd().split("\n")

        assertEquals("ticker,time,deal_id,price,volume,direction,open_interest", lines[0])
        assertEquals("CNYRUBF,2024-10-01T10:00,12345,66000.00,2,BUY,100", lines[1])
    }

    @Test
    fun `exportCsv without rows returns header only`() {
        runBlocking {
            Mockito.`when`(tickRepository.findByTickerAndTimeBetween(any(), any(), any(), any())).thenReturn(emptyList())
        }

        val csv = runBlocking { service.exportCsv("ticks", "CNYRUBF", null, null, 10) }

        assertEquals("ticker,time,deal_id,price,volume,direction,open_interest\n", csv)
    }

    @Test
    fun `exportCsv rejects unknown source`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { service.exportCsv("orderbook", "CNYRUBF", null, null, null) }
            }
        assertTrue(error.message!!.contains("unknown microstructure source"))
    }

    private fun coverage(
        source: String,
        from: LocalDateTime?,
        to: LocalDateTime?,
        rows: Long,
    ): MicrostructureCoverage = MicrostructureCoverage(source = source, ticker = "CNYRUBF", rows = rows, from = from, to = to)

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

    private fun bbo(): OrderbookBbo =
        OrderbookBbo(
            ticker = "CNYRUBF",
            time = LocalDateTime.of(2024, 10, 1, 10, 0),
            quoteCount = 42,
            price = null,
            bid = BigDecimal("65990"),
            ask = BigDecimal("66010"),
            bidSize = 5,
            askSize = 7,
            spreadBps = BigDecimal("1.2"),
            obi = BigDecimal("0.25"),
            microprice = BigDecimal("66000.5"),
            micropriceDeviationBps = BigDecimal("0.3"),
        )

    private fun tick(): TradeTick =
        TradeTick(
            ticker = "CNYRUBF",
            time = LocalDateTime.of(2024, 10, 1, 10, 0),
            dealId = 12345,
            price = BigDecimal("66000.00"),
            volume = 2,
            direction = "BUY",
            openInterest = 100,
        )
}
