package com.trading.bot.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Unit-тесты [AlorClient] (roadmap 13.17, P0):
 * REST-пути (парсинг quotes/orders/positions/trades, State Reconciliation,
 * fallback на null/Failed, метрики) поверх реального локального HTTP-сервера —
 * «mock WebClient» как в [com.trading.bot.performance.LlmCycleLoadTest]. Классические
 * сценарии: найден/не найден/неизвестен ордер по idempotency key (outbox-повторы),
 * блокировка market-ордера при широком спреде, slippage-метрика.
 */
class AlorClientTest {
    private val orderTransport = mock<RoutedOrderTransport>()

    private fun buildClient(
        server: FakeAlorServer,
        mode: String,
        registry: SimpleMeterRegistry,
    ): AlorClient {
        val tradingConfig = TradingConfig().apply { this.mode = mode }
        val alorConfig =
            AlorConfig().apply {
                apiUrl = server.baseUrl
                token = "test-token"
                portfolio = "P1"
                retryEnabled = false
                rateLimiterEnabled = false
                circuitBreakerEnabled = false
            }
        return AlorClient(
            tradingConfig,
            alorConfig,
            jacksonObjectMapper(),
            registry,
            AlorTokenProvider(alorConfig, jacksonObjectMapper()),
            orderTransport,
            RetryRegistry.ofDefaults(),
            RateLimiterRegistry.ofDefaults(),
            CircuitBreakerRegistry.ofDefaults(),
        )
    }

    private fun client(
        server: FakeAlorServer,
        mode: String = "LIVE",
    ): AlorClient = buildClient(server, mode, SimpleMeterRegistry())

    private fun clientWithMetrics(
        server: FakeAlorServer,
        mode: String = "LIVE",
    ): Pair<AlorClient, SimpleMeterRegistry> {
        val registry = SimpleMeterRegistry()
        return buildClient(server, mode, registry) to registry
    }

    private suspend fun stubPlaceLimit(result: String) {
        whenever(
            orderTransport.placeLimit(any(), any(), any(), any(), any(), any()),
        ).thenReturn(result)
    }

    @Test
    fun `simulation mode returns fixed snapshot without REST`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val c = client(server, mode = "SIMULATION")

                val snapshot = c.getMarketSnapshot("SBER")

