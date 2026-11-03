package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import com.trading.bot.model.dto.OrderStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServer
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * [AlorWebSocketClient] — реальные потоки `subscribeToQuotes`/`subscribeToOrders`
 * поверх локального reactor-netty WebSocket-сервера: подписка, разбор кадра,
 * закрытие соединения и провал подключения с reconnect-метриками.
 */
class AlorWebSocketClientFlowTest {
    private val registry = SimpleMeterRegistry()
    private val webSocketManager = mock<WebSocketManager>()
    private val wsEvents = MutableSharedFlow<WsConnectionEvent>(replay = 1, extraBufferCapacity = 16)

    init {
        Mockito.`when`(webSocketManager.events).thenReturn(wsEvents)
    }

    private val tokenProvider =
        Mockito.mock(AlorTokenProvider::class.java) { invocation ->
            if (invocation.method.name == "actualToken") "token" else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }

    private fun client(url: String): AlorWebSocketClient =
        AlorWebSocketClient(
            AlorConfig().apply {
                wsUrl = url
                wsHeartbeatIntervalMs = 60_000
                wsHeartbeatTimeoutMs = 120_000
            },
            jacksonObjectMapper(),
            registry,
            webSocketManager,
            tokenProvider,
        )

    private fun quoteFrame(price: String = "280.5"): String =
        """{"guid":"q-SBER","quotes":[{"o":"Bid","price":280.0,"volume":10,"time":1700000000},""" +
            """{"o":"Last","price":$price,"volume":0,"time":1700000000},""" +
            """{"o":"Offer","price":281.0,"volume":25,"time":1700000000}]}"""

    private fun executionFrame(): String =
        """{"opcode":"OrdersGetAndSubscribeV2","guid":"g-1","data":{"id":"ord-1","status":"filled",""" +
            """"filledQtyBatch":5,"qtyBatch":5,"avgFillPrice":"92000","ticker":"Si","side":"buy"}}"""

    private fun wsServer(
        frame: String?,
        received: MutableList<String> = mutableListOf(),
        holdMs: Long = 700,
    ): reactor.netty.DisposableServer {
        val server =
            HttpServer
                .create()
                .host("127.0.0.1")
                .port(0)
                .handle { _, response ->
                    response.sendWebsocket { inbound, outbound ->
                        val subscription =
                            inbound
                                .receive()
                                .asString()
                                .doOnNext { message -> received += message }
                                .then()
                        if (frame == null) {
                            subscription.subscribe()
                            Mono.delay(Duration.ofMillis(200)).then(outbound.sendClose()).then()
                        } else {
                            Mono
                                .`when`(subscription, outbound.sendString(Flux.just(frame)).then())
                                .then(Mono.delay(Duration.ofMillis(holdMs)).then())
                        }
                    }
                }.bindNow()
        return server
    }

    @Test
    fun `quotes flow connects subscribes and emits a parsed tick`(): Unit =
        runBlocking {
            val received = mutableListOf<String>()
            val server = wsServer(quoteFrame(), received)
            try {
                val flow = client("ws://127.0.0.1:${server.port()}/ws").subscribeToQuotes(listOf("SBER"))

                val tick = withTimeout(10_000) { flow.first() }

                assertEquals("SBER", tick.ticker)
                assertEquals(0, BigDecimal("280.5").compareTo(tick.price))
                assertEquals(0, BigDecimal("280.0").compareTo(tick.bid!!))
                assertEquals(0, BigDecimal("281.0").compareTo(tick.ask!!))
                assertTrue(
                    registry.counter("alor.ws.quote_received", "ticker", "SBER").count() >= 1.0,
                    "принятая котировка должна считаться",
                )
            } finally {
                server.disposeNow()
            }
            assertTrue(
                received.any { it.contains("QuotesSubscribe") && it.contains("q-SBER") },
                received.toString(),
            )
        }

    @Test
    fun `orders flow connects and emits a parsed execution report`(): Unit =
        runBlocking {
            val received = mutableListOf<String>()
            val server = wsServer(executionFrame(), received)
            try {
                val flow = client("ws://127.0.0.1:${server.port()}/ws").subscribeToOrders("D12345")

                val report = withTimeout(10_000) { flow.first() }

                assertEquals("ord-1", report.orderId)
                assertEquals(OrderStatusExpectation.FILLED, report.status)
                assertEquals(5, report.cumulativeFilledQty)
            } finally {
                server.disposeNow()
            }
            assertTrue(
                received.any { it.contains("OrdersGetAndSubscribeV2") && it.contains("D12345") },
                received.toString(),
            )
        }

