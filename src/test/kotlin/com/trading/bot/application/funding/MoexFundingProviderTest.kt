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
 * (RUB/контракт/клиринг, CNYRUBF: SWAPRATE за 1 CNY × лот 1000). Верификация по
 * реальным данным MOEX (2026-09-08..10): поле `LATESTFUNDING` в MOEX ISS НЕ
 * существует — ставка публикуется как столбец `SWAPRATE` («Фандинг, руб.»,
 * значения 0.00278 / 0.00256). Отсутствие столбца/невалидные данные/недоступность
 * API → null (fallen switch на CONFIG fallback в FundingSnapshotService).
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
                    // lotSize != 1000: раньше funding завышался глобальным множителем 1000
                    // (GLDRUBF ×1000, IMOEXF ×100) — регрессия на конвертацию по lotSize.
                    InstrumentsConfig.InstrumentSpec(
                        ticker = "GLDRUBF",
                        type = "FUTURES",
                        lotSize = 1,
                        priceStep = BigDecimal("0.1"),
                        priceStepCost = BigDecimal("0.1"),
                        go = BigDecimal("1292"),
                        leverage = BigDecimal("1.0"),
                        baseAsset = "XAU",
                    ),
                    InstrumentsConfig.InstrumentSpec(
                        ticker = "IMOEXF",
                        type = "FUTURES",
                        lotSize = 10,
                        priceStep = BigDecimal("0.5"),
                        priceStepCost = BigDecimal("5.0"),
                        go = BigDecimal("2264"),
                        leverage = BigDecimal("1.0"),
                        baseAsset = "IMOEX",
                    ),
                )
        }

    @Test
    fun `parses funding from ISS marketdata block`() {
        val provider = provider()
        // Реальный ISS-ответ: несколько блоков, SWAPRATE в marketdata (securities — первый).
        val body =
            """{"securities": {"columns": ["SECID", "LOTVOLUME"], "data": [["CNYRUBF", 1000]]},
                "marketdata": {"columns": ["SECID", "SWAPRATE", "TRADEDATE"], "data": [["CNYRUBF", "0.00279", "2026-09-08"]]}}"""

        val parsed = provider.parseFundingValue(body, "SWAPRATE")

        assertEquals(0, BigDecimal("0.00279").compareTo(parsed!!))
    }

    @Test
    fun `missing column yields null`() {
        val provider = provider()
        val body = """{"marketdata": {"columns": ["TS"], "data": [["2026-09-01 18:45:00"]]}}"""

        assertNull(provider.parseFundingValue(body, "SWAPRATE"))
    }

    @Test
    fun `non numeric funding yields null`() {
        val provider = provider()
        val body = """{"marketdata": {"columns": ["SWAPRATE"], "data": [["n/a"]]}}"""

        assertNull(provider.parseFundingValue(body, "SWAPRATE"))
    }

    @Test
    fun `no block with columns and data yields null`() {
        val provider = provider()
        val body = """{"ok": true}"""

        assertNull(provider.parseFundingValue(body, "SWAPRATE"))
    }

    @Test
    fun `live snapshot converts raw swaprate by lot multiplier`() =
        runBlocking {
            val server = liveServer("""{"marketdata": {"columns": ["SECID", "SWAPRATE"], "data": [["CNYRUBF", "0.00279"]]}}""")
            try {
                val fundingConfig = FundingConfig()
                fundingConfig.moexUrl = "http://127.0.0.1:${server.address.port}/iss/{ticker}/funding"
                val provider = MoexFundingProvider(fundingConfig, instrumentsConfig, ObjectMapper())

                val snapshot = provider.currentSnapshot("CNYRUBF")

                assertNotNull(snapshot)
                assertEquals(FundingSource.MOEX, snapshot!!.source)
                assertEquals(FundingUnit.RUB_PER_BASE_ASSET_UNIT, snapshot.unit)
                // 0.00279 (SWAPRATE, RUB за 1 CNY) × 1000 (лот CNYRUBF) = 2.79 ₽/контракт/клиринг
                assertEquals(0, BigDecimal("2.79").compareTo(snapshot.valueRubPerContractPerClearing))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `live snapshot converts raw swaprate by instrument lot size of 1`() =
        runBlocking {
            // Реальный GLDRUBF: SWAPRATE ~5.1 ₽ за 1 г золота, контракт = 1 г → ~5.1 ₽/клиринг.
            val server = liveServer("""{"marketdata": {"columns": ["SECID", "SWAPRATE"], "data": [["GLDRUBF", "5.10597"]]}}""")
            try {
                val fundingConfig = FundingConfig()
                fundingConfig.moexUrl = "http://127.0.0.1:${server.address.port}/iss/{ticker}/funding"
                val provider = MoexFundingProvider(fundingConfig, instrumentsConfig, ObjectMapper())

                val snapshot = provider.currentSnapshot("GLDRUBF")

                assertNotNull(snapshot)
                // 5.10597 × lotSize 1 = 5.10597 ₽/контракт/клиринг (НЕ ×1000 = 5105.97)
                assertEquals(0, BigDecimal("5.10597").compareTo(snapshot!!.valueRubPerContractPerClearing))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `live snapshot converts raw swaprate by instrument lot size of 10`() =
        runBlocking {
            // Реальный IMOEXF: SWAPRATE ~1.01287 ₽ за 1 пункт индекса, контракт = 10 пунктов.
            val server = liveServer("""{"marketdata": {"columns": ["SECID", "SWAPRATE"], "data": [["IMOEXF", "1.01287"]]}}""")
            try {
                val fundingConfig = FundingConfig()
                fundingConfig.moexUrl = "http://127.0.0.1:${server.address.port}/iss/{ticker}/funding"
                val provider = MoexFundingProvider(fundingConfig, instrumentsConfig, ObjectMapper())

                val snapshot = provider.currentSnapshot("IMOEXF")

                assertNotNull(snapshot)
                // 1.01287 × lotSize 10 = 10.1287 ₽/контракт/клиринг (НЕ ×1000 = 1012.87)
                assertEquals(0, BigDecimal("10.1287").compareTo(snapshot!!.valueRubPerContractPerClearing))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `unknown instrument yields null instead of global lot multiplier`() =
        runBlocking {
            // Тикер без спецификации → lotSize неизвестен. Подставлять глобальный
            // множитель 1000 нельзя (именно это завышало GLDRUBF/IMOEXF) → fail-closed.
            val server = liveServer("""{"marketdata": {"columns": ["SECID", "SWAPRATE"], "data": [["NOSUCHF", "1.5"]]}}""")
            try {
                val fundingConfig = FundingConfig()
                fundingConfig.moexUrl = "http://127.0.0.1:${server.address.port}/iss/{ticker}/funding"
                val provider = MoexFundingProvider(fundingConfig, instrumentsConfig, ObjectMapper())

                assertNull(provider.currentSnapshot("NOSUCHF"))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `live API down yields null`() =
        runBlocking {
            val server = liveServer("""{"marketdata": {"columns": ["SECID", "SWAPRATE"], "data": [["CNYRUBF", "0.00279"]]}}""")
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
