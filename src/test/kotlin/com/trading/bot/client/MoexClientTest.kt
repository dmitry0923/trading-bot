package com.trading.bot.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trading.bot.domain.risk.OptionKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Unit-тесты [MoexClient] (ISS API) поверх локального HTTP-сервера: разбор свечей,
 * пагинация, fallback акции→фьючерсы, индексы волатильности, FORTS-опционы,
 * склейка контрактов фьючерса и все error-пути. Внешняя сеть не используется:
 * `baseUrl` (private val, захардкожен в проде) подменяется на адрес фейка рефлексией —
 * это тестовый приём, production-код не меняется.
 */
class MoexClientTest {
    private val mapper = jacksonObjectMapper()

    private fun client(server: FakeIssServer): MoexClient {
        val client = MoexClient(mapper)
        val field = MoexClient::class.java.getDeclaredField("baseUrl")
        field.isAccessible = true
        field.set(client, server.baseUrl)
        return client
    }

    private fun candle(
        at: LocalDateTime,
        close: String = "100.5",
    ): String = """["${at.format(TIME)}","100","101","99","$close","50"]"""

    private fun candlesJson(rows: List<String>): String =
        """{"candles":{"columns":["begin","open","high","low","close","volume"],"data":[${rows.joinToString(",")}]}}"""

    private fun emptyCandles(): String = candlesJson(emptyList())

    @Test
    fun `getCandles parses stock candles sorted by time`() =
        runBlocking {
            FakeIssServer().use { server ->
                val later = LocalDateTime.of(2026, 8, 7, 10, 10)
                server.stockCandles = candlesJson(listOf(candle(later, "101.5"), candle(later.minusMinutes(10))))
                val client = client(server)

                val candles = client.getCandles("SBER", later.minusDays(1))

                assertEquals(2, candles.size)
                assertEquals(later.minusMinutes(10), candles[0].time)
                assertEquals(later, candles[1].time)
                assertEquals(0, BigDecimal("101.5").compareTo(candles[1].closePrice))
                assertEquals("SBER", candles[0].ticker)
                assertEquals("MINUTE_10", candles[0].timeframe)
                assertEquals(50L, candles[0].volume)
                assertTrue(
                    server.requests
                        .single()
                        .path
                        .contains("/boards/TQBR/securities/SBER/candles.json"),
                )
            }
        }

    @Test
    fun `getCandles falls back to FORTS when stock market returns nothing`() =
        runBlocking {
            FakeIssServer().use { server ->
                server.stockCandles = emptyCandles()
                server.futuresCandles = candlesJson(listOf(candle(LocalDateTime.of(2026, 8, 7, 10, 0))))
                val client = client(server)

                val candles = client.getCandles("RIU6", LocalDateTime.of(2026, 8, 7, 9, 0))

                assertEquals(1, candles.size)
                assertEquals(2, server.requests.size)
                assertTrue(server.requests[0].path.contains("/boards/TQBR/"))
                assertTrue(server.requests[1].path.contains("/boards/RFUD/"))
            }
        }

    @Test
    fun `getCandles skips malformed rows and returns empty on server error`() =
        runBlocking {
            FakeIssServer().use { server ->
                val valid = candle(LocalDateTime.of(2026, 8, 7, 10, 0))
                server.stockCandles =
                    """{"candles":{"columns":["begin","open","high","low","close","volume"],
                       "data":[$valid,["bad-date","1","1","1","1","1"],["2026-08-07 10:10:00","x","1","1","1","1"]]}}"""
                val client = client(server)

                assertEquals(1, client.getCandles("SBER", LocalDateTime.of(2026, 8, 7, 10, 0)).size)

                server.statusCode = 500
                assertTrue(client.getCandles("SBER", LocalDateTime.of(2026, 8, 7, 10, 0)).isEmpty())
            }
        }

    @Test
    fun `getCandlesPaged follows pagination until a short page`() =
        runBlocking {
            FakeIssServer().use { server ->
                val base = LocalDateTime.of(2026, 8, 7, 10, 0)
                val firstPage = (0 until 500).map { candle(base.plusMinutes(it.toLong())) }
                server.paginatedStock = { start ->
                    if (start == 0) candlesJson(firstPage) else candlesJson(listOf(candle(base.plusMinutes(500))))
                }
                val client = client(server)

                val candles = client.getCandlesPaged("SBER", base, base.plusDays(1))

                assertEquals(501, candles.size)
                assertEquals(base, candles.first().time)
                assertEquals(base.plusMinutes(500), candles.last().time)
                val starts =
                    server.requests
                        .filter { it.path.contains("/shares/") }
                        .map { request -> request.rawQuery.substringAfter("start=").substringBefore("&") }
                assertEquals(listOf("0", "500"), starts)
            }
        }