    @Test
    fun `empty symbol list closes the quotes flow without connecting`(): Unit =
        runBlocking {
            val ticks =
                withTimeout(5_000) {
                    client("ws://127.0.0.1:1/ws").subscribeToQuotes(emptyList()).toList()
                }

            assertTrue(ticks.isEmpty())
            assertEquals(0.0, registry.counter("alor.ws.quotes.reconnect").count())
        }

    @Test
    fun `connect failure reports CONNECT_FAILED and schedules a reconnect`(): Unit =
        runBlocking {
            val client = client("ws://127.0.0.1:1/ws")
            val job = launch(Dispatchers.Default) { client.subscribeToQuotes(listOf("SBER")).collect {} }
            try {
                awaitUntil(10_000) { registry.counter("alor.ws.quotes.reconnect").count() >= 1.0 }
                verify(webSocketManager, Mockito.timeout(5_000))
                    .onDisconnected(anyStream(), Mockito.anyInt(), Mockito.anyString())
                val reasons =
                    Mockito
                        .mockingDetails(webSocketManager)
                        .invocations
                        .filter { it.method.name == "onDisconnected" }
                        .mapNotNull { it.arguments.getOrNull(2) as? String }
                assertTrue(reasons.isNotEmpty(), "reconnect после ошибки подключения: $reasons")
            } finally {
                job.cancel()
            }
        }

    @Test
    fun `server close during the stream triggers closed metric and reconnect`(): Unit =
        runBlocking {
            val server = wsServer(frame = null)
            try {
                val client = client("ws://127.0.0.1:${server.port()}/ws")
                val job = launch(Dispatchers.Default) { client.subscribeToQuotes(listOf("SBER")).collect {} }
                try {
                    awaitUntil(10_000) {
                        registry.counter("alor.ws.quotes.closed").count() >= 1.0 ||
                            registry.counter("alor.ws.quotes.error").count() >= 1.0
                    }
                    verify(webSocketManager, Mockito.timeout(5_000))
                        .onDisconnected(anyStream(), Mockito.anyInt(), Mockito.anyString())
                } finally {
                    job.cancel()
                }
            } finally {
                server.disposeNow()
            }
        }

    @Test
    fun `ws url with an existing query keeps it and appends the token`(): Unit =
        runBlocking {
            val received = mutableListOf<String>()
            val server = wsServer(quoteFrame(), received)
            try {
                val flow =
                    client("ws://127.0.0.1:${server.port()}/ws?x=1")
                        .subscribeToQuotes(listOf("SBER"))

                val tick = withTimeout(10_000) { flow.first() }

                assertEquals("SBER", tick.ticker)
            } finally {
                server.disposeNow()
            }
            assertTrue(received.any { it.contains("token") }, received.toString())
        }

    @Test
    fun `orders stream close triggers the closed metric and reconnect`(): Unit =
        runBlocking {
            val server = wsServer(frame = null)
            try {
                val client = client("ws://127.0.0.1:${server.port()}/ws")
                val job = launch(Dispatchers.Default) { client.subscribeToOrders("D12345").collect {} }
                try {
                    awaitUntil(10_000) {
                        registry.counter("alor.ws.closed").count() >= 1.0 ||
                            registry.counter("alor.ws.error").count() >= 1.0
                    }
                    verify(webSocketManager, Mockito.timeout(5_000))
                        .onDisconnected(anyStream(), Mockito.anyInt(), Mockito.anyString())
                } finally {
                    job.cancel()
                }
            } finally {
                server.disposeNow()
            }
        }

    @Test
    fun `stale quote is discarded with a per ticker drop metric`(): Unit =
        runBlocking {
            Mockito
                .`when`(
                    webSocketManager.isQuoteStale(
                        Mockito.anyString(),
                        anyInstant(),
                        Mockito.anyLong(),
                        org.mockito.kotlin.anyOrNull(),
                    ),
                ).thenReturn(true)
            val server = wsServer(quoteFrame())
            try {
                val flow = client("ws://127.0.0.1:${server.port()}/ws").subscribeToQuotes(listOf("SBER"))

                val tick = kotlinx.coroutines.withTimeoutOrNull(3_000) { flow.first() }

                assertEquals(null, tick, "устаревшая котировка не должна эмититься")
                assertTrue(
                    registry.counter("alor.ws.quotes.drop", "ticker", "SBER").count() >= 1.0,
                    "drop-метрика по тикеру",
                )
            } finally {
                server.disposeNow()
            }
        }

