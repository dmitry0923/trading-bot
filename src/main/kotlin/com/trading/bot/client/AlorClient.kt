package com.trading.bot.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.model.MarketSnapshot
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * REST-клиент Alor.
 *
 * - Авторизация: Bearer token с автообновлением через refreshToken
 * - WebClient с таймаутом 10s
 * - Retry с экспоненциальным backoff (max 3 попытки) на критичных вызовах
 * - Idempotency Key для каждого ордера (ticker+side+qty+price+timestamp)
 * - Контроль проскальзывания: маркет-ордер запрещён при спреде > 0.5%
 * - Метрики: alor.api.latency, alor.order.placed, alor.order.error, alor.quotes.ok, trade.slippage.rub
 */
@Component
class AlorClient(
    private val tradingConfig: TradingConfig,
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val webClient = WebClient.create()

    data class OrderExecution(
        val status: String,
        val filledQuantity: Int,
        val avgPrice: BigDecimal?,
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
                volume = 1_000_000L,
            )
        }
        return withRetry(ticker) {
            val start = System.currentTimeMillis()
            try {
                val raw: String =
                    webClient
                        .get()
                        .uri("${alorConfig.apiUrl}/md/v2/Securities/${alorConfig.exchange}/$ticker/quotes")
                        .header("Authorization", "Bearer ${getActualToken()}")
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(Duration.ofSeconds(10))
                        .awaitSingle()

                val j = objectMapper.readTree(raw)
                recordLatency("getQuotes", start)
                meterRegistry.counter("alor.quotes.ok", Tags.of("ticker", ticker)).increment()
                MarketSnapshot(
                    ticker = ticker,
                    currentPrice = BigDecimal(j.path("lastPrice").asText("0")),
                    bid = j.path("bid").asText().toBigDecimalOrNull(),
                    ask = j.path("ask").asText().toBigDecimalOrNull(),
                    volume = j.path("volume").asLong(0),
                    timestamp = Instant.now(),
                )
            } catch (e: Exception) {
                logger.warn(e) { "getMarketSnapshot failed for $ticker" }
                meterRegistry.counter("alor.quotes.error", Tags.of("ticker", ticker)).increment()
                null
            }
        }
    }

    suspend fun getLastPrice(ticker: String): BigDecimal? = getMarketSnapshot(ticker)?.currentPrice

    suspend fun placeLimitOrder(
        ticker: String,
        side: String,
        qty: Int,
        price: BigDecimal,
        clientOrderId: String? = null,
    ): String? {
        if (!isLive) return "sim-order-$ticker-${System.currentTimeMillis()}"
        val stableOrderId = clientOrderId ?: idempotencyKey(ticker, side, qty, price, "limit")
        return try {
            withRetry(ticker) {
                val start = System.currentTimeMillis()
                val body =
                    mapOf(
                        "portfolio" to alorConfig.portfolio,
                        "ticker" to ticker,
                        "exchange" to alorConfig.exchange,
                        "side" to side,
                        "type" to "limit",
                        "quantity" to qty,
                        "price" to price.toPlainString(),
                        "id" to stableOrderId,
                    )
                val raw: String =
                    webClient
                        .post()
                        .uri("${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/orders/actions/limit")
                        .header("Authorization", "Bearer ${getActualToken()}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(objectMapper.writeValueAsString(body))
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(Duration.ofSeconds(10))
                        .awaitSingle()

                recordLatency("placeLimitOrder", start)
                val orderNumber =
                    objectMapper
                        .readTree(raw)
                        .path("orderNumber")
                        .asText()
                        .ifBlank { null }
                if (orderNumber != null) {
                    meterRegistry.counter("alor.order.placed", Tags.of("type", "limit", "status", "OK")).increment()
                    logger.info {
                        "Limit order placed $side $qty $ticker @ $price -> $orderNumber (idem=$stableOrderId)"
                    }
                }
                orderNumber
            }
        } catch (e: Exception) {
            logger.error(e) { "placeLimitOrder failed for $ticker" }
            meterRegistry.counter("alor.order.error", Tags.of("side", side, "type", "limit")).increment()
            null
        }
    }

    /**
     * Маркет-ордер. Запрещён при спреде > 0.5% (slippage control).
     */
    suspend fun placeMarketOrder(
        ticker: String,
        side: String,
        qty: Int,
        clientOrderId: String? = null,
    ): String? {
        if (!isLive) return "sim-market-$ticker-${System.currentTimeMillis()}"
        val snapshot = getMarketSnapshot(ticker) ?: return null

        val spread = spreadPercent(snapshot)
        if (spread > BigDecimal("0.005")) {
            logger.warn { "Market order BLOCKED for $ticker: spread ${spread.movePointRight(2)}% > 0.5%" }
            meterRegistry.counter("alor.order.blocked", Tags.of("reason", "WIDE_SPREAD")).increment()
            return null
        }

        val price =
            when (side) {
                "buy" -> snapshot.ask ?: snapshot.currentPrice
                "sell" -> snapshot.bid ?: snapshot.currentPrice
                else -> snapshot.currentPrice
            }
        val orderId = placeLimitOrder(ticker, side, qty, price, clientOrderId)
        if (orderId != null) {
            meterRegistry.counter("alor.order.placed", Tags.of("type", "market", "status", "OK")).increment()
        }
        return orderId
    }

    /**
     * Проверка исполнения ордера + запись проскальзывания.
     */
    suspend fun verifyOrder(
        orderId: String,
        expectedPrice: BigDecimal? = null,
    ): OrderExecution? {
        if (!isLive) return null
        return withRetry(null) {
            val start = System.currentTimeMillis()
            try {
                val raw: String =
                    webClient
                        .get()
                        .uri("${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/orders/$orderId?portfolio=${alorConfig.portfolio}")
                        .header("Authorization", "Bearer ${getActualToken()}")
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(Duration.ofSeconds(10))
                        .awaitSingle()

                recordLatency("verifyOrder", start)
                val j = objectMapper.readTree(raw)
                val execution =
                    OrderExecution(
                        status = j.path("status").asText("UNKNOWN"),
                        filledQuantity = j.path("filledQty").asInt(0),
                        avgPrice = j.path("filledPrice").asText().toBigDecimalOrNull(),
                    )
                if (expectedPrice != null && execution.avgPrice != null) {
                    recordSlippage(expectedPrice, execution.avgPrice, execution.filledQuantity)
                }
                execution
            } catch (e: Exception) {
                logger.warn(e) { "verifyOrder failed for $orderId" }
                null
            }
        }
    }

    /**
     * Метрика проскальзывания: |expected - filled| * qty в рублях.
     */
    fun recordSlippage(
        expectedPrice: BigDecimal,
        filledPrice: BigDecimal,
        qty: Int,
    ) {
        val slippageRub = expectedPrice.subtract(filledPrice).abs().multiply(BigDecimal(qty))
        meterRegistry.counter("trade.slippage.rub").increment(slippageRub.toDouble())
        logger.info { "Slippage: expected=$expectedPrice filled=$filledPrice qty=$qty => $slippageRub RUB" }
    }

    private fun spreadPercent(snapshot: MarketSnapshot): BigDecimal {
        val bid = snapshot.bid ?: snapshot.currentPrice
        val ask = snapshot.ask ?: snapshot.currentPrice
        if (bid <= BigDecimal.ZERO || ask <= BigDecimal.ZERO || bid >= ask) return BigDecimal.ZERO
        return ask.subtract(bid).divide(ask, 6, RoundingMode.HALF_UP)
    }

    private fun idempotencyKey(
        ticker: String,
        side: String,
        qty: Int,
        price: BigDecimal,
        type: String,
    ): String {
        val raw = "$ticker|$side|$qty|$price|$type|${Instant.now().toEpochMilli()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private suspend fun <T> withRetry(
        ticker: String?,
        block: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= 3) throw e
                val backoff = (1L shl (attempt - 1)) * 1000L
                logger.warn(e) { "Retrying ($attempt/3) in ${backoff}ms${ticker?.let { " for $it" } ?: ""}" }
                delay(backoff)
            }
        }
    }

    private fun recordLatency(
        operation: String,
        startMs: Long,
    ) {
        meterRegistry
            .timer("alor.api.latency", Tags.of("operation", operation))
            .record(System.currentTimeMillis() - startMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private suspend fun getActualToken(): String {
        if (Instant.now().isBefore(tokenExpiresAt.minusSeconds(60)) && accessToken.isNotBlank()) {
            return accessToken
        }
        if (accessToken.isBlank()) accessToken = alorConfig.token
        if (alorConfig.refreshToken.isBlank()) return accessToken

        return try {
            val body = mapOf("refreshToken" to alorConfig.refreshToken)
            val raw: String =
                webClient
                    .post()
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