    @Test
    fun `getCandlesPaged returns empty when every page fails`() =
        runBlocking {
            FakeIssServer().use { server ->
                server.statusCode = 500
                val client = client(server)

                assertTrue(
                    client.getCandlesPaged("SBER", LocalDateTime.of(2026, 8, 7, 10, 0), LocalDateTime.of(2026, 8, 8, 10, 0)).isEmpty(),
                )
            }
        }

    @Test
    fun `getVolatilityIndex parses LAST column and tolerates missing data`() =
        runBlocking {
            FakeIssServer().use { server ->
                server.indexQuote = """{"marketdata":{"columns":["SECID","LAST"],"data":[["RVI","45.5"]]}}"""
                val client = client(server)
                assertEquals(0, BigDecimal("45.5").compareTo(client.getVolatilityIndex()))

                server.indexQuote = """{"marketdata":{"columns":["SECID","LAST"],"data":[]}}"""
                assertNull(client.getVolatilityIndex())

                server.indexQuote = """{"marketdata":{"columns":["SECID","BID"],"data":[["RVI","1"]]}}"""
                assertNull(client.getVolatilityIndex())

                server.statusCode = 500
                assertNull(client.getVolatilityIndex())
            }
        }

    @Test
    fun `getVolatilityIndexDailyCloses parses closes and respects error path`() =
        runBlocking {
            FakeIssServer().use { server ->
                server.indexDaily =
                    """
                    {"candles":{"columns":["begin","close"],
                     "data":[["2026-08-06","20.5"],["2026-08-07","bad"],["2026-08-05","18.0"]]}}
                    """.trimIndent()
                val client = client(server)

                assertEquals(listOf(18.0, 20.5), client.getVolatilityIndexDailyCloses(from = LocalDate.of(2026, 7, 1)))

                server.indexDaily = """{"candles":{"columns":["begin"],"data":[]}}"""
                assertTrue(client.getVolatilityIndexDailyCloses(from = LocalDate.of(2026, 7, 1)).isEmpty())

                server.statusCode = 503
                assertTrue(client.getVolatilityIndexDailyCloses(from = LocalDate.of(2026, 7, 1)).isEmpty())
            }
        }

    @Test
    fun `getFortsOptions parses aligned rows and skips invalid ones`() =
        runBlocking {
            FakeIssServer().use { server ->
                server.options =
                    """
                    {"securities":{"columns":["SECID","ASSETCODE","OPTIONTYPE","STRIKE","LASTTRADEDATE",
                        "UNDERLYINGASSET","UNDERLYINGSETTLEPRICE"],
                      "data":[["RIU6C90000","RI","C","90000","2026-09-03","RI","90500"],
                              ["RIU6P90000","RI","P","90000","2026-09-03","RI",null],
                              ["MARGIN","RI","X","1","2026-09-03","RI","1"],
                              ["BROKEN","RI","C","nope","2026-09-03","RI","1"]]},
                     "marketdata":{"columns":["SECID","LAST","BID","OPENPOSITION"],
                      "data":[["RIU6C90000","1200","1100","42"],["RIU6P90000","0","","7"],["MARGIN","1","1","1"],["BROKEN","1","1","1"]]}}
                    """.trimIndent()
                val client = client(server)

                val options = client.getFortsOptions()

                assertEquals(2, options.size)
                assertEquals("RIU6C90000", options[0].secid)
                assertEquals(OptionKind.CALL, options[0].kind)
                assertEquals(0, BigDecimal("90000").compareTo(options[0].strike))
                assertEquals(LocalDate.of(2026, 9, 3), options[0].lastTradeDate)
                assertEquals(0, BigDecimal("90500").compareTo(options[0].underlyingSettlePrice))
                assertEquals(0, BigDecimal("1200").compareTo(options[0].last))
                assertEquals(42L, options[0].openPosition)
                assertEquals(OptionKind.PUT, options[1].kind)
                assertNull(options[1].last)
                assertNull(options[1].bid)

                server.statusCode = 500
                assertTrue(client.getFortsOptions().isEmpty())
            }
        }

