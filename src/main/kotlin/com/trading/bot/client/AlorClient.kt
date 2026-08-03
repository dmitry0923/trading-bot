package com.trading.bot.client
import com.fasterxml.jackson.databind.JsonNode
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.model.*
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
class AlorClient(private val webClient: WebClient, private val alorConfig: AlorConfig, private val tradingConfig: TradingConfig) {
    private val logger = KotlinLogging.logger {}
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    suspend fun getLastPrice(ticker: String): BigDecimal? {
        return if (tradingConfig.mode == "SIMULATION") {
            val base = kotlin.math.abs(ticker.hashCode() % 1000) + 50.0
            BigDecimal(base + (Math.random() - 0.5) * 5).setScale(2, RoundingMode.HALF_UP)
        } else {
            try {
                val r = webClient.get().uri("${alorConfig.apiUrl}/md/v2/Securities/${alorConfig.exchange}/${ticker}/quotes")
                    .header("Authorization", "Bearer ${alorConfig.token}").retrieve().bodyToMono(JsonNode::class.java).awaitSingle()
                r["last_price"]?.asText()?.toBigDecimalOrNull()
            } catch (e: Exception) { logger.error(e) { "Alor price error $ticker" }; null }
        }
    }

    suspend fun getCandles(ticker: String, tf: String = "600", from: Instant, to: Instant): List<Candle> {
        val f = LocalDateTime.ofInstant(from, ZoneId.systemDefault()).format(fmt)
        val t = LocalDateTime.ofInstant(to, ZoneId.systemDefault()).format(fmt)
        return try {
            val r = webClient.get().uri { b -> b.path("${alorConfig.apiUrl}/md/v2/history").queryParam("symbol", ticker).queryParam("exchange", alorConfig.exchange).queryParam("tf", tf).queryParam("from", f).queryParam("to", t).build() }
                .header("Authorization", "Bearer ${alorConfig.token}").retrieve().bodyToMono(JsonNode::class.java).awaitSingle()
            r.map { node -> Candle(ticker, tf, LocalDateTime.parse(node["time"]?.asText() ?: "", fmt), node["open"]?.asText()?.toBigDecimalOrNull() ?: BigDecimal.ZERO, node["high"]?.asText()?.toBigDecimalOrNull() ?: BigDecimal.ZERO, node["low"]?.asText()?.toBigDecimalOrNull() ?: BigDecimal.ZERO, node["close"]?.asText()?.toBigDecimalOrNull() ?: BigDecimal.ZERO, node["volume"]?.asLong() ?: 0) }
        } catch (e: Exception) { logger.error(e) { "Alor candles error $ticker" }; emptyList() }
    }

    suspend fun getMarketSnapshot(ticker: String): MarketSnapshot? {
        val price = getLastPrice(ticker) ?: return null
        return MarketSnapshot(ticker, price, price.multiply(BigDecimal("0.9995")), price.multiply(BigDecimal("1.0005")), price.multiply(BigDecimal("0.001")), 0)
    }

    suspend fun placeMarketOrder(ticker: String, side: String, qty: Int): String? {
        if (tradingConfig.mode == "SIMULATION") { logger.info { "[SIM] $side $qty $ticker" }; return "sim-${System.currentTimeMillis()}" }
        val body = mapOf("side" to side.lowercase(), "quantity" to qty, "instrument" to mapOf("symbol" to ticker, "exchange" to alorConfig.exchange), "user" to mapOf("portfolio" to alorConfig.portfolio), "type" to "market")
        return try {
            val r = webClient.post().uri("${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/orders/actions/market").header("Authorization", "Bearer ${alorConfig.token}").contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve().bodyToMono(JsonNode::class.java).awaitSingle()
            r["orderId"]?.asText()
        } catch (e: Exception) { logger.error(e) { "Alor order error" }; null }
    }

    suspend fun placeLimitOrder(ticker: String, side: String, qty: Int, price: BigDecimal): String? {
        if (tradingConfig.mode == "SIMULATION") { logger.info { "[SIM] LIMIT $side $qty $ticker @ $price" }; return "sim-${System.currentTimeMillis()}" }
        val body = mapOf("side" to side.lowercase(), "quantity" to qty, "price" to price.toDouble(), "instrument" to mapOf("symbol" to ticker, "exchange" to alorConfig.exchange), "user" to mapOf("portfolio" to alorConfig.portfolio), "type" to "limit")
        return try {
            val r = webClient.post().uri("${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/orders/actions/limit").header("Authorization", "Bearer ${alorConfig.token}").contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve().bodyToMono(JsonNode::class.java).awaitSingle()
            r["orderId"]?.asText()
        } catch (e: Exception) { logger.error(e) { "Alor limit error" }; null }
    }
}