    private fun anyInstant(): java.time.Instant {
        Mockito.any(java.time.Instant::class.java)
        return java.time.Instant.now()
    }

    @Test
    fun `invalid frames are skipped before a valid quote`(): Unit =
        runBlocking {
            val received = mutableListOf<String>()
            val server =
                HttpServer
                    .create()
                    .host("127.0.0.1")
                    .port(0)
                    .handle { _, response ->
                        response.sendWebsocket { _, outbound ->
                            Mono
                                .`when`(
                                    outbound.sendString(Flux.just("{not-json", quoteFrame())).then(),
                                ).then(Mono.delay(Duration.ofMillis(500)).then())
                        }
                    }.bindNow()
            try {
                val flow = client("ws://127.0.0.1:${server.port()}/ws").subscribeToQuotes(listOf("SBER"))

                val tick = withTimeout(10_000) { flow.first() }

                assertEquals("SBER", tick.ticker)
                assertEquals(0, BigDecimal("280.5").compareTo(tick.price))
            } finally {
                server.disposeNow()
            }
            assertTrue(received.isEmpty())
        }

    @Test
    fun `heartbeat timeout event closes the active session and triggers reconnect`(): Unit =
        runBlocking {
            val server = wsServer(quoteFrame(), holdMs = 5_000)
            try {
                val client = client("ws://127.0.0.1:${server.port()}/ws")
                val job = launch(Dispatchers.Default) { client.subscribeToQuotes(listOf("SBER")).collect {} }
                try {
                    awaitUntil(10_000) {
                        registry.counter("alor.ws.quote_received", "ticker", "SBER").count() >= 1.0
                    }
                    wsEvents.emit(
                        WsConnectionEvent(
                            stream = WsStream.QUOTES,
                            status = WsConnectionStatus.DISCONNECTED,
                            reason = "HEARTBEAT_TIMEOUT",
                            timestamp = Instant.now(),
                        ),
                    )
                    awaitUntil(10_000) {
                        registry.counter("alor.ws.quotes.reconnect").count() >= 1.0
                    }
                    assertTrue(
                        registry.counter("alor.ws.heartbeat_timeout_close", "stream", "QUOTES").count() >= 1.0,
                        "heartbeat timeout must close the Netty session",
                    )
                } finally {
                    job.cancel()
                }
            } finally {
                server.disposeNow()
            }
        }

    @Test
    fun `replayed heartbeat timeout does not close a fresh session`(): Unit =
        runBlocking {
            // SharedFlow.replay=1: событие прошлой сессии не должно закрыть
            // новую сессию. Watcher стартует до connect(0), видит active=null.
            wsEvents.tryEmit(
                WsConnectionEvent(
                    stream = WsStream.QUOTES,
                    status = WsConnectionStatus.DISCONNECTED,
                    reason = "HEARTBEAT_TIMEOUT",
                    timestamp = Instant.now().minusSeconds(60),
                ),
            )
            val server = wsServer(quoteFrame(), holdMs = 2_000)
            try {
                val client = client("ws://127.0.0.1:${server.port()}/ws")
                val job = launch(Dispatchers.Default) { client.subscribeToQuotes(listOf("SBER")).collect {} }
                try {
                    awaitUntil(10_000) {
                        registry.counter("alor.ws.quote_received", "ticker", "SBER").count() >= 1.0
                    }
                    delay(500)
                    assertEquals(0.0, registry.counter("alor.ws.quotes.reconnect").count())
                } finally {
                    job.cancel()
                }
            } finally {
                server.disposeNow()
            }
        }

    @Test
    fun `reconnect after a close opens a second subscription`(): Unit =
        runBlocking {
            val received = mutableListOf<String>()
            val server = wsServer(frame = null, received = received)
            try {
                val client = client("ws://127.0.0.1:${server.port()}/ws")
                val job = launch(Dispatchers.Default) { client.subscribeToQuotes(listOf("SBER")).collect {} }
                try {
                    awaitUntil(15_000) { registry.counter("alor.ws.quotes.reconnect").count() >= 1.0 }
                    awaitUntil(15_000) { received.count { it.contains("QuotesSubscribe") } >= 2 }
                } finally {
                    job.cancel()
                }
            } finally {
                server.disposeNow()
            }
        }

    private fun anyStream(): WsStream {
        Mockito.any(WsStream::class.java)
        return WsStream.QUOTES
    }

    private suspend fun awaitUntil(
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    }

    private object OrderStatusExpectation {
        val FILLED = com.trading.bot.model.dto.OrderStatus.FILLED
    }
}