    @Test
    fun `getFuturesCandlesPaged stitches contracts resolved by prefix and sorts them`() =
        runBlocking {
            FakeIssServer().use { server ->
                server.futuresSecurities =
                    """
                    {"securities":{"columns":["SECID","LASTDELDATE"],
                      "data":[["RIU6","2026-06-30"],["RIZ6","2026-09-30"],["RI","2100-01-01"],
                              ["SBER","2026-06-30"],["RIU6B","2026-06-30"]]}}
                    """.trimIndent()
                val base = LocalDateTime.of(2026, 5, 1, 10, 0)
                server.futuresCandlesByTicker =
                    mapOf(
                        "RIU6" to candlesJson(listOf(candle(base.plusMinutes(2)))),
                        "RIZ6" to candlesJson(listOf(candle(base.plusMinutes(1)))),
                        "RI" to candlesJson(listOf(candle(base))),
                    )
                val client = client(server)

                val candles = client.getFuturesCandlesPaged("RI", base, base.plusDays(10))

                assertEquals(3, candles.size)
                assertEquals(base, candles[0].time)
                assertEquals(base.plusMinutes(1), candles[1].time)
                assertEquals(base.plusMinutes(2), candles[2].time)
                val contractPaths =
                    server.requests
                        .filter { it.path.contains("/boards/RFUD/securities/") && it.path.endsWith("/candles.json") }
                        .map { it.path.substringAfterLast("/securities/").substringBefore("/candles.json") }
                assertEquals(listOf("RIU6", "RIZ6", "RI").sorted(), contractPaths.sorted())
            }
        }

    @Test
    fun `getFuturesCandlesPaged returns empty when contract list is unavailable`() =
        runBlocking {
            FakeIssServer().use { server ->
                server.statusCode = 500
                val client = client(server)

                val candles = client.getFuturesCandlesPaged("RI", LocalDateTime.of(2026, 5, 1, 10, 0), LocalDateTime.of(2026, 5, 2, 10, 0))

                assertTrue(candles.isEmpty())
            }
        }

    @Test
    fun `getFuturesCandlesPaged returns empty when no contract matches prefix`() =
        runBlocking {
            FakeIssServer().use { server ->
                server.futuresSecurities =
                    """{"securities":{"columns":["SECID","LASTDELDATE"],"data":[["SBER","2026-06-30"]]}}"""
                val client = client(server)

                assertTrue(
                    client.getFuturesCandlesPaged("RI", LocalDateTime.of(2026, 5, 1, 10, 0), LocalDateTime.of(2026, 5, 2, 10, 0)).isEmpty(),
                )
            }
        }

    private class FakeIssServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests: MutableList<URI> = Collections.synchronizedList(mutableListOf())

        var statusCode: Int = 200
        var stockCandles: String = ""
        var futuresCandles: String = ""
        var futuresCandlesByTicker: Map<String, String> = emptyMap()
        var paginatedStock: ((Int) -> String)? = null
        var indexQuote: String = ""
        var indexDaily: String = ""
        var options: String = ""
        var futuresSecurities: String = """{"securities":{"columns":["SECID","LASTDELDATE"],"data":[]}}"""

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}/iss"

        init {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { handle(it) }
            server.start()
        }

        private fun handle(exchange: HttpExchange) {
            val uri = exchange.requestURI
            requests.add(uri)
            val body =
                when {
                    statusCode != 200 -> "error"
                    uri.path.endsWith("/securities.json") && uri.path.contains("/futures/markets/options/") -> options
                    uri.path.endsWith("/securities.json") && uri.path.contains("/forts/boards/RFUD/") -> futuresSecurities
                    uri.path.endsWith("/candles.json") && uri.path.contains("/markets/index/boards/") -> indexDaily
                    uri.path.endsWith("/candles.json") && uri.path.contains("/boards/") -> candlePage(uri)
                    uri.path.endsWith(".json") && uri.path.contains("/markets/index/securities/") -> indexQuote
                    else -> "{}"
                }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        private fun candlePage(uri: URI): String {
            val ticker = uri.path.substringAfterLast("/securities/").substringBefore("/candles.json")
            return when {
                uri.path.contains("/markets/shares/") -> {
                    val start =
                        uri.rawQuery
                            ?.substringAfter("start=", "0")
                            ?.substringBefore("&")
                            ?.toIntOrNull() ?: 0
                    paginatedStock?.invoke(start) ?: stockCandles
                }

                else -> {
                    futuresCandlesByTicker[ticker] ?: futuresCandles
                }
            }
        }

        override fun close() {
            server.stop(0)
        }
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
