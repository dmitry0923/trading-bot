package com.trading.bot.integration

import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

class PositionRepositoryIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var repo: PositionRepository

    @BeforeEach
    fun cleanup() {
        runBlocking { repo.deleteAll() }
    }

    private fun pos(
        ticker: String = "GAZP",
        direction: PositionDirection = PositionDirection.LONG,
        quantity: Int = 10,
        status: PositionStatus = PositionStatus.OPEN,
        pendingEntry: Boolean = false,
        pendingClose: Boolean = false,
        instrumentType: InstrumentType = InstrumentType.STOCK,
    ) = Position(
        ticker = ticker,
        direction = direction,
        quantity = quantity,
        entryPrice = BigDecimal("100.00"),
        status = status,
        pendingEntry = pendingEntry,
        pendingClose = pendingClose,
        instrumentType = instrumentType,
        openedAt = java.time.LocalDateTime.now(),
    )

    @Test
    fun `findPending returns only pendingEntry and pendingClose positions`() {
        runBlocking {
            repo.save(pos(ticker = "A", pendingEntry = true))
            repo.save(pos(ticker = "B", pendingClose = true))
            repo.save(pos(ticker = "C"))
            repo.save(pos(ticker = "D", pendingEntry = true, pendingClose = true))

            val pending = repo.findPending()
            assertEquals(3, pending.size)
            assertTrue(pending.all { it.pendingEntry || it.pendingClose })
            val tickers = pending.map { it.ticker }.toSet()
            assertTrue("A" in tickers)
            assertTrue("B" in tickers)
            assertTrue("D" in tickers)
            assertTrue("C" !in tickers)
        }
    }

    @Test
    fun `findPending returns empty when no pending positions`() {
        runBlocking {
            repo.save(pos(ticker = "A"))
            repo.save(pos(ticker = "B"))

            val pending = repo.findPending()
            assertTrue(pending.isEmpty())
        }
    }

    @Test
    fun `findPending excludes non-OPEN positions`() {
        runBlocking {
            repo.save(pos(ticker = "A", pendingEntry = true, status = PositionStatus.OPEN))
            repo.save(pos(ticker = "B", pendingEntry = true, status = PositionStatus.CLOSED))

            val pending = repo.findPending()
            assertEquals(1, pending.size)
            assertEquals("A", pending[0].ticker)
        }
    }

    @Test
    fun `findOpenStocks excludes futures`() {
        runBlocking {
            repo.save(pos(ticker = "GAZP", instrumentType = InstrumentType.STOCK))
            repo.save(pos(ticker = "Si", instrumentType = InstrumentType.FUTURES))
            repo.save(pos(ticker = "LKOH", instrumentType = InstrumentType.STOCK))

            val stocks = repo.findOpenStocks()
            assertEquals(2, stocks.size)
            assertTrue(stocks.all { it.instrumentType != InstrumentType.FUTURES })
            val tickers = stocks.map { it.ticker }.toSet()
            assertTrue("GAZP" in tickers)
            assertTrue("LKOH" in tickers)
            assertTrue("Si" !in tickers)
        }
    }

    @Test
    fun `findOpenStocks excludes closed positions`() {
        runBlocking {
            repo.save(pos(ticker = "A", instrumentType = InstrumentType.STOCK, status = PositionStatus.OPEN))
            repo.save(pos(ticker = "B", instrumentType = InstrumentType.STOCK, status = PositionStatus.CLOSED))

            val stocks = repo.findOpenStocks()
            assertEquals(1, stocks.size)
            assertEquals("A", stocks[0].ticker)
        }
    }
}
