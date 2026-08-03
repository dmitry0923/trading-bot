package com.trading.bot.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.model.MarketSnapshot
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class AlorClient(
    private val tradingConfig: TradingConfig,
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry
) {
    private val logger = KotlinLogging.logger {}
    private val webClient = WebClient.create()

    data class OrderExecution(
        val status: String,
        val filledQuantity: Int,
        val avgPrice: BigDecimal?
    )

    private var accessToken: String = ""
    private var tokenExpiresAt: Instant = Instant.EPOCH

    private val isLive: Boolean get() = tradingConfig.mode == "LIVE"

    suspend fun getMarketSnapshot(ticker: String): MarketSnapshot? {
        if (!isLive) {
            return MarketSnapshot(
                ticker = ticker,
                currentPrice = BigDecimal("100"),
                bid = BigDecimal("99.9"),
                ask = BigDecimal("100.1"),
                volume = 1_000_000L
            )
        }
        return try {
            val raw: String = webClient.get()
                .uri("${alorConfig.apiUrl}/md/v2/Securities/${alorConfig.exchange}/$ticker/quotes")
                .header("Authorization", "Bearer ${getActualToken()}")
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(Duration.ofSeconds(5))
                .awaitSingle()

            val j = objectMapper.readTree(raw)
            meterRegistry.counter("alor.quotes.ok", Tags.of("ticker", ticker)).increment()
            MarketSnapshot(
                ticker = ticker,
                currentPrice = BigDecimal(j.path("lastPrice").asText("0")),
                bid = j.path("bid").asText().toBigDecimalOrNull(),
                ask = j.path("ask").asText().toBigDecimalOrNull(),
                volume = j.path("volume").asLong(0),
                timestamp = Instant.now()
            )
        } catch (e: Exception) {
            logger.warn(e) { "getMarketSnapshot failed for $ticker" }
            meterRegistry.counter("alor.quotes.error", Tags.of("ticker", ticker)).increment()
            null
        }
    }

    suspend fun getLastPrice(ticker: String): BigDecimal? = getMarketSnapshot(ticker)?.currentPrice

    suspend fun placeLimitOrder(ticker: String, side: String, qty: Int, price: BigDecimal): String? {
        if (!isLive) return "sim-order-$ticker-${System.currentTimeMillis()}"
        return try {
            val body = mapOf(
                "portfolio" to alorConfig.portfolio,
                "ticker" to ticker,
                "exchange" to alorConfig.exchange,
                "side" to side,
                "type" to "limit",
                "quantity" to qty,
                "price" to price.toPlainString(),
                "id" to UUID.randomUUID().toString()
            )
            val raw: String = webClient.post()
                .uri("${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/orders/actions/limit")
                .header("Authorization", "Bearer ${getActualToken()}")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(Duration.ofSeconds(5))
                .awaitSingle()

            val orderNumber = objectMapper.readTree(raw).path("orderNumber").asText().ifBlank { null }
            if (orderNumber != null) {
                meterRegistry.counter("alor.order.placed", Tags.of("side", side)).increment()
            }
            orderNumber
        } catch (e: Exception) {
            logger.error(e) { "placeLimitOrder failed for $ticker" }
            meterRegistry.counter("alor.order.error", Tags.of("side", side)).increment()
            null
        }
    }

    suspend fun placeMarketOrder(ticker: String, side: String, qty: Int): String? {
        if (!isLive) return "sim-market-$ticker-${System.currentTimeMillis()}"
        val snapshot = getMarketSnapshot(ticker) ?: return null
        val price = when (side) {
            "buy" -> snapshot.ask ?: snapshot.currentPrice
            "sell" -> snapshot.bid ?: snapshot.currentPrice
            else -> snapshot.currentPrice
        }
        return placeLimitOrder(ticker, side, qty, price)
    }

    suspend fun verifyOrder(orderId: String): OrderExecution? {
        if (!isLive) return null
        return try {
            val raw: String = webClient.get()
                .uri("${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/orders/$orderId?portfolio=${alorConfig.portfolio}")
                .header("Authorization", "Bearer ${getActualToken()}")
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(Duration.ofSeconds(5))
                .awaitSingle()

            val j = objectMapper.readTree(raw)
            OrderExecution(
                status = j.path("status").asText("UNKNOWN"),
                filledQuantity = j.path("filledQty").asInt(0),
                avgPrice = j.path("filledPrice").asText().toBigDecimalOrNull()
            )
        } catch (e: Exception) {
            logger.warn(e) { "verifyOrder failed for $orderId" }
            null
        }
    }

    private suspend fun getActualToken(): String {
        if (Instant.now().isBefore(tokenExpiresAt.minusSeconds(60)) && accessToken.isNotBlank()) {
            return accessToken
        }
        if (accessToken.isBlank()) accessToken = alorConfig.token
        if (alorConfig.refreshToken.isBlank()) return accessToken

        return try {
            val body = mapOf("refreshToken" to alorConfig.refreshToken)
            val raw: String = webClient.post()
                .uri("${alorConfig.apiUrl}/oauth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(Duration.ofSeconds(5))
                .awaitSingle()

            val j = objectMapper.readTree(raw)
            accessToken = j.path("accessToken").asText(accessToken)
            val expiresIn = j.path("expiresIn").asLong(3600)
            tokenExpiresAt = Instant.now().plusSeconds(expiresIn)
            logger.info { "Alor access token refreshed (expires in ${expiresIn}s)" }
            accessToken
        } catch (e: Exception) {
            logger.warn(e) { "Token refresh failed, using existing token" }
            accessToken
        }
    }
}
