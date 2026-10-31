package com.trading.bot.client

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [MoexClient] — защитные ветви разбора ISS-ответов: отсутствующие колонки,
 * пустые/неполные таблицы, служебные строки опционов, отсутствие LASTDELDATE и
 * недоступность эндпоинта. Полные позитивные сценарии — в [MoexClientTest].
 */
class MoexClientEdgeCasesTest {
    private val mapper = jacksonObjectMapper()

    private fun client(server: FakeServer): MoexClient {
        val client = MoexClient(mapper)
        val field = MoexClient::class.java.getDeclaredField("baseUrl")
        field.isAccessible = true
        field.set(client, server.baseUrl)
        return client
    }

    @Test
    fun `volatility index without LAST column or with an empty table is null`() =
        runBlocking {
            FakeServer().use { server ->
                val client = client(server)

                server.volatilityBody = """{"marketdata":{"columns":["BID"],"data":[["1"]]}}"""
                assertNull(client.getVolatilityIndex())

                server.volatilityBody = """{"marketdata":{"columns":["LAST"],"data":[]}}"""
                assertNull(client.getVolatilityIndex())

                server.volatilityBody = """{"marketdata":{"columns":["LAST"],"data":[["  "]]}}"""
                assertNull(client.getVolatilityIndex())

                server.volatilityBody = """{"marketdata":{"columns":["LAST"],"data":[["42.5"]]}}"""
                assertEquals(0, java.math.BigDecimal("42.5").compareTo(client.getVolatilityIndex()!!))
            }
        }

    @Test
    fun `volatility index request failure is fail soft`() =
        runBlocking {
            FakeServer().use { server ->
                server.status = 503

                assertNull(client(server).getVolatilityIndex())
            }
        }

    @Test
    fun `forts options without required columns give an empty list`() =
        runBlocking {
            FakeServer().use { server ->
                server.optionsBody =
                    """
                    {"securities":{"columns":["ASSETCODE"],"data":[["RI"]]},
                     "marketdata":{"columns":["LAST"],"data":[["1"]]}}
                    """.trimIndent()

                assertTrue(client(server).getFortsOptions().isEmpty())
            }
        }

    @Test
    fun `forts options skip malformed rows and default missing open position`() =
        runBlocking {
            FakeServer().use { server ->
                server.optionsBody =
                    """
                    {"securities":{"columns":["SECID","ASSETCODE","OPTIONTYPE","STRIKE","LASTTRADEDATE","UNDERLYINGASSET"],
                      "data":[["RIU6C90000","RI","C","90000","2026-09-03","RI"],
                              ["BADDATE","RI","C","90000","not-a-date","RI"],
                              ["NOSTRIKE","RI","P","nope","2026-09-03","RI"]]},
                     "marketdata":{"columns":["LAST","BID"],
                      "data":[["1200","1100"],["1","1"],["1","1"]]}}
                    """.trimIndent()

                val options = client(server).getFortsOptions()

                assertEquals(1, options.size)
                assertEquals(0L, options.single().openPosition)
            }
        }

    @Test
    fun `forts options tolerate a shorter marketdata table and missing payload arrays`() =
        runBlocking {
            FakeServer().use { server ->
                server.optionsBody =
                    """
                    {"securities":{"columns":["SECID","ASSETCODE","OPTIONTYPE","STRIKE","LASTTRADEDATE","UNDERLYINGASSET"],
                      "data":[["RIU6C90000","RI","C","90000","2026-09-03","RI"]]}}
                    """.trimIndent()
                assertTrue(client(server).getFortsOptions().isEmpty())

                server.optionsBody =
                    """
                    {"securities":{"columns":["SECID","ASSETCODE","OPTIONTYPE","STRIKE","LASTTRADEDATE","UNDERLYINGASSET"],
                      "data":[["RIU6C90000","RI","C","90000","2026-09-03","RI"]]},
                     "marketdata":{"columns":["LAST"],"data":[]}}
                    """.trimIndent()
                assertTrue(client(server).getFortsOptions().isEmpty())
            }
        }

    @Test
    fun `daily closes ignore rows without a parseable close`() =
        runBlocking {
            FakeServer().use { server ->
                server.closesBody =
                    """
                    {"candles":{"columns":["begin","close"],
                      "data":[["2026-07-01","10.5"],["2026-07-02","oops"],["2026-07-03",null]]}}
                    """.trimIndent()

                assertEquals(listOf(10.5), client(server).getVolatilityIndexDailyCloses(from = LocalDate.of(2026, 7, 1)))

                server.closesBody = """{"candles":{"columns":["begin"],"data":[["2026-07-01"]]}}"""
                assertTrue(client(server).getVolatilityIndexDailyCloses(from = LocalDate.of(2026, 7, 1)).isEmpty())
            }
        }

    @Test
    fun `futures contract resolution requires SECID and skips short rows`() =
        runBlocking {
            FakeServer().use { server ->
                server.futuresSecuritiesBody = """{"securities":{"columns":["LASTDELDATE"],"data":[["2026-06-30"]]}}"""
                assertTrue(
                    client(server)
                        .getFuturesCandlesPaged("RI", LocalDateTime.of(2026, 5, 1, 10, 0), LocalDateTime.of(2026, 5, 2, 10, 0))
                        .isEmpty(),
                )
            }
        }

    private class FakeServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        var status: Int = 200
        var volatilityBody: String = "{}"
        var optionsBody: String = "{}"
        var closesBody: String = "{}"
        var futuresSecuritiesBody: String = """{"securities":{"columns":["SECID","LASTDELDATE"],"data":[]}}"""

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}/iss"

        init {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val body =
                    when {
                        status != 200 -> "error"
                        path.contains("/markets/options/securities.json") -> optionsBody
                        path.contains("/markets/index/boards/SNDX/") -> closesBody
                        path.contains("/markets/index/securities/") -> volatilityBody
                        path.contains("/forts/boards/RFUD/securities.json") -> futuresSecuritiesBody
                        else -> "{}"
                    }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
        }
    }
}
