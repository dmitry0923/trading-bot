package com.trading.bot.client
import com.fasterxml.jackson.databind.JsonNode
import com.trading.bot.config.MoexConfig
import com.trading.bot.model.Candle
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Component
class MoexClient(private val webClient: WebClient, private val moexConfig: MoexConfig) {
    private val logger = KotlinLogging.logger {}
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    suspend fun getCandles(ticker: String, interval: Int = 10, from: LocalDateTime? = null, till: LocalDateTime? = null): List<Candle> {
        val url = buildString {
            append("${moexConfig.baseUrl}/engines/stock/markets/shares/securities/${ticker}/candles.json")
            append("?interval=$interval"); from?.let { append("&from=${it.format(fmt)}") }; till?.let { append("&till=${it.format(fmt)}") }
        }
        return try {
            val r = webClient.get().uri(url).retrieve().bodyToMono(JsonNode::class.java).awaitSingle()
            val tf = when(interval){1->"M1";10->"M10";60->"H1";24->"D1";else->"M10"}
            r["candles"]?.get("data")?.map { row -> Candle(ticker, tf, LocalDateTime.parse(row[6].asText(), fmt), BigDecimal(row[0].asText()), BigDecimal(row[1].asText()), BigDecimal(row[2].asText()), BigDecimal(row[3].asText()), row[5].asLong()) } ?: emptyList()
        } catch (e: Exception) { logger.error(e) { "MOEX error $ticker" }; emptyList() }
    }
}
