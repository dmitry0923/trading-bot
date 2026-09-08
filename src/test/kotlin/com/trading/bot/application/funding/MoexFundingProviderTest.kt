package com.trading.bot.application.funding

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trading.bot.config.FundingConfig
import com.trading.bot.config.InstrumentsConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * P0-аудит (funding): MoexFundingProvider извлекает значение funding из ISS-ответа
 * (таблица columns/data) и конвертирует raw-значение во вход P&L
 * (RUB/контракт/клиринг, CNYRUBF: × 1000 CNY). Отсутствие столбца/невалидные данные
 * /недоступность API → null (fallen switch на CONFIG fallback в FundingSnapshotService).
 */
class MoexFundingProviderTest {
    private val instrumentsConfig =
        InstrumentsConfig().apply {
            instruments =
                mutableListOf(
                    InstrumentsConfig.InstrumentSpec(
                        ticker = "CNYRUBF",
                        type = "FUTURES",
                        lotSize = 1000,
                        priceStep = BigDecimal("0.001"),
                        priceStepCost = BigDecimal("1.0"),
                        go = BigDecimal("850"),
                        leverage = BigDecimal("1.0"),
                        baseAsset = "CNY",
                    ),
                )
        }

    @Test
    fun `parses funding from ISS columns data block`() {
        val provider = provider()
        val body =
            """{"issdata": {"columns": ["LATESTFUNDING", "TS"], "data": [["0.00279", "2026-09-01 18:45:00"]]}}"""

        val parsed = provider.parseFundingValue(body, "LATESTFUNDING")

        assertEquals(0, BigDecimal("0.00279").compareTo(parsed!!))
    }

    @Test
    fun `missing column yields null`() {
        val provider = provider()
        val body = """{"issdata": {"columns": ["TS"], "data": [["2026-09-01 18:45:00"]]}}"""

        assertNull(provider.parseFundingValue(body, "LATESTFUNDING"))
    }

    @Test
    fun `non numeric funding yields null`() {
        val provider = provider()
        val body = """{"issdata": {"columns": ["LATESTFUNDING"], "data": [["n/a"]]}}"""

        assertNull(provider.parseFundingValue(body, "LATESTFUNDING"))
    }

    @Test
    fun `no block with columns and data yields null`() {
        val provider = provider()
        val body = """{"ok": true}"""

        assertNull(provider.parseFundingValue(body, "LATESTFUNDING"))
    }

    @Test
    fun `live snapshot converts raw funding by lot multiplier`() =
        runBlocking {
            val server = liveServer("""{"issdata": {"columns": ["LATESTFUNDING"], "data": [["0.00279"]]}}""")
            try {
                val fundingConfig = FundingConfig()
                fundingConfig.moexUrl = "http://127.0.0.1:${server.address.port}/iss/{ticker}/funding"
                val provider = MoexFundingProvider(fundingConfig, instrumentsConfig, ObjectMapper())

                val snapshot = provider.currentSnapshot("CNYRUBF")

                assertNotNull(snapshot)
                assertEquals(FundingSource.MOEX, snapshot!!.source)
                // 0.00279 (raw, единица источника) × 1000 (лычи CNYRUBF) = 2.79 ₽/контракт/клиринг
                assertEquals(0, BigDecimal("2.79").compareTo(snapshot.valueRubPerContractPerClearing))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `live API down yields null`() =
        runBlocking {
            val server = liveServer("""{"issdata": {"columns": ["LATESTFUNDING"], "data": [["0.00279"]]}}""")
            try {
                val fundingConfig = FundingConfig()
                fundingConfig.moexUrl = "http://127.0.0.1:${server.address.port}/iss/{ticker}/funding"
                val provider = MoexFundingProvider(fundingConfig, instrumentsConfig, ObjectMapper())
                server.stop(0)

                assertNull(provider.currentSnapshot("CNYRUBF"))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `blank moex url disables live source`() =
        runBlocking {
            val provider = MoexFundingProvider(FundingConfig(), instrumentsConfig, ObjectMapper())

            assertNull(provider.currentSnapshot("CNYRUBF"))
        }

    private fun provider(): MoexFundingProvider = MoexFundingProvider(FundingConfig(), instrumentsConfig, ObjectMapper())

    private fun liveServer(body: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> respond(exchange, body, 200) }
        server.start()
        return server
    }

    private fun respond(
        exchange: HttpExchange,
        body: String,
        statusCode: Int,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        if (statusCode in 200..299) {
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        } else {
            exchange.sendResponseHeaders(statusCode, -1)
        }
        exchange.close()
    }
}
