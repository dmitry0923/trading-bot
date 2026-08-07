package com.trading.bot.application

import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsStream
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit-тесты [MarketDataGate]: свежесть источника цены перед входом.
 *
 * - свежий WS-тик по тикеру → вход разрешён;
 * - QUOTES подключён, но тика не было (старт/реконнект) → блок;
 * - устаревший WS-тик при подключённом QUOTES → блок (закрывает окно watchdog);
 * - QUOTES разорван + свежий REST-fallback → вход разрешён;
 * - QUOTES разорван + устаревший REST-fallback → блок;
 * - без данных вообще → блок.
 */
class MarketDataGateTest {
    private val tradingConfig = TradingConfig().apply { marketDataMaxAgeMs = 50 }
    private val meterRegistry = SimpleMeterRegistry()
    private val wsConfig = AlorConfig().apply { wsStaleMessageAgeMs = 5_000 }
    private val wsManager = WebSocketManager(wsConfig, meterRegistry)
    private val gate = MarketDataGate(wsManager, tradingConfig, meterRegistry)

    @Test
    fun `fresh ws tick allows entry`() {
        wsManager.onConnected(WsStream.QUOTES, 0)
        wsManager.isQuoteStale("SBER", Instant.now(), 1, Instant.now())

        assertTrue(gate.isPriceDataFresh("SBER"))
    }

    @Test
    fun `quotes connected but no tick yet blocks entry`() {
        wsManager.onConnected(WsStream.QUOTES, 0)

        assertFalse(gate.isPriceDataFresh("SBER"))
    }

    @Test
    fun `stale ws tick while connected blocks entry`() {
        wsManager.onConnected(WsStream.QUOTES, 0)
        wsManager.isQuoteStale("SBER", Instant.now(), 1, Instant.now())

        Thread.sleep(100)

        assertFalse(gate.isPriceDataFresh("SBER"))
    }

    @Test
    fun `quotes disconnected with fresh rest poll allows entry`() {
        wsManager.onDisconnected(WsStream.QUOTES, 0, "STREAM_CLOSED")
        gate.recordRestPollSuccess("SBER")

        assertTrue(gate.isPriceDataFresh("SBER"))
    }

    @Test
    fun `quotes disconnected with stale rest poll blocks entry`() {
        wsManager.onDisconnected(WsStream.QUOTES, 0, "STREAM_CLOSED")
        gate.recordRestPollSuccess("SBER")

        Thread.sleep(100)

        assertFalse(gate.isPriceDataFresh("SBER"))
    }

    @Test
    fun `no data at all blocks entry`() {
        assertFalse(gate.isPriceDataFresh("SBER"))
    }
}
