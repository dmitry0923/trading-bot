package com.trading.bot.infrastructure.cbr

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trading.bot.config.MacroConfig
import com.trading.bot.model.entity.CbrRateRecord
import com.trading.bot.repository.CbrRatesHistoryRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Донакачка ряда ставок ЦБ РФ (P2 c1): объединение KeyRate/RUONIA по дате
 * и загрузка за окно от часов сервиса (детерминированный `Clock`).
 */
class CbrRatesHistoryLoaderTest {
    // 2026-10-18 15:00 МСК
    private val clock = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneId.of("Europe/Moscow"))

    @Test
    fun `merge combines key rate and ruonia by date`() {
        val merged =
            loader().merge(
                keyRates =
                    listOf(
                        record("2026-10-15", keyRate = "14.0"),
                        record("2026-10-16", keyRate = "14.0"),
                    ),
                ruonia =
                    listOf(
                        record("2026-10-15", ruonia = "13.9"),
                        record("2026-10-16", ruonia = "13.8"),
                    ),
            )

        assertEquals(2, merged.size)
        assertEquals("2026-10-15", merged[0].rateDate.toString())
        assertEquals(0, BigDecimal("14.0").compareTo(merged[0].keyRate))
        assertEquals(0, BigDecimal("13.9").compareTo(merged[0].ruonia))
        assertEquals(0, BigDecimal("13.8").compareTo(merged[1].ruonia))
    }

    @Test
    fun `merge keeps partial rows from both series`() {
        val merged =
            loader().merge(
                keyRates = listOf(record("2026-10-15", keyRate = "14.0")),
                ruonia = listOf(record("2026-10-16", ruonia = "13.8")),
            )

        assertEquals(listOf("2026-10-15", "2026-10-16"), merged.map { it.rateDate.toString() })
        assertNull(merged[0].ruonia)
        assertNull(merged[1].keyRate)
    }

    @Test
    fun `loadAndSave fetches both series for clock window and upserts merged rows`() =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val action = exchange.requestHeaders.getFirst("SOAPAction") ?: ""
                val body = if (action.contains("Ruonia")) ruoniaResponse else keyRateResponse
                respond(exchange, body, 200)
            }
            server.start()
            try {
                val repo = Mockito.mock(CbrRatesHistoryRepository::class.java)
                Mockito.`when`(repo.saveAll(Mockito.anyList())).thenReturn(2)
                val loader =
                    loader(
                        url = "http://127.0.0.1:${server.address.port}/DailyInfo.asmx",
                        repository = repo,
                    )

                val result = loader.loadAndSave(days = 30)

                assertEquals("2026-09-18", result.from.toString())
                assertEquals("2026-10-18", result.to.toString())
                assertEquals(2, result.keyRates)
                assertEquals(2, result.ruonia)
                assertEquals(2, result.loaded)
                assertEquals(2, result.upserted)
                Mockito.verify(repo).saveAll(
                    Mockito.argThat<List<CbrRateRecord>> { records ->
                        records.size == 2 &&
                            records[0].rateDate.toString() == "2026-10-15" &&
                            records[0].keyRate != null &&
                            records[0].ruonia != null
                    },
                )
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `loadAndSave returns zero when provider is unreachable`() =
        runBlocking {
            val repo = Mockito.mock(CbrRatesHistoryRepository::class.java)
            Mockito.`when`(repo.saveAll(Mockito.anyList())).thenReturn(0)
            val loader = loader(url = "http://127.0.0.1:1/DailyInfo.asmx", repository = repo)

            val result = loader.loadAndSave(days = 30)

            assertEquals(0, result.loaded)
            assertEquals(0, result.upserted)
        }

    private fun loader(
        url: String = "https://www.cbr.ru/DailyInfoWebServ/DailyInfo.asmx",
        repository: CbrRatesHistoryRepository = Mockito.mock(CbrRatesHistoryRepository::class.java),
    ): CbrRatesHistoryLoader =
        CbrRatesHistoryLoader(
            CbrRatesProvider(MacroConfig().apply { cbrSoapUrl = url }, SimpleMeterRegistry()),
            repository,
            SimpleMeterRegistry(),
            clock,
        )

    private fun record(
        date: String,
        keyRate: String? = null,
        ruonia: String? = null,
    ): CbrRateRecord =
        CbrRateRecord(
            rateDate = LocalDate.parse(date),
            keyRate = keyRate?.toBigDecimal(),
            ruonia = ruonia?.toBigDecimal(),
        )

    private fun respond(
        exchange: HttpExchange,
        body: String,
        statusCode: Int,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/xml; charset=utf-8")
        if (statusCode in 200..299) {
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        } else {
            exchange.sendResponseHeaders(statusCode, -1)
        }
        exchange.close()
    }

    private val keyRateResponse: String =
        """<?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>
        <KeyRateResponse xmlns="http://web.cbr.ru/"><KeyRateResult>
        <diffgr:diffgram xmlns:diffgr="urn:schemas-microsoft-com:xml-diffgram-v1"><KeyRate xmlns="">
        <KR diffgr:id="KR1"><DT>2026-10-15T00:00:00+03:00</DT><Rate>14.00</Rate></KR>
        <KR diffgr:id="KR2"><DT>2026-10-16T00:00:00+03:00</DT><Rate>14.00</Rate></KR>
        </KeyRate></diffgr:diffgram></KeyRateResult></KeyRateResponse>
        </soap:Body></soap:Envelope>"""

    private val ruoniaResponse: String =
        """<?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>
        <RuoniaResponse xmlns="http://web.cbr.ru/"><RuoniaResult>
        <diffgr:diffgram xmlns:diffgr="urn:schemas-microsoft-com:xml-diffgram-v1"><Ruonia xmlns="">
        <ro diffgr:id="ro1"><D0>2026-10-15T00:00:00+03:00</D0><ruo>13.9</ruo><vol>420000000</vol></ro>
        <ro diffgr:id="ro2"><D0>2026-10-16T00:00:00+03:00</D0><ruo>13.8</ruo><vol>390000000</vol></ro>
        </Ruonia></diffgr:diffgram></RuoniaResult></RuoniaResponse>
        </soap:Body></soap:Envelope>"""
}
