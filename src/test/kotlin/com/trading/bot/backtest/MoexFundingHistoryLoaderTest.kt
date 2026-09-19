package com.trading.bot.backtest

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trading.bot.config.FundingConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.model.entity.FundingHistoryRecord
import com.trading.bot.repository.FundingHistoryRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * Донакачка исторического ряда funding (MOEX ISS history endpoint, SWAPRATE):
 * парсинг истории (TRADEDATE + SWAPRATE × лот), пагинация, сохранение. Парсинг
 * и конвертация — по паттерну MoexFundingProviderTest (реальные данные MOEX:
 * 0.00278 × 1000 = 2.78 ₽/контракт/клиринг). Пустой config-history URL → off.
 */
class MoexFundingHistoryLoaderTest {
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
    fun `parses history rows with swaprate conversion`() {
        val loader = loader(fundingConfig = FundingConfig())
        // Реальный ISS history ответ: блок history с до 100 строками (TRADEDATE/SWAPRATE).
        val body =
            """{"history": {"columns": ["BOARDID", "TRADEDATE", "SECID", "SETTLEPRICE", "SWAPRATE", "SHORTNAME"],
                "data": [
                  ["RFUD", "2025-09-09", "CNYRUBF", "1093.44", "0.00278", "Юань"],
                  ["RFUD", "2025-09-10", "CNYRUBF", "1094.00", "0.00256", "Юань"],
                  ["RFUD", "2025-09-11", "CNYRUBF", "1095.00", "n/a", "Юань"]
                ]}}"""

        val parsed = loader.parseHistory(body, "CNYRUBF")

        assertEquals(2, parsed.size)
        assertEquals("2025-09-09", parsed[0].clearingDate.toString())
        // 0.00278 (SWAPRATE, RUB за 1 CNY) × 1000 (лот CNYRUBF) = 2.78 ₽/контракт/клиринг
        assertEquals(0, BigDecimal("2.78").compareTo(parsed[0].valueRubPerContract))
        assertEquals("MOEX", parsed[0].source)
    }

    @Test
    fun `blank history url disables backfill`() =
        runBlocking {
            val loader = loader()

            val result = loader.loadAndSave("CNYRUBF", 365)

            assertEquals(0, result.loaded)
            assertEquals(0, result.saved)
        }

    @Test
    fun `loads and saves history via HTTP with repository`() =
        runBlocking {
            val body =
                """{"history": {"columns": ["TRADEDATE", "SWAPRATE"],
                    "data": [["2026-08-03", "0.00287"], ["2026-08-04", "0.00256"]]}}"""
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange -> respond(exchange, body, 200) }
            server.start()
            try {
                val fundingConfig = FundingConfig()
                fundingConfig.moexHistoryUrl = "http://127.0.0.1:${server.address.port}/iss/{ticker}?from={from}&till={till}"
                val repo = Mockito.mock(FundingHistoryRepository::class.java)
                Mockito.`when`(repo.saveAll(Mockito.anyList())).thenReturn(2)
                val loader = loader(fundingConfig = fundingConfig, repo = repo)

                val result = loader.loadAndSave("CNYRUBF", 365)

                assertEquals(2, result.loaded)
                assertEquals(2, result.saved)
                Mockito.verify(repo).saveAll(
                    Mockito.argThat<List<FundingHistoryRecord>> { records ->
                        records.size == 2 &&
                            records[0].clearingDate.toString() == "2026-08-03" &&
                            records.any { it.valueRubPerContract.compareTo(BigDecimal("2.87")) == 0 }
                    },
                )
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `perms pages until short page returned`() =
        runBlocking {
            val requests = AtomicInteger(0)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val startParam =
                    Regex("start=(\\d+)")
                        .find(exchange.requestURI.query ?: "")
                        ?.groupValues
                        ?.get(1)
                        ?.toInt() ?: 0
                val page =
                    if (startParam == 0) {
                        """{"history": {"columns": ["TRADEDATE", "SWAPRATE"], "data": ${rows(0, 100)}}}"""
                    } else {
                        """{"history": {"columns": ["TRADEDATE", "SWAPRATE"], "data": [["2026-05-01", "0.00250"]]}}"""
                    }
                requests.incrementAndGet()
                respond(exchange, page, 200)
            }
            server.start()
            try {
                val fundingConfig = FundingConfig()
                fundingConfig.moexHistoryUrl = "http://127.0.0.1:${server.address.port}/iss/{ticker}?from={from}&till={till}"
                val repo = Mockito.mock(FundingHistoryRepository::class.java)
                Mockito.`when`(repo.saveAll(Mockito.anyList())).thenReturn(101)
                val loader = loader(fundingConfig = fundingConfig, repo = repo)

                val result = loader.loadAndSave("CNYRUBF", 365)

                assertEquals(101, result.loaded)
                assertEquals(2, requests.get(), "должны уйти 2 страницы (полная + короткая)")
                Mockito.verify(repo).saveAll(Mockito.argThat<List<FundingHistoryRecord>> { it.size == 101 })
            } finally {
                server.stop(0)
            }
        }

    private fun rows(
        from: Int,
        count: Int,
    ): String =
        (from until from + count)
            .joinToString(",") { i -> "[\"${LocalDate.of(2026, 1, 1).plusDays(i.toLong())}\", \"0.00%03d\"]".format(200 + i) }

    private fun loader(
        fundingConfig: FundingConfig = FundingConfig(),
        repo: FundingHistoryRepository = Mockito.mock(FundingHistoryRepository::class.java),
    ): MoexFundingHistoryLoader =
        MoexFundingHistoryLoader(
            fundingConfig,
            instrumentsConfig,
            repo,
            ObjectMapper(),
            SimpleMeterRegistry(),
        )

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
