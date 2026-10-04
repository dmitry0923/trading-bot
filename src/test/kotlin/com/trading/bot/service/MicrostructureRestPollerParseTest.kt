package com.trading.bot.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Парсер ответа `/md/v2/orderbooks/{exchange}/{ticker}` — источника L1 для
 * forward-пилота микроструктуры.
 *
 * Проверяются реальные формы ответа Alor (проверены на живом токене), а также
 * вырожденные случаи: неполный стакан и стакан без существования инструмента
 * не должны приводить к записи фиктивных нулевых уровней.
 */
class MicrostructureRestPollerParseTest {
    private val mapper = ObjectMapper()
    private val poller =
        MicrostructureRestPoller(
            alorConfig =
                com.trading.bot.config
                    .AlorConfig(),
            microstructureConfig =
                com.trading.bot.config
                    .MicrostructureConfig(),
            tradingConfig =
                com.trading.bot.config
                    .TradingConfig(),
            tokenProvider =
                org.mockito.Mockito.mock(com.trading.bot.client.AlorTokenProvider::class.java),
            recorder =
                org.mockito.Mockito.mock(MicrostructureRecorder::class.java),
            meterRegistry = SimpleMeterRegistry(),
            objectMapper = mapper,
        )

    @Test
    fun `parses real CNYRUBF L1 payload with bid and ask volumes`() {
        val node =
            mapper.readTree(
                """
                {
                  "snapshot": true,
                  "bids": [{"price": 12.441, "volume": 564}],
                  "asks": [{"price": 12.442, "volume": 570}],
                  "timestamp": 1790932877,
                  "ms_timestamp": "2026-10-02T13:21:17.000Z"
                }
                """.trimIndent(),
            )

        val tick = poller.parse("CNYRUBF", node)

        assertNotNull(tick)
        assertEquals("CNYRUBF", tick.ticker)
        assertEquals(12.441.toBigDecimal(), tick.bid)
        assertEquals(12.442.toBigDecimal(), tick.ask)
        assertEquals(564L, tick.bidSize)
        assertEquals(570L, tick.askSize)
        assertEquals(12.4415.toBigDecimal(), tick.price.stripTrailingZeros())
    }

    @Test
    fun `selects best level across multiple levels`() {
        val node =
            mapper.readTree(
                """
                {
                  "bids": [{"price": 95.50, "volume": 10}, {"price": 95.51, "volume": 77}],
                  "asks": [{"price": 95.53, "volume": 5}, {"price": 95.52, "volume": 3}]
                }
                """.trimIndent(),
            )

        val tick = poller.parse("GAZP", node)

        assertNotNull(tick)
        assertEquals(95.51.toBigDecimal(), tick.bid)
        assertEquals(77L, tick.bidSize)
        assertEquals(95.52.toBigDecimal(), tick.ask)
        assertEquals(3L, tick.askSize)
    }

    @Test
    fun `returns null when ask side is missing`() {
        val node = mapper.readTree("""{"bids": [{"price": 12.441, "volume": 564}], "asks": []}""")

        assertNull(poller.parse("CNYRUBF", node))
    }

    @Test
    fun `returns null when bids are absent`() {
        val node = mapper.readTree("""{"bids": [], "asks": [{"price": 12.442, "volume": 570}]}""")

        assertNull(poller.parse("CNYRUBF", node))
    }

    @Test
    fun `returns null for non-existing instrument`() {
        val node = mapper.readTree("""{"existing": false, "bids": [], "asks": []}""")

        assertNull(poller.parse("UNKNOWN", node))
    }

    @Test
    fun `keeps zero volume level instead of dropping it`() {
        val node =
            mapper.readTree(
                """
                {
                  "bids": [{"price": 273.39, "volume": 0}],
                  "asks": [{"price": 273.40, "volume": 81}]
                }
                """.trimIndent(),
            )

        val tick = poller.parse("SBER", node)

        assertNotNull(tick)
        assertEquals(0L, tick.bidSize)
        assertEquals(81L, tick.askSize)
    }

    @Test
    fun `collection tickers narrow the pilot and never fall back to trading watchlist`() {
        val micro =
            com.trading.bot.config
                .MicrostructureConfig()
                .apply { tickers = listOf(" CNYRUBF ", "CNYRUBF", "") }
        val trading =
            com.trading.bot.config
                .TradingConfig()

        val narrowed = newPoller(micro, trading)

        assertEquals(listOf("CNYRUBF"), narrowed.tickers)
    }

    @Test
    fun `empty collection tickers fall back to trading watchlist without duplicates`() {
        val micro =
            com.trading.bot.config
                .MicrostructureConfig()
        val trading =
            com.trading.bot.config
                .TradingConfig()
                .apply { tickers = listOf("SBER", "SBER", "GAZP") }

        val fallback = newPoller(micro, trading)

        assertEquals(listOf("SBER", "GAZP"), fallback.tickers)
    }

    private fun newPoller(
        tickers: com.trading.bot.config.MicrostructureConfig,
        trading: com.trading.bot.config.TradingConfig,
    ): MicrostructureRestPoller =
        MicrostructureRestPoller(
            alorConfig =
                com.trading.bot.config
                    .AlorConfig(),
            microstructureConfig = tickers,
            tradingConfig = trading,
            tokenProvider = org.mockito.Mockito.mock(com.trading.bot.client.AlorTokenProvider::class.java),
            recorder = org.mockito.Mockito.mock(MicrostructureRecorder::class.java),
            meterRegistry = SimpleMeterRegistry(),
            objectMapper = mapper,
        )
}
