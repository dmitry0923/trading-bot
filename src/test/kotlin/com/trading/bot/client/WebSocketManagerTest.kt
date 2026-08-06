package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Unit-тесты [WebSocketManager]: stale data discard (возраст + нарушенный порядок),
 * сброс водяных знаков при переподключении и watchdog «тихого» соединения.
 */
class WebSocketManagerTest {
    private val alorConfig =
        AlorConfig().apply {
            wsStaleMessageAgeMs = 5_000
            wsHeartbeatTimeoutMs = 45_000
        }
    private val meterRegistry = SimpleMeterRegistry()
    private val manager = WebSocketManager(alorConfig, meterRegistry)

    @Test
    fun `fresh quote is accepted`() {
        assertFalse(manager.isQuoteStale("SBER", Instant.now(), 1, Instant.now()))
    }

    @Test
    fun `quote older than stale age is discarded`() {
        val receivedAt = Instant.now().minus(6, ChronoUnit.SECONDS)
        assertTrue(manager.isQuoteStale("SBER", receivedAt, 1, Instant.now()))
    }

    @Test
    fun `out-of-order quote by exchange time is discarded`() {
        val now = Instant.now()
        assertFalse(manager.isQuoteStale("SBER", Instant.now(), 1, now))
        assertTrue(manager.isQuoteStale("SBER", Instant.now(), 2, now.minusSeconds(1)))
    }

    @Test
    fun `out-of-order quote by sequence is discarded when exchange time is absent`() {
        assertFalse(manager.isQuoteStale("SBER", Instant.now(), 5))
        assertTrue(manager.isQuoteStale("SBER", Instant.now(), 4))
    }

    @Test
    fun `onConnected resets watermarks so new snapshot is accepted`() {
        val now = Instant.now()
        assertFalse(manager.isQuoteStale("SBER", Instant.now(), 1, now))
        assertTrue(manager.isQuoteStale("SBER", Instant.now(), 2, now.minusSeconds(1)))

        manager.onConnected(WsStream.QUOTES, 1)

        assertFalse(manager.isQuoteStale("SBER", Instant.now(), 3, now.minusSeconds(1)))
    }

    @Test
    fun `watchdog marks idle connected stream as disconnected`() {
        manager.onConnected(WsStream.ORDERS, 0)
        assertTrue(manager.isConnected(WsStream.ORDERS))

        alorConfig.wsHeartbeatTimeoutMs = 1
        Thread.sleep(5)
        manager.watchdog()

        assertFalse(manager.isConnected(WsStream.ORDERS))
        val count = meterRegistry.counter("alor.ws.stale_connection", Tags.of("stream", "ORDERS")).count()
        assertTrue(count >= 1.0)
    }
}
