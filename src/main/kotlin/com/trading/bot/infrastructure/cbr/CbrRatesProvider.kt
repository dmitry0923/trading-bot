package com.trading.bot.infrastructure.cbr

import com.trading.bot.config.MacroConfig
import com.trading.bot.model.entity.CbrRateRecord
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Источник дневного ряда ставок Банка России (P2 c1 плана 18_10_2026, research).
 *
 * Официальный SOAP-сервис ЦБ `DailyInfoWebServ` ([MacroConfig.cbrSoapUrl]):
 * метод `KeyRate` — ежедневная ключевая ставка, метод `Ruonia` — ставка RUONIA.
 * Оба возвращают diffgram-таблицу внутри SOAP-конверта; разбирается встроенным
 * DOM-парсером (без внешних XML-зависимостей).
 *
 * Fail-soft: при HTTP-ошибке/таймауте/невалидном ответе возвращается пустой
 * список — вызывающий [CbrRatesHistoryLoader] продолжает работать и не падает.
 * В торговом контуре не участвует.
 */
@Component
class CbrRatesProvider(
    private val macroConfig: MacroConfig,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val webClient =
        WebClient
            .builder()
            .codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES) }
            .build()

    /** Ключевая ставка ЦБ РФ за окно [from]..[to] (включительно). */
    suspend fun fetchKeyRates(
        from: LocalDate,
        to: LocalDate,
    ): List<CbrRateRecord> {
        val raw = post(KEY_RATE_OPERATION, from, to) ?: return emptyList()
        return parseKeyRates(raw)
    }

    /** Ставка RUONIA за окно [from]..[to] (включительно). */
    suspend fun fetchRuonia(
        from: LocalDate,
        to: LocalDate,
    ): List<CbrRateRecord> {
        val raw = post(RUONIA_OPERATION, from, to) ?: return emptyList()
        return parseRuonia(raw)
    }

    /** Разбор ответа метода `KeyRate` (row-тег `KR`: `DT` + `Rate`). */
    internal fun parseKeyRates(raw: String): List<CbrRateRecord> =
        try {
            val rows = parseRows(raw, rowTag = "KR", dateTag = "DT", valueTag = "Rate")
            rows.map { CbrRateRecord(rateDate = it.first, keyRate = it.second) }
        } catch (e: Exception) {
            logger.warn(e) { "CBR KeyRate response parse failed" }
            emptyList()
        }

    /** Разбор ответа метода `Ruonia` (row-тег `ro`: `D0` + `ruo`). */
    internal fun parseRuonia(raw: String): List<CbrRateRecord> =
        try {
            val rows = parseRows(raw, rowTag = "ro", dateTag = "D0", valueTag = "ruo")
            rows.map { CbrRateRecord(rateDate = it.first, ruonia = it.second) }
        } catch (e: Exception) {
            logger.warn(e) { "CBR Ruonia response parse failed" }
            emptyList()
        }

    /** SOAP-конверт запроса (SOAP 1.1): `<операция>` с `fromDate`/`ToDate`. */
    internal fun envelope(
        operation: String,
        from: LocalDate,
        to: LocalDate,
    ): String {
        val fromTs = from.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val toTs = to.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <$operation xmlns="http://web.cbr.ru/">
                  <fromDate>$fromTs</fromDate>
                  <ToDate>$toTs</ToDate>
                </$operation>
              </soap:Body>
            </soap:Envelope>
            """.trimIndent()
    }

    private suspend fun post(
        operation: String,
        from: LocalDate,
        to: LocalDate,
    ): String? =
        try {
            val raw: String =
                webClient
                    .post()
                    .uri(macroConfig.cbrSoapUrl)
                    .contentType(SOAP_CONTENT_TYPE)
                    .header("SOAPAction", "\"$SOAP_ACTION_PREFIX$operation\"")
                    .bodyValue(envelope(operation, from, to))
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofMillis(macroConfig.cbrRequestTimeoutMs))
                    .awaitSingle()
            meterRegistry.counter("macro.cbr.fetch", Tags.of("operation", operation, "status", "OK")).increment()
            raw
        } catch (e: Exception) {
            logger.warn(e) { "CBR SOAP $operation fetch failed" }
            meterRegistry.counter("macro.cbr.fetch", Tags.of("operation", operation, "status", "ERROR")).increment()
            null
        }

    private fun parseRows(
        raw: String,
        rowTag: String,
        dateTag: String,
        valueTag: String,
    ): List<Pair<LocalDate, BigDecimal>> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply {
                    isNamespaceAware = false
                    isExpandEntityReferences = false
                    runCatching { setFeature(DISALLOW_DOCTYPE, true) }
                    runCatching { setFeature(EXTERNAL_GENERAL_ENTITIES, false) }
                    runCatching { setFeature(EXTERNAL_PARAMETER_ENTITIES, false) }
                }.newDocumentBuilder()
                .parse(ByteArrayInputStream(raw.toByteArray(StandardCharsets.UTF_8)))
        val rows = document.getElementsByTagName(rowTag)
        val result = ArrayList<Pair<LocalDate, BigDecimal>>()
        for (i in 0 until rows.length) {
            val element = rows.item(i) as? Element ?: continue
            val date = childText(element, dateTag)?.let { parseDate(it) } ?: continue
            val value = childText(element, valueTag)?.let { parseDecimal(it) } ?: continue
            result.add(date to value)
        }
        return result
    }

    private fun childText(
        parent: Element,
        tag: String,
    ): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.nodeName == tag) return node.textContent?.trim()
        }
        return null
    }

    private fun parseDate(raw: String): LocalDate? {
        val cleaned = raw.trim()
        val withOffset = runCatching { OffsetDateTime.parse(cleaned).toLocalDate() }.getOrNull()
        return withOffset ?: runCatching { LocalDate.parse(cleaned.take(10)) }.getOrNull()
    }

    private fun parseDecimal(raw: String): BigDecimal? = raw.trim().replace(',', '.').toBigDecimalOrNull()

    private companion object {
        const val KEY_RATE_OPERATION = "KeyRate"
        const val RUONIA_OPERATION = "Ruonia"
        const val SOAP_ACTION_PREFIX = "http://web.cbr.ru/"
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
        const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
        const val EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"
        val SOAP_CONTENT_TYPE: MediaType = MediaType.parseMediaType("text/xml; charset=utf-8")
    }
}