                assertEquals("SBER", snapshot?.ticker)
                assertEquals(0, BigDecimal("100").compareTo(snapshot?.currentPrice))
                assertEquals(0, BigDecimal("99.9").compareTo(snapshot?.bid))
                assertEquals(0, BigDecimal("100.1").compareTo(snapshot?.ask))
                assertEquals(1_000_000L, snapshot?.volume)
                assertTrue(server.requests.isEmpty(), "no REST call expected in SIMULATION")
            }
        }

    @Test
    fun `simulation mode getLastPrice returns 100`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val c = client(server, mode = "SIMULATION")
                assertEquals(0, BigDecimal("100").compareTo(c.getLastPrice("SBER")))
            }
        }

    @Test
    fun `simulation mode reconciliation returns NotFound and empty lists`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val c = client(server, mode = "SIMULATION")

                assertEquals(AlorClient.OrderReconciliation.NotFound, c.reconcileOrderByIdempotencyKey("idem-1", "Si", "buy"))
                assertTrue((c.getOpenOrders() as AlorClient.ReconcileResult.Ok).items.isEmpty())
                assertTrue((c.getPositions() as AlorClient.ReconcileResult.Ok).items.isEmpty())
                assertTrue((c.getRecentTrades() as AlorClient.ReconcileResult.Ok).items.isEmpty())
                assertNull(c.verifyOrder("ord-1"))
                assertEquals("sim-SBER-idem-1", c.placeMarketOrder("SBER", "buy", 1, "idem-1"))
                assertTrue(server.requests.isEmpty())
            }
        }

    @Test
    fun `getMarketSnapshot parses quotes and sends bearer token`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val (c, registry) = clientWithMetrics(server)
                server.quotesResponse = """{"lastPrice":"123.45","bid":"123.0","ask":"123.9","volume":250000}"""

                val snapshot = c.getMarketSnapshot("SBER")

                assertEquals(0, BigDecimal("123.45").compareTo(snapshot?.currentPrice))
                assertEquals(0, BigDecimal("123.0").compareTo(snapshot?.bid))
                assertEquals(0, BigDecimal("123.9").compareTo(snapshot?.ask))
                assertEquals(250_000L, snapshot?.volume)
                assertTrue(server.requests.any { it.second == "Bearer test-token" }, "Authorization header must be set")
                assertEquals(1.0, registry.counter("alor.quotes.ok", "ticker", "SBER").count())
            }
        }

    @Test
    fun `getMarketSnapshot returns null on server error`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val (c, registry) = clientWithMetrics(server)
                server.statusCode = 500

                val snapshot = c.getMarketSnapshot("SBER")

                assertNull(snapshot)
                assertEquals(1.0, registry.counter("alor.quotes.error", "ticker", "SBER").count())
            }
        }

    @Test
    fun `verifyOrder parses execution and records slippage`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val (c, registry) = clientWithMetrics(server)
                server.orderByIdResponse = """{"status":"FILLED","filledQty":2,"filledPrice":"99.5"}"""

                val execution = c.verifyOrder("ord-1", expectedPrice = BigDecimal("100"))

                assertEquals("FILLED", execution?.status)
                assertEquals(2, execution?.filledQuantity)
                assertEquals(0, BigDecimal("99.5").compareTo(execution?.avgPrice))
                assertEquals(1.0, registry.counter("trade.slippage.rub").count())
            }
        }

    @Test
    fun `verifyOrder returns null on server error`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val c = client(server)
                server.statusCode = 503
                assertNull(c.verifyOrder("ord-1"))
            }
        }

    @Test
    fun `verifyOrder falls back to filledQuantity when filledQty absent (EXEC-2)`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val c = client(server)
                server.orderByIdResponse = """{"status":"FILLED","filledQuantity":2,"filledPrice":"99.5"}"""

                val execution = c.verifyOrder("ord-1", expectedPrice = BigDecimal("100"))

                assertEquals("FILLED", execution?.status)
                assertEquals(2, execution?.filledQuantity)
                assertEquals(0, BigDecimal("99.5").compareTo(execution?.avgPrice))
            }
        }

    @Test
    fun `getOpenOrders parses orders with fallback fields`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val c = client(server)
                server.ordersResponse =
                    """
                    [
                      {"orderNumber":"ord-1","ticker":"SBER","side":"buy","status":"Working","quantity":10,"filledQty":4,"filledPrice":"250.5","time":1700000000},
                      {"id":"ord-2","ticker":"GAZP","side":"sell","filledQuantity":0,"avgFillPrice":"150.0","time":1700000000123}
                    ]
                    """.trimIndent()

                val result = c.getOpenOrders()

                assertTrue(result is AlorClient.ReconcileResult.Ok)
                val items = (result as AlorClient.ReconcileResult.Ok).items
                assertEquals(2, items.size)
                assertEquals("ord-1", items[0].orderId)
                assertEquals("SBER", items[0].ticker)
                assertEquals(10, items[0].quantity)
                assertEquals(4, items[0].filledQty)
                assertEquals(0, BigDecimal("250.5").compareTo(items[0].avgPrice))
                assertEquals(1700000000L, items[0].time?.epochSecond)
                assertEquals("ord-2", items[1].orderId)
                assertEquals("GAZP", items[1].ticker)
                assertEquals(0, BigDecimal("150.0").compareTo(items[1].avgPrice))
                assertEquals(1700000000L, items[1].time?.epochSecond)
            }
        }

    @Test
    fun `getOpenOrders is Failed on server error`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val (c, registry) = clientWithMetrics(server)
                server.statusCode = 500

                val result = c.getOpenOrders()

                assertEquals(AlorClient.ReconcileResult.Failed, result)
                assertEquals(1.0, registry.counter("alor.reconcile.fetch_error", "kind", "orders").count())
            }
        }

    @Test
    fun `getPositions parses positions with fallback fields`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val c = client(server)
                server.positionsResponse =
                    """
                    [
                      {"ticker":"SBER","qty":5,"averagePrice":"250.5","time":1700000000},
                      {"symbol":"GAZP","quantity":-3,"avgPrice":"150.0","time":1700000000000}
                    ]
                    """.trimIndent()

                val result = c.getPositions()

                assertTrue(result is AlorClient.ReconcileResult.Ok)
                val items = (result as AlorClient.ReconcileResult.Ok).items
                assertEquals(2, items.size)
                assertEquals("SBER", items[0].ticker)
                assertEquals(5L, items[0].qty)
                assertEquals(0, BigDecimal("250.5").compareTo(items[0].avgPrice))
                assertEquals("GAZP", items[1].ticker)
                assertEquals(-3L, items[1].qty)
                assertEquals(0, BigDecimal("150.0").compareTo(items[1].avgPrice))
            }
        }

    @Test
    fun `getPositions accepts wrapped object response`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val c = client(server)
                server.positionsResponse = """{"positions":[{"ticker":"SBER","quantity":7,"avgFillPrice":"260.0"}]}"""

                val result = c.getPositions()

                assertTrue(result is AlorClient.ReconcileResult.Ok)
                val items = (result as AlorClient.ReconcileResult.Ok).items
                assertEquals(1, items.size)
                assertEquals("SBER", items[0].ticker)
                assertEquals(7L, items[0].qty)
                assertEquals(0, BigDecimal("260.0").compareTo(items[0].avgPrice))
            }
        }

    @Test
    fun `getRecentTrades parses trades and skips price-less`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val c = client(server)
                server.tradesResponse =
                    """
                    [
                      {"id":"t-1","orderId":"ord-1","ticker":"SBER","side":"buy","quantity":5,"price":"250.5","time":1700000000},
                      {"tradeId":"t-2","orderNumber":"ord-2","symbol":"GAZP","side":"sell","qty":3,"price":"150.0"},
                      {"id":"t-3","ticker":"NOPE","quantity":1}
                    ]
                    """.trimIndent()

                val result = c.getRecentTrades()

                assertTrue(result is AlorClient.ReconcileResult.Ok)
                val items = (result as AlorClient.ReconcileResult.Ok).items
                assertEquals(2, items.size)
                assertEquals("t-1", items[0].id)
                assertEquals("ord-1", items[0].orderId)
                assertEquals(5, items[0].quantity)
                assertEquals(0, BigDecimal("250.5").compareTo(items[0].price))
                assertEquals("t-2", items[1].id)
                assertEquals("GAZP", items[1].ticker)
                assertEquals(3, items[1].quantity)
            }
        }

    @Test
    fun `reconciliation Found uses idempotency key match`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val (c, registry) = clientWithMetrics(server)
                server.ordersResponse =
                    """
                    [
                      {"id":"idem-9","ticker":"Si","side":"buy","orderNumber":"ord-9","filledQty":3,"filledPrice":"92000"},
                      {"id":"idem-other","ticker":"Si","side":"buy","orderNumber":"ord-x"}
                    ]
                    """.trimIndent()

                val result = c.reconcileOrderByIdempotencyKey("idem-9", "Si", "buy")

                assertEquals(AlorClient.OrderReconciliation.Found("ord-9", 3, BigDecimal("92000")), result)
                assertEquals(1.0, registry.counter("alor.reconcile", "result", "FOUND").count())
            }
        }

    @Test
    fun `reconciliation skips orders with different ticker or side`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val c = client(server)
                server.ordersResponse = """[{"id":"idem-9","ticker":"Si","side":"buy","orderNumber":"ord-9"}]"""

                val wrongTicker = c.reconcileOrderByIdempotencyKey("idem-9", "SBER", "buy")
                val wrongSide = c.reconcileOrderByIdempotencyKey("idem-9", "Si", "sell")

                assertEquals(AlorClient.OrderReconciliation.NotFound, wrongTicker)
                assertEquals(AlorClient.OrderReconciliation.NotFound, wrongSide)
            }
        }

    @Test
    fun `reconciliation NotFound when no matching order`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val (c, registry) = clientWithMetrics(server)
                server.ordersResponse = """[]"""

                val result = c.reconcileOrderByIdempotencyKey("idem-1", "Si", "buy")

                assertEquals(AlorClient.OrderReconciliation.NotFound, result)
                assertEquals(1.0, registry.counter("alor.reconcile", "result", "NOT_FOUND").count())
            }
        }

    @Test
    fun `reconciliation Unknown on exchange failure`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val (c, registry) = clientWithMetrics(server)
                server.statusCode = 500

                val result = c.reconcileOrderByIdempotencyKey("idem-1", "Si", "buy")

                assertEquals(AlorClient.OrderReconciliation.Unknown, result)
                assertEquals(1.0, registry.counter("alor.reconcile", "result", "UNKNOWN").count())
            }
        }

    @Test
    fun `blank idempotency key is NotFound without HTTP`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val c = client(server)

                val result = c.reconcileOrderByIdempotencyKey(" ", "Si", "buy")

                assertEquals(AlorClient.OrderReconciliation.NotFound, result)
                assertTrue(server.requests.isEmpty())
            }
        }

    @Test
    fun `market order blocked on wide spread`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val (c, registry) = clientWithMetrics(server)
                server.quotesResponse = """{"lastPrice":"100","bid":"99.9","ask":"100.6","volume":100000}"""

                val orderId = c.placeMarketOrder("SBER", "buy", 1, "idem-1")

                assertNull(orderId)
                assertEquals(1.0, registry.counter("alor.order.blocked", "reason", "WIDE_SPREAD").count())
                verify(orderTransport, never()).placeLimit(any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `market order forced on wide spread for emergency close`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val (c, registry) = clientWithMetrics(server)
                server.quotesResponse = """{"lastPrice":"100","bid":"99.9","ask":"100.6","volume":100000}"""
                stubPlaceLimit("ord-force-1")

                val orderId = c.placeMarketOrder("SBER", "buy", 1, "idem-1", forceMarket = true)

                assertEquals("ord-force-1", orderId)
                verify(orderTransport).placeLimit("SBER", "buy", 1, BigDecimal("100.6"), "idem-1", "P1")
                assertEquals(0.0, registry.counter("alor.order.blocked", "reason", "WIDE_SPREAD").count())
                assertEquals(1.0, registry.counter("alor.order.forced_market", "ticker", "SBER").count())
            }
        }

    @Test
    fun `market order placed at ask on narrow spread`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val (c, registry) = clientWithMetrics(server)
                server.quotesResponse = """{"lastPrice":"100","bid":"99.9","ask":"100.1","volume":100000}"""
                stubPlaceLimit("ord-market-1")

                val orderId = c.placeMarketOrder("SBER", "buy", 1, "idem-1")

                assertEquals("ord-market-1", orderId)
                verify(orderTransport).placeLimit("SBER", "buy", 1, BigDecimal("100.1"), "idem-1", "P1")
                assertEquals(1.0, registry.counter("alor.order.placed", "type", "market", "status", "OK").count())
            }
        }

    @Test
    fun `placeLimitOrder delegates to order transport`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val c = client(server)
                stubPlaceLimit("ord-limit-1")

                val orderId = c.placeLimitOrder("SBER", "buy", 1, BigDecimal("250.0"), "idem-1")

                assertEquals("ord-limit-1", orderId)
                verify(orderTransport).placeLimit("SBER", "buy", 1, BigDecimal("250.0"), "idem-1", "P1")
            }
        }

    private class FakeAlorServer : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())

        var statusCode = 200
        var quotesResponse = """{"lastPrice":"100","bid":"99.9","ask":"100.1","volume":1000000}"""
        var ordersResponse = "[]"
        var positionsResponse = "[]"
        var tradesResponse = "[]"
        var orderByIdResponse = """{"status":"FILLED","filledQty":1,"filledPrice":"99.5"}"""

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { handle(it) }
            server.start()
        }

        private fun handle(exchange: HttpExchange) {
            val auth = exchange.requestHeaders.getFirst("Authorization")
            requests.add(exchange.requestURI.path to auth)
            val body =
                when {
                    statusCode != 200 -> "error".toByteArray()
                    exchange.requestURI.path.endsWith("/quotes") -> quotesResponse.toByteArray()
                    exchange.requestURI.path.contains("/orders/") -> orderByIdResponse.toByteArray()
                    exchange.requestURI.path.contains("/positions") -> positionsResponse.toByteArray()
                    exchange.requestURI.path.contains("/trades") -> tradesResponse.toByteArray()
                    exchange.requestURI.path.contains("/orders") -> ordersResponse.toByteArray()
                    else -> quotesResponse.toByteArray()
                }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(statusCode, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        override fun close() {
            server.stop(0)
        }
    }
}
