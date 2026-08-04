package com.trading.bot.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.model.Candle
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Component
class MoexClient(
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}
    private val webClient = WebClient.create()
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val baseUrl = "https://iss.moex.com/iss"

    suspend fun getCandles(ticker: String, days: Int, from: LocalDateTime): List<Candle> {
        val stock = fetchCandles("stock", "shares", "TQBR", ticker, from)
        if (stock.isNotEmpty()) return stock
        // Фьючерсы (Si и др.) торгуются на FORTS — ищем там
        val futures = fetchCandles("futures", "forts", "RFUD", ticker, from)
        return if (futures.isNotEmpty()) futures else stock
    }

    private suspend fun fetchCandles(
        engine: String,
        market: String,
        board: String,
        ticker: String,
        from: LocalDateTime
    ): List<Candle> {
        return try {
            val url = "$baseUrl/engines/$engine/markets/$market/boards/$board/securities/$ticker/candles.json" +
                "?interval=10&from=${from.format(timeFormatter)}&until=${LocalDateTime.now().format(timeFormatter)}"

            val raw: String = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(Duration.ofSeconds(10))
                .awaitSingle()

            parseCandles(raw, ticker)
        } catch (e: Exception) {
            logger.warn(e) { "MOEX candles request failed for $ticker ($engine/$board)" }
            emptyList()
        }
    }

    private fun parseCandles(raw: String, ticker: String): List<Candle> {
        val candles = objectMapper.readTree(raw).path("candles")
        if (!candles.path("data").isArray) return emptyList()

        val columns = candles.path("columns").map { it.asText() }
        val indexOf = { name: String -> columns.indexOf(name) }
        val beginIdx = indexOf("begin")
        val openIdx = indexOf("open")
        val highIdx = indexOf("high")
        val lowIdx = indexOf("low")
        val closeIdx = indexOf("close")
        val volumeIdx = indexOf("volume")
        if (listOf(beginIdx, openIdx, highIdx, lowIdx, closeIdx, volumeIdx).any { it < 0 }) return emptyList()

        return candles.path("data")
            .map { row -> toCandle(row, ticker, beginIdx, openIdx, highIdx, lowIdx, closeIdx, volumeIdx) }
            .filterNotNull()
            .sortedBy { it.time }
            .takeLast(500)
    }

    private fun toCandle(
        row: JsonNode,
        ticker: String,
        beginIdx: Int,
        openIdx: Int,
        highIdx: Int,
        lowIdx: Int,
        closeIdx: Int,
        volumeIdx: Int
    ): Candle? {
        return try {
            Candle(
                ticker = ticker,
                timeframe = "MINUTE_10",
                time = LocalDateTime.parse(row.get(beginIdx).asText(), timeFormatter),
                openPrice = BigDecimal(row.get(openIdx).asText()),
                highPrice = BigDecimal(row.get(highIdx).asText()),
                lowPrice = BigDecimal(row.get(lowIdx).asText()),
                closePrice = BigDecimal(row.get(closeIdx).asText()),
                volume = row.get(volumeIdx).asLong()
            )
        } catch (e: Exception) {
            logger.debug { "Skipping malformed candle row for $ticker: ${e.message}" }
            null
        }
    }
}
