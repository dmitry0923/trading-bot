package com.trading.bot.integration

import com.trading.bot.repository.HistoricalBboRepository
import com.trading.bot.repository.TradeTickRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Интеграционные тесты persistence-слоя исторического BBO и тиков MOEX
 * Типа B на реальном Postgres (миграция 038):
 *
 * - идемпотентность `ON CONFLICT DO NOTHING` (ретрай/докачка/перезапуск);
 * - `price` исторического BBO остаётся NULL: точная цена сделки берётся
 *   point-in-time из `ticks`, а не из котировки;
 * - nullable `open_interest` у сделки;
 * - полуоткрытый интервал `[from, to)` — как у свечей, чтобы в
 *   point-in-time признаках не было lookahead.
 */
@Tag("integration")
class HistoricalMarketDataRepositoryIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var bboRepository: HistoricalBboRepository

    @Autowired
    lateinit var tickRepository: TradeTickRepository

    @Autowired
    lateinit var databaseClient: DatabaseClient

    @BeforeEach
    fun cleanup() {
        runBlocking {
            databaseClient
                .sql("DELETE FROM orderbook_bbo")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            databaseClient
                .sql("DELETE FROM ticks")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    @Test
    fun `bbo bucket round-trips through the hypertable`() {
        val base = LocalDateTime.of(2024, 10, 1, 19, 5, 1)
        val inserted = runBlocking { bboRepository.saveAll(listOf(bbo(base), bbo(base.plusSeconds(1)))) }

        assertEquals(2, inserted)
        val loaded = runBlocking { bboRepository.findByTickerAndTimeBetween("CNYRUBF", base.minusMinutes(1), base.plusMinutes(1)) }
        assertEquals(listOf(base, base.plusSeconds(1)), loaded.map { it.time })
        assertEquals(0, BigDecimal("13.224").compareTo(loaded.first().bid))
        assertEquals(0, BigDecimal("13.226").compareTo(loaded.first().ask))
        assertEquals(42L, loaded.first().bidSize)
        assertEquals(0, BigDecimal("1.512").compareTo(loaded.first().spreadBps))
    }

    @Test
    fun `historical bbo price stays null because trade price comes from ticks`() {
        val base = LocalDateTime.of(2024, 10, 1, 19, 6, 0)
        runBlocking { bboRepository.saveAll(listOf(bbo(base))) }

        val loaded = runBlocking { bboRepository.findByTickerAndTimeBetween("CNYRUBF", base.minusMinutes(1), base.plusMinutes(1)) }

        assertNull(loaded.single().price)
    }

    @Test
    fun `repeated bbo load is idempotent`() {
        val base = LocalDateTime.of(2024, 10, 1, 19, 7, 0)

        assertEquals(1, runBlocking { bboRepository.saveAll(listOf(bbo(base))) })
        assertEquals(0, runBlocking { bboRepository.saveAll(listOf(bbo(base))) })

        assertEquals(1, runBlocking { bboRepository.findByTickerAndTimeBetween("CNYRUBF", base.minusMinutes(1), base.plusMinutes(1)) }.size)
    }

    @Test
    fun `trade tick round-trips with nullable open interest`() {
        val base = LocalDateTime.of(2024, 10, 1, 19, 5, 1, 117_000_000)
        val inserted = runBlocking { tickRepository.saveAll(listOf(tick(base, 1L, null), tick(base.plusNanos(50_000_000), 2L, 8128542L))) }

        assertEquals(2, inserted)
        val loaded = runBlocking { tickRepository.findByTickerAndTimeBetween("CNYRUBF", base.minusMinutes(1), base.plusMinutes(1)) }
        assertEquals(listOf(base, base.plusNanos(50_000_000)), loaded.map { it.time })
        assertEquals("B", loaded.first().direction)
        assertNull(loaded.first().openInterest)
        assertEquals(8128542L, loaded.last().openInterest)
    }

    @Test
    fun `repeated deal load is idempotent`() {
        val base = LocalDateTime.of(2024, 10, 1, 19, 8, 0, 500000)
        val tick = tick(base, 99L, 10L)

        assertEquals(1, runBlocking { tickRepository.saveAll(listOf(tick)) })
        assertEquals(0, runBlocking { tickRepository.saveAll(listOf(tick)) })

        assertEquals(
            1,
            runBlocking { tickRepository.findByTickerAndTimeBetween("CNYRUBF", base.minusMinutes(1), base.plusMinutes(1)) }.size,
        )
    }

    @Test
    fun `findByTickerAndTimeBetween excludes the open bucket at the right boundary`() {
        val base = LocalDateTime.of(2024, 10, 1, 19, 9, 0)
        runBlocking {
            bboRepository.saveAll(listOf(bbo(base.minusSeconds(1)), bbo(base)))
            tickRepository.saveAll(listOf(tick(base.minusSeconds(1), 7L, 1L), tick(base, 8L, 1L)))
        }

        val bboWindow = runBlocking { bboRepository.findByTickerAndTimeBetween("CNYRUBF", base.minusMinutes(1), base) }
        val tickWindow = runBlocking { tickRepository.findByTickerAndTimeBetween("CNYRUBF", base.minusMinutes(1), base) }

        assertEquals(listOf(base.minusSeconds(1)), bboWindow.map { it.time })
        assertEquals(listOf(base.minusSeconds(1)), tickWindow.map { it.time })
    }

    @Test
    fun `reads are empty outside the loaded range`() {
        val base = LocalDateTime.of(2024, 10, 1, 19, 10, 0)
        runBlocking {
            bboRepository.saveAll(listOf(bbo(base)))
            tickRepository.saveAll(listOf(tick(base, 11L, 1L)))
        }

        assertTrue(runBlocking { bboRepository.findByTickerAndTimeBetween("CNYRUBF", base.plusDays(1), base.plusDays(2)) }.isEmpty())
        assertTrue(runBlocking { tickRepository.findByTickerAndTimeBetween("CNYRUBF", base.plusDays(1), base.plusDays(2)) }.isEmpty())
    }

    @Test
    fun `ticker is isolated from other instruments`() {
        val base = LocalDateTime.of(2024, 10, 1, 19, 11, 0)
        runBlocking {
            bboRepository.saveAll(
                listOf(
                    bbo(base).copy(ticker = "CNYRUBF"),
                    bbo(base).copy(ticker = "USDRUBF"),
                ),
            )
            tickRepository.saveAll(
                listOf(
                    tick(base, 21L, 1L).copy(ticker = "CNYRUBF"),
                    tick(base, 22L, 1L).copy(ticker = "USDRUBF"),
                ),
            )
        }

        assertEquals(1, runBlocking { bboRepository.findByTickerAndTimeBetween("CNYRUBF", base.minusMinutes(1), base.plusMinutes(1)) }.size)
        assertEquals(
            1,
            runBlocking { tickRepository.findByTickerAndTimeBetween("USDRUBF", base.minusMinutes(1), base.plusMinutes(1)) }.size,
        )
    }

    private fun bbo(time: LocalDateTime) =
        com.trading.bot.model.entity.OrderbookBbo(
            ticker = "CNYRUBF",
            time = time,
            quoteCount = 12,
            price = null,
            bid = BigDecimal("13.224"),
            ask = BigDecimal("13.226"),
            bidSize = 42,
            askSize = 17,
            spreadBps = BigDecimal("1.512"),
            obi = BigDecimal("0.423"),
            microprice = BigDecimal("13.22464"),
            micropriceDeviationBps = BigDecimal("0.483"),
        )

    private fun tick(
        time: LocalDateTime,
        dealId: Long,
        openInterest: Long?,
    ) = com.trading.bot.model.entity.TradeTick(
        ticker = "CNYRUBF",
        time = time,
        dealId = dealId,
        price = BigDecimal("13.2240"),
        volume = 5,
        direction = "B",
        openInterest = openInterest,
    )
}
