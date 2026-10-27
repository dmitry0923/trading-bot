package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
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
    fun `snapshot with old exchange time is discarded even if received just now`() {
        // После реконнекта Alor присылает снапшот: локальное время получения свежее,
        // а цена может быть 20-минутной давности (скан-7 P2).
        assertTrue(
            manager.isQuoteStale("SBER", Instant.now(), 1, Instant.now().minusSeconds(120)),
            "quote by exchange time older than the ceiling must be discarded",
        )
        assertFalse(manager.isQuoteStale("SBER", Instant.now(), 2, Instant.now().minusSeconds(1)))
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
    fun `lastQuoteReceivedAt tracks accepted quotes only`() {
        assertNull(manager.lastQuoteReceivedAt("SBER"))

        val oldReceivedAt = Instant.now().minus(6, ChronoUnit.SECONDS)
        assertTrue(manager.isQuoteStale("SBER", oldReceivedAt, 1, Instant.now()))
        assertNull(manager.lastQuoteReceivedAt("SBER"))

        assertFalse(manager.isQuoteStale("SBER", Instant.now(), 2, Instant.now()))
        val last = manager.lastQuoteReceivedAt("SBER")
        assertNotNull(last)
        assertTrue(Duration.between(last, Instant.now()).toMillis() < 1_000)
    }

    @Test
    fun `onConnected resets lastQuoteReceivedAt`() {
        assertFalse(manager.isQuoteStale("SBER", Instant.now(), 1, Instant.now()))
        assertNotNull(manager.lastQuoteReceivedAt("SBER"))

        manager.onConnected(WsStream.QUOTES, 1)

        assertNull(manager.lastQuoteReceivedAt("SBER"))
    }

    @Test
    fun `onConnected of orders stream does not reset quote watermarks`() {
        val now = Instant.now()
        assertFalse(manager.isQuoteStale("SBER", Instant.now(), 1, now))
        assertFalse(manager.isQuoteStale("GAZP", Instant.now(), 1))
        assertNotNull(manager.lastQuoteReceivedAt("SBER"))

        // Реконнект потока заявок НЕ должен легализовать устаревшие/неупорядоченные котировки.
        manager.onConnected(WsStream.ORDERS, 1)

        assertTrue(manager.isQuoteStale("SBER", Instant.now(), 2, now.minusSeconds(1)))
        assertTrue(manager.isQuoteStale("GAZP", Instant.now(), 1))
        assertNotNull(manager.lastQuoteReceivedAt("SBER"))

        // Реконнект именно потока котировок — водяные знаки сбрасываются.
        manager.onConnected(WsStream.QUOTES, 1)

        assertFalse(manager.isQuoteStale("SBER", Instant.now(), 3, now.minusSeconds(1)))
        assertFalse(manager.isQuoteStale("GAZP", Instant.now(), 1))
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

    @Test
    fun `activity after watchdog disconnect restores connected state`() {
        // Watchdog только помечает поток, реконнект не заказывает: без восстановления
        // TradingGate блокировал бы все входы до рестарта процесса (скан-12 P1).
        manager.onConnected(WsStream.ORDERS, 0)
        alorConfig.wsHeartbeatTimeoutMs = 1
        Thread.sleep(5)
        manager.watchdog()
        assertFalse(manager.isConnected(WsStream.ORDERS))

        manager.onActivity(WsStream.ORDERS)

        assertTrue(manager.isConnected(WsStream.ORDERS))
        assertTrue(meterRegistry.counter("alor.ws.connected", Tags.of("stream", "ORDERS")).count() >= 2.0)
    }

    @Test
    fun `last known status is replayed to a late subscriber`() =
        runBlocking {
            // StateReconciliationService подписывается на ApplicationReadyEvent, то есть
            // позже установления WS-соединения: без replay первый CONNECTED терялся и
            // сверка с биржей не запускалась (скан-12 P1).
            manager.onConnected(WsStream.ORDERS, 0, "INIT")

            val event = withTimeout(2000) { manager.events.first() }

            assertEquals(WsConnectionStatus.CONNECTED, event.status)
            assertEquals(WsStream.ORDERS, event.stream)
        }
}
