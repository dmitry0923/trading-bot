package com.trading.bot.infrastructure.cbr

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trading.bot.config.MacroConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/**
 * Источник ряда ставок ЦБ РФ (P2 c1 плана 18_10_2026): парсинг SOAP-ответов
 * `DailyInfoWebServ` (методы KeyRate/Ruonia) и fail-soft при HTTP-ошибке.
 *
 * Формат ответов — реальный diffgram ЦБ: секция `xs:schema` со объявлением
 * элементов (`xs:element name="KR"`) не должна попадать в данные, строки — это
 * элементы `KR` (ключевая ставка: `DT`+`Rate`) и `ro` (RUONIA: `D0`+`ruo`).
 */
class CbrRatesProviderTest {
    @Test
    fun `parses key rate diffgram rows`() {
        val rows = provider().parseKeyRates(keyRateResponse)

        assertEquals(3, rows.size)
        assertEquals("2026-10-16", rows[0].rateDate.toString())
        assertEquals(0, BigDecimal("14.00").compareTo(rows[0].keyRate))
        assertEquals(0, BigDecimal("13.5").compareTo(rows[2].keyRate))
        assertTrue(rows.all { it.ruonia == null })
    }

    @Test
    fun `parses ruonia diffgram rows ignoring volume`() {
        val rows = provider().parseRuonia(ruoniaResponse)

        assertEquals(2, rows.size)
        assertEquals("2026-10-15", rows[0].rateDate.toString())
        assertEquals(0, BigDecimal("13.84").compareTo(rows[0].ruonia))
        assertEquals(0, BigDecimal("13.80").compareTo(rows[1].ruonia))
        assertTrue(rows.all { it.keyRate == null })
    }

    @Test
    fun `accepts comma decimal separator`() {
        val rows = provider().parseKeyRates(keyRateResponse.replace(">14.00<", ">14,00<"))

        assertEquals(3, rows.size)
        assertEquals(0, BigDecimal("14.00").compareTo(rows[0].keyRate))
    }

    @Test
    fun `schema declarations are not treated as data rows`() {
        // Только секция схемы, без строк diffgram: объявления xs:element name="KR"
        // не должны считаться данными (иначе парсер вернул бы мусорные строки).
        assertTrue(provider().parseKeyRates(schemaOnlyKeyRate).isEmpty())
    }

    @Test
    fun `malformed xml yields empty list instead of throwing`() {
        assertTrue(provider().parseKeyRates("<not-xml").isEmpty())
        assertTrue(provider().parseRuonia("<not-xml").isEmpty())
    }

    @Test
    fun `fetches key rates over http and sends soap envelope`() =
        runBlocking {
            val capturedBody = AtomicReference("")
            val capturedAction = AtomicReference("")
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                capturedBody.set(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
                capturedAction.set(exchange.requestHeaders.getFirst("SOAPAction") ?: "")
                respond(exchange, keyRateResponse, 200)
            }
            server.start()
            try {
                val provider = provider(url = "http://127.0.0.1:${server.address.port}/DailyInfo.asmx")

                val rows = provider.fetchKeyRates(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 16))

                assertEquals(3, rows.size)
                assertTrue(capturedAction.get().contains("http://web.cbr.ru/KeyRate"))
                assertTrue(capturedBody.get().contains("<KeyRate xmlns=\"http://web.cbr.ru/\">"))
                assertTrue(capturedBody.get().contains("<fromDate>2026-10-01T00:00:00</fromDate>"))
                assertTrue(capturedBody.get().contains("<ToDate>2026-10-16T00:00:00</ToDate>"))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `fetch returns empty on http error`() =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange -> respond(exchange, "boom", 500) }
            server.start()
            try {
                val provider = provider(url = "http://127.0.0.1:${server.address.port}/DailyInfo.asmx")

                assertTrue(provider.fetchKeyRates(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 16)).isEmpty())
                assertTrue(provider.fetchRuonia(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 16)).isEmpty())
            } finally {
                server.stop(0)
            }
        }

    private fun provider(url: String = "https://www.cbr.ru/DailyInfoWebServ/DailyInfo.asmx"): CbrRatesProvider =
        CbrRatesProvider(
            MacroConfig().apply { cbrSoapUrl = url },
            SimpleMeterRegistry(),
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

    private fun soap(inner: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
          <soap:Body>$inner</soap:Body>
        </soap:Envelope>"""

    private val schema: String =
        """<xs:schema id="KeyRate" xmlns="" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:msdata="urn:schemas-microsoft-com:xml-msdata">
          <xs:element name="KeyRate" msdata:IsDataSet="true">
            <xs:complexType><xs:choice minOccurs="0" maxOccurs="unbounded">
              <xs:element name="KR"><xs:complexType><xs:sequence>
                <xs:element name="DT" type="xs:dateTime" minOccurs="0" />
                <xs:element name="Rate" type="xs:decimal" minOccurs="0" />
              </xs:sequence></xs:complexType></xs:element>
            </xs:choice></xs:complexType>
          </xs:element>
        </xs:schema>"""

    private val keyRateResponse: String =
        soap(
            """<KeyRateResponse xmlns="http://web.cbr.ru/"><KeyRateResult>$schema<diffgr:diffgram xmlns:msdata="urn:schemas-microsoft-com:xml-msdata" xmlns:diffgr="urn:schemas-microsoft-com:xml-diffgram-v1"><KeyRate xmlns=""><KR diffgr:id="KR1" msdata:rowOrder="0"><DT>2026-10-16T00:00:00+03:00</DT><Rate>14.00</Rate></KR><KR diffgr:id="KR2" msdata:rowOrder="1"><DT>2026-10-15T00:00:00+03:00</DT><Rate>14.00</Rate></KR><KR diffgr:id="KR3" msdata:rowOrder="2"><DT>2026-10-14T00:00:00+03:00</DT><Rate>13.5</Rate></KR></KeyRate></diffgr:diffgram></KeyRateResult></KeyRateResponse>""",
        )

    private val ruoniaResponse: String =
        soap(
            """<RuoniaResponse xmlns="http://web.cbr.ru/"><RuoniaResult><xs:schema id="Ruonia" xmlns="" xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:element name="Ruonia"><xs:complexType><xs:choice minOccurs="0" maxOccurs="unbounded"><xs:element name="ro"><xs:complexType><xs:sequence><xs:element name="D0" type="xs:dateTime" minOccurs="0" /><xs:element name="ruo" type="xs:decimal" minOccurs="0" /><xs:element name="vol" type="xs:decimal" minOccurs="0" /></xs:sequence></xs:complexType></xs:element></xs:choice></xs:complexType></xs:element></xs:schema><diffgr:diffgram xmlns:diffgr="urn:schemas-microsoft-com:xml-diffgram-v1"><Ruonia xmlns=""><ro diffgr:id="ro1"><D0>2026-10-15T00:00:00+03:00</D0><ruo>13.84</ruo><vol>420000000</vol></ro><ro diffgr:id="ro2"><D0>2026-10-16T00:00:00+03:00</D0><ruo>13.80</ruo><vol>390000000</vol></ro></Ruonia></diffgr:diffgram></RuoniaResult></RuoniaResponse>""",
        )

    private val schemaOnlyKeyRate: String =
        soap(
            """<KeyRateResponse xmlns="http://web.cbr.ru/"><KeyRateResult>$schema<diffgr:diffgram xmlns:diffgr="urn:schemas-microsoft-com:xml-diffgram-v1"><KeyRate xmlns="" /></diffgr:diffgram></KeyRateResult></KeyRateResponse>""",
        )
}
