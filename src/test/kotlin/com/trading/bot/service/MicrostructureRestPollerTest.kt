package com.trading.bot.service

import com.sun.net.httpserver.HttpServer
import com.trading.bot.client.AlorTokenProvider
import com.trading.bot.client.QuoteTick
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.MicrostructureConfig
import com.trading.bot.config.TradingConfig
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [MicrostructureRestPoller]: цикл REST-поллинга L1 и его fail-soft ветки.
 *
 * Парсер покрыт отдельно ([MicrostructureRestPollerParseTest]); здесь проверяется
 * **обвязка**: запуск/остановка корутины, обработка HTTP-ошибок, неполного стакана,
 * гейта свежести и вырожденного `ms_timestamp`. Транспорт — локальный JDK
 * [HttpServer] на 127.0.0.1 (как в `MoexFundingProviderTest`), реальная сеть не
 * используется.
 */
class MicrostructureRestPollerTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val objectMapper = ObjectMapper()
    private val recorder: MicrostructureRecorder = mock()
    private val tokenProvider: AlorTokenProvider =
        Mockito.mock(AlorTokenProvider::class.java) { invocation ->
            if (invocation.method.name == "actualToken") "token" else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    private val alorConfig = AlorConfig()
    private val microstructureConfig = MicrostructureConfig()
    private val tradingConfig = TradingConfig()
    private val servers = mutableListOf<HttpServer>()

    @AfterEach
    fun tearDown() {
        servers.forEach { it.stop(0) }
        servers.clear()
    }

    private fun poller(): MicrostructureRestPoller =
        MicrostructureRestPoller(
            alorConfig = alorConfig,
            microstructureConfig = microstructureConfig,
            tradingConfig = tradingConfig,
            tokenProvider = tokenProvider,
            recorder = recorder,
            meterRegistry = meterRegistry,
            objectMapper = objectMapper,
        )

    private fun counter(
        name: String,
        vararg tags: String,
    ): Counter = meterRegistry.counter(name, *tags)

    private fun startServer(body: () -> Pair<Int, String>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val (status, payload) = body()
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        servers += server
        alorConfig.apiUrl = "http://127.0.0.1:${server.address.port}"
        return server
    }

    private fun l1Body(
        exchangeTime: Long = Instant.now().toEpochMilli(),
        bid: String = "12.441",
        ask: String = "12.442",
    ): String =
        """
        {
          "bids": [{"price": $bid, "volume": 10}],
          "asks": [{"price": $ask, "volume": 20}],
          "ms_timestamp": $exchangeTime
        }
        """.trimIndent()

    @Test
    fun `pollOne records fresh snapshot and marks poll ok`() {
        startServer { 200 to l1Body() }

        runBlocking { poller().pollOne("CNYRUBF") }

        val captured = org.mockito.kotlin.argumentCaptor<QuoteTick>()
        verify(recorder).record(captured.capture())
        assertEquals("CNYRUBF", captured.firstValue.ticker)
        assertEquals(1.0, counter("microstructure.rest.poll", "ticker", "CNYRUBF", "status", "ok").count())
    }

    @Test
    fun `pollOne counts HTTP failure and never records`() {
        startServer { 500 to "boom" }

        runBlocking { poller().pollOne("CNYRUBF") }

        verify(recorder, never()).record(any())
        assertEquals(
            1.0,
            counter("microstructure.rest.poll", "ticker", "CNYRUBF", "status", "error").count(),
        )
    }

    @Test
    fun `pollOne counts unparsable payload as invalid`() {
        startServer { 200 to "{not-json" }

        runBlocking { poller().pollOne("CNYRUBF") }

        verify(recorder, never()).record(any())
        assertEquals(
            1.0,
            counter("microstructure.rest.poll", "ticker", "CNYRUBF", "status", "invalid").count(),
        )
    }

    @Test
    fun `pollOne skips one-sided order book as incomplete`() {
        startServer { 200 to """{"bids": [{"price": 12.441, "volume": 10}], "asks": []}""" }

        runBlocking { poller().pollOne("CNYRUBF") }

        verify(recorder, never()).record(any())
        assertEquals(
            1.0,
            counter("microstructure.rest.bbo", "ticker", "CNYRUBF", "reason", "incomplete").count(),
        )
    }

    @Test
    fun `pollOne skips stale quote instead of writing weekend duplicate`() {
        startServer { 200 to l1Body(exchangeTime = Instant.now().minusSeconds(3_600).toEpochMilli()) }

        runBlocking { poller().pollOne("CNYRUBF") }

        verify(recorder, never()).record(any())
        assertEquals(
            1.0,
            counter("microstructure.rest.bbo", "ticker", "CNYRUBF", "reason", "stale").count(),
        )
    }

    @Test
    fun `pollOne treats zero timestamp as missing exchange time`() {
        startServer { 200 to l1Body(exchangeTime = 0L) }

        runBlocking { poller().pollOne("CNYRUBF") }

        verify(recorder, never()).record(any())
        assertEquals(
            1.0,
            counter("microstructure.rest.bbo", "ticker", "CNYRUBF", "reason", "missing_exchange_time").count(),
        )
    }

    @Test
    fun `start does nothing when polling is disabled`() {
        val poller = poller()

        poller.start()
        poller.stop()
        assertNull(meterRegistry.find("microstructure.rest.poll").counter())
    }

    @Test
    fun `start warns and stays idle when ticker list is empty`() {
        microstructureConfig.restPollingEnabled = true
        tradingConfig.tickers = emptyList()
        val poller = poller()

        poller.start()
        poller.stop()

        assertNull(meterRegistry.find("microstructure.rest.poll").counter())
    }

    @Test
    fun `start polls every configured ticker in background loop`() {
        startServer { 200 to l1Body() }
        microstructureConfig.restPollingEnabled = true
        microstructureConfig.restPollIntervalMs = 200
        microstructureConfig.tickers = listOf("CNYRUBF", "SBER")
        val polls = CountDownLatch(2)
        doAnswer {
            polls.countDown()
            null
        }.whenever(recorder).record(any())

        val poller = poller()
        poller.start()
        try {
            assertTrue(polls.await(5, TimeUnit.SECONDS), "background loop must poll every ticker")
        } finally {
            poller.stop()
        }
    }
}
