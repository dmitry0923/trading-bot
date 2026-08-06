package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.model.MarketSnapshot
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.kotlin.ratelimiter.decorateSuspendFunction
import io.github.resilience4j.kotlin.retry.decorateSuspendFunction
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * REST-клиент Alor.
 *
 * - Авторизация: Bearer token с автообновлением через refreshToken
 * - WebClient с таймаутом 10s
 * - Resilience4j: Retry (exponential backoff + jitter) + RateLimiter
 *   (инстанс "alor" в application.yml), защита от 429/сетевых сбоев.
 * - Idempotency Key: уникальный `idempotencyKey` для КАЖДОГО POST-ордера.
 *   Ключ генерируется ОДИН раз на логический ордер (в [com.trading.bot.service.OrderOutboxService])
 *   и передаётся в Alor как "id" — Alor дедуплицирует повторные доставки.
 * - [reconcileOrderByIdempotencyKey]: State Reconciliation перед повторным запросом —
 *   поиск реального ордера на бирже по idempotency key (защита от double execution).
 * - Классификация ошибок: 4xx (кроме 429) — определённый отказ (не ретраим);
 *   сетевые/таймауты/5xx/429 — retryable; после исчерпания попыток исключение
 *   пробрасывается наверх, чтобы outbox пометил доставку как UNCERTAIN.
 * - Метрики: alor.api.latency, alor.order.placed, alor.order.error, alor.quotes.ok, trade.slippage.rub
 */
@Component
class AlorClient(
    private val tradingConfig: TradingConfig,
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val retryRegistry: RetryRegistry,
    private val rateLimiterRegistry: RateLimiterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val webClient = WebClient.create()

    data class OrderExecution(
        val status: String,
        val filledQuantity: Int,
        val avgPrice: BigDecimal?,
    )

    /**
     * Результат State Reconciliation (сверки локального стейта с биржей).
     *
     * - [Found]: ордер с данным idempotency key реально существует на бирже
     *   (повторно НЕ отправляем, используем его orderNumber).
     * - [NotFound]: ордера на бирже нет — безопасно переотправить.
     * - [Unknown]: биржа недоступна, подтвердить нельзя — НЕ переотправляем
     *   (fail-safe, защита от двойного исполнения).
     */
    sealed interface OrderReconciliation {
        data class Found(
            val orderNumber: String,
            val filledQty: Int,
            val avgPrice: BigDecimal?,
        ) : OrderReconciliation

        data object NotFound : OrderReconciliation

        data object Unknown : OrderReconciliation
    }

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
        val start = System.currentTimeMillis()
        return try {
            val raw: String =
                resilient {
                    webClient
                        .get()
                        .uri("${alorConfig.apiUrl}/md/v2/Securities/${alorConfig.exchange}/$ticker/quotes")
                        .header("Authorization", "Bearer ${getActualToken()}")
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(Duration.ofSeconds(10))
                        .awaitSingle()
                }
            recordLatency("getQuotes", start)
            meterRegistry.counter("alor.quotes.ok", Tags.of("ticker", ticker)).increment()
            val j = objectMapper.readTree(raw)
            MarketSnapshot(
                ticker = ticker,
                currentPrice = BigDecimal(j.path("lastPrice").asString("0")),
                bid = j.path("bid").asString().toBigDecimalOrNull(),
                ask = j.path("ask").asString().toBigDecimalOrNull(),
                volume = j.path("volume").asLong(0),
                timestamp = Instant.now(),
            )
        } catch (e: Exception) {
            logger.warn(e) { "getMarketSnapshot failed for $ticker" }
            meterRegistry.counter("alor.quotes.error", Tags.of("ticker", ticker)).increment()
            null
        }
    }

    suspend fun getLastPrice(ticker: String): BigDecimal? = getMarketSnapshot(ticker)?.currentPrice

    /**
     * Плейс лимитного ордера с обязательным idempotency key.
     *
     * @param idempotencyKey уникальный клиентский id ордера (генерируется один раз
     *   на логический ордер в OrderOutboxService). Все повторные доставки/ретраи
     *   используют ТОТ ЖЕ ключ → Alor дедуплицирует.
     * @return orderNumber при успехе; null при определённом отказе биржи (4xx);
     *   при сетевом сбое/таймауте/5xx после исчерпания ретраев — бросает исключение
     *   (верхний слой пометит доставку как UNCERTAIN и выполнит State Reconciliation).
     */
    suspend fun placeLimitOrder(
        ticker: String,
        side: String,
        qty: Int,
        price: BigDecimal,
        idempotencyKey: String,
    ): String? {
        if (!isLive) return "sim-$ticker-$idempotencyKey"
        val start = System.currentTimeMillis()
        return try {
            val body =
                mapOf(
                    "portfolio" to alorConfig.portfolio,
                    "ticker" to ticker,
                    "exchange" to alorConfig.exchange,
                    "side" to side,
                    "type" to "limit",
                    "quantity" to qty,
                    "price" to price.toPlainString(),
                    "id" to idempotencyKey,
                )
            val raw: String =
                resilient {
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
                }
            recordLatency("placeLimitOrder", start)
            val orderNumber =
                objectMapper
                    .readTree(raw)
                    .path("orderNumber")
                    .asString()
                    .ifBlank { null }
            if (orderNumber != null) {
                meterRegistry.counter("alor.order.placed", Tags.of("type", "limit", "status", "OK")).increment()
                logger.info { "Limit order placed $side $qty $ticker @ $price -> $orderNumber (idem=$idempotencyKey)" }
            }
            orderNumber
        } catch (e: WebClientResponseException) {
            meterRegistry.counter("alor.order.error", Tags.of("side", side, "type", "limit")).increment()
            if (isDefinitiveRejection(e)) {
                logger.error(e) { "Limit order REJECTED by Alor $ticker (${e.statusCode.value()}): ${e.responseBodyAsString.take(500)}" }
                null
            } else {
                logger.error(e) { "placeLimitOrder failed for $ticker after retries (${e.statusCode.value()}) — delivery UNCERTAIN" }
                throw e
            }
        } catch (e: Exception) {
            logger.error(e) { "placeLimitOrder failed for $ticker after retries — delivery UNCERTAIN" }
            meterRegistry.counter("alor.order.error", Tags.of("side", side, "type", "limit")).increment()
            throw e
        }
    }

    /**
     * Маркет-ордер (через лимитный по лучшему ask/bid). Запрещён при спреде > 0.5% (slippage control).
     * Использует тот же [idempotencyKey], что и базовый лимитный ордер.
     */
    suspend fun placeMarketOrder(
        ticker: String,
        side: String,
        qty: Int,
        idempotencyKey: String,
    ): String? {
        if (!isLive) return "sim-$ticker-$idempotencyKey"
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
        val orderId = placeLimitOrder(ticker, side, qty, price, idempotencyKey)
        if (orderId != null) {
            meterRegistry.counter("alor.order.placed", Tags.of("type", "market", "status", "OK")).increment()
        }
        return orderId
    }

    /**
     * State Reconciliation: ищет на бирже реальный ордер по idempotency key
     * (GET orders по портфелю). Перед ЛЮБЫМ повторным запросом бот обязан
     * провести эту сверку — см. OrderOutboxService.dispatch.
     */
    suspend fun reconcileOrderByIdempotencyKey(
        idempotencyKey: String,
        ticker: String,
        side: String,
    ): OrderReconciliation {
        if (!isLive || idempotencyKey.isBlank()) return OrderReconciliation.NotFound
        return try {
            val raw: String =
                resilient {
                    webClient
                        .get()
                        .uri(
                            "${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/orders" +
                                "?portfolio=${alorConfig.portfolio}&includeOrders=true",
                        ).header("Authorization", "Bearer ${getActualToken()}")
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(Duration.ofSeconds(10))
                        .awaitSingle()
                }
            val root = objectMapper.readTree(raw)
            val orders = if (root.isArray) root else root.path("orders")
            for (order in orders) {
                if (order.path("id").asString() != idempotencyKey) continue
                val orderTicker = order.path("ticker").asString()
                val orderSide = order.path("side").asString()
                if (orderTicker.isNotBlank() && orderTicker != ticker) continue
                if (orderSide.isNotBlank() && orderSide != side) continue
                val orderNumber =
                    order
                        .path("orderNumber")
                        .asString()
                        .ifBlank { order.path("id").asString() }
                val filledQty =
                    order
                        .path("filledQty")
                        .asInt(0)
                        .let { if (it == 0) order.path("filledQuantity").asInt(0) else it }
                val avgPrice =
                    order.path("filledPrice").asString().toBigDecimalOrNull()
                        ?: order.path("avgFillPrice").asString().toBigDecimalOrNull()
                meterRegistry.counter("alor.reconcile", Tags.of("result", "FOUND")).increment()
                logger.info { "Reconciliation FOUND idem=$idempotencyKey -> order=$orderNumber filled=$filledQty" }
                return OrderReconciliation.Found(orderNumber, filledQty, avgPrice)
            }
            meterRegistry.counter("alor.reconcile", Tags.of("result", "NOT_FOUND")).increment()
            logger.info { "Reconciliation NOT_FOUND idem=$idempotencyKey (safe to re-send)" }
            OrderReconciliation.NotFound
        } catch (e: Exception) {
            logger.warn(e) { "Reconciliation UNKNOWN for idem=$idempotencyKey (exchange unreachable) — skip re-send" }
            meterRegistry.counter("alor.reconcile", Tags.of("result", "UNKNOWN")).increment()
            OrderReconciliation.Unknown
        }
    }

    /**
     * Проверка исполнения ордера + запись проскальзывания.
     */
    suspend fun verifyOrder(
        orderId: String,
        expectedPrice: BigDecimal? = null,
    ): OrderExecution? {
        if (!isLive) return null
        val start = System.currentTimeMillis()
        return try {
            val raw: String =
                resilient {
                    webClient
                        .get()
                        .uri("${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/orders/$orderId?portfolio=${alorConfig.portfolio}")
                        .header("Authorization", "Bearer ${getActualToken()}")
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(Duration.ofSeconds(10))
                        .awaitSingle()
                }
            recordLatency("verifyOrder", start)
            val j = objectMapper.readTree(raw)
            val execution =
                OrderExecution(
                    status = j.path("status").asString("UNKNOWN"),
                    filledQuantity = j.path("filledQty").asInt(0),
                    avgPrice = j.path("filledPrice").asString().toBigDecimalOrNull(),
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

    /**
     * Состояние заявки из REST-сверки.
     */
    data class ExchangeOrder(
        val orderId: String,
        val ticker: String,
        val side: String?,
        val status: String,
        val quantity: Int,
        val filledQty: Int,
        val avgPrice: BigDecimal?,
        val time: Instant? = null,
    )

    /**
     * Позиция портфеля из REST-сверки. [qty] — знаковая: > 0 LONG, < 0 SHORT.
     */
    data class ExchangePosition(
        val ticker: String,
        val qty: Long,
        val avgPrice: BigDecimal?,
        val time: Instant? = null,
    )

    /**
     * Сделка портфеля из REST-сверки.
     */
    data class ExchangeTrade(
        val id: String,
        val orderId: String?,
        val ticker: String,
        val side: String?,
        val quantity: Int,
        val price: BigDecimal,
        val time: Instant? = null,
    )

    /**
     * Результат REST-сверки (State Reconciliation).
     *
     * - [Ok]: данные получены (в т.ч. пустой список — биржа действительно «плоская»).
     * - [Failed]: REST недоступен/ошибка — сверять локальный стейт НЕЛЬЗЯ.
     *   [com.trading.bot.service.StateReconciliationService] в этом случае НЕ мутирует
     *   локальные позиции (fail-safe: отсутствие ответа != отсутствие позиции).
     */
    sealed interface ReconcileResult<out T> {
        data class Ok<out T>(
            val items: List<T>,
        ) : ReconcileResult<T>

        data object Failed : ReconcileResult<Nothing>
    }

    /**
     * Все заявки портфеля (State Reconciliation). [ReconcileResult.Failed] при
     * ошибке REST — сверка не должна ронять бота и не должна выдавать «пусто»
     * за отсутствие заявок.
     */
    suspend fun getOpenOrders(): ReconcileResult<ExchangeOrder> {
        if (!isLive) return ReconcileResult.Ok(emptyList())
        return try {
            val raw: String =
                resilient {
                    webClient
                        .get()
                        .uri(
                            "${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/orders" +
                                "?portfolio=${alorConfig.portfolio}&includeOrders=true",
                        ).header("Authorization", "Bearer ${getActualToken()}")
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(Duration.ofSeconds(10))
                        .awaitSingle()
                }
            val root = objectMapper.readTree(raw)
            val arr = if (root.isArray) root else root.path("orders")
            val items =
                arr.mapNotNull { o ->
                    val orderId =
                        o
                            .path("orderNumber")
                            .asString()
                            .ifBlank { o.path("id").asString() }
                            .ifBlank { return@mapNotNull null }
                    ExchangeOrder(
                        orderId = orderId,
                        ticker = o.path("ticker").asString(""),
                        side = o.path("side").asString().takeIf { it.isNotBlank() },
                        status = o.path("status").asString("UNKNOWN"),
                        quantity = o.path("quantity").asInt(0),
                        filledQty =
                            o
                                .path("filledQty")
                                .asInt(0)
                                .let { if (it == 0) o.path("filledQuantity").asInt(0) else it },
                        avgPrice =
                            o.path("filledPrice").asString().toBigDecimalOrNull()
                                ?: o.path("avgFillPrice").asString().toBigDecimalOrNull(),
                        time = parseAlorTime(o.path("time")),
                    )
                }
            ReconcileResult.Ok(items)
        } catch (e: Exception) {
            meterRegistry.counter("alor.reconcile.fetch_error", Tags.of("kind", "orders")).increment()
            logger.warn(e) { "getOpenOrders failed" }
            ReconcileResult.Failed
        }
    }

    /**
     * Текущие позиции портфеля (State Reconciliation). [ReconcileResult.Failed] при ошибке.
     */
    suspend fun getPositions(): ReconcileResult<ExchangePosition> {
        if (!isLive) return ReconcileResult.Ok(emptyList())
        return try {
            val raw: String =
                resilient {
                    webClient
                        .get()
                        .uri(
                            "${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/portfolios" +
                                "/${alorConfig.portfolio}/positions",
                        ).header("Authorization", "Bearer ${getActualToken()}")
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(Duration.ofSeconds(10))
                        .awaitSingle()
                }
            val root = objectMapper.readTree(raw)
            val arr = if (root.isArray) root else root.path("positions")
            val items =
                arr.mapNotNull { p ->
                    val ticker =
                        p
                            .path("ticker")
                            .asString()
                            .ifBlank { p.path("symbol").asString() }
                            .ifBlank { return@mapNotNull null }
                    val qty =
                        p
                            .path("qty")
                            .asLong(0)
                            .let { if (it == 0L) p.path("quantity").asLong(0) else it }
                    ExchangePosition(
                        ticker = ticker,
                        qty = qty,
                        avgPrice =
                            p.path("averagePrice").asString().toBigDecimalOrNull()
                                ?: p.path("avgPrice").asString().toBigDecimalOrNull()
                                ?: p.path("avgFillPrice").asString().toBigDecimalOrNull(),
                        time = parseAlorTime(p.path("time")),
                    )
                }
            ReconcileResult.Ok(items)
        } catch (e: Exception) {
            meterRegistry.counter("alor.reconcile.fetch_error", Tags.of("kind", "positions")).increment()
            logger.warn(e) { "getPositions failed" }
            ReconcileResult.Failed
        }
    }

    /**
     * Сделки портфеля за текущую сессию (State Reconciliation). [ReconcileResult.Failed] при ошибке.
     */
    suspend fun getRecentTrades(): ReconcileResult<ExchangeTrade> {
        if (!isLive) return ReconcileResult.Ok(emptyList())
        return try {
            val raw: String =
                resilient {
                    webClient
                        .get()
                        .uri(
                            "${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/portfolios" +
                                "/${alorConfig.portfolio}/trades",
                        ).header("Authorization", "Bearer ${getActualToken()}")
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(Duration.ofSeconds(10))
                        .awaitSingle()
                }
            val root = objectMapper.readTree(raw)
            val arr = if (root.isArray) root else root.path("trades")
            val items =
                arr.mapNotNull { t ->
                    val id =
                        t
                            .path("id")
                            .asString()
                            .ifBlank { t.path("tradeId").asString() }
                            .ifBlank { return@mapNotNull null }
                    val price =
                        t.path("price").asString().toBigDecimalOrNull()
                            ?: return@mapNotNull null
                    ExchangeTrade(
                        id = id,
                        orderId =
                            t
                                .path("orderId")
                                .asString()
                                .ifBlank { t.path("orderNumber").asString() }
                                .takeIf { it.isNotBlank() },
                        ticker = t.path("ticker").asString().ifBlank { t.path("symbol").asString() },
                        side = t.path("side").asString().takeIf { it.isNotBlank() },
                        quantity =
                            t
                                .path("quantity")
                                .asInt(0)
                                .let { if (it == 0) t.path("qty").asInt(0) else it },
                        price = price,
                        time = parseAlorTime(t.path("time")),
                    )
                }
            ReconcileResult.Ok(items)
        } catch (e: Exception) {
            meterRegistry.counter("alor.reconcile.fetch_error", Tags.of("kind", "trades")).increment()
            logger.warn(e) { "getRecentTrades failed" }
            ReconcileResult.Failed
        }
    }

    /**
     * Разбирает время Alor из Unix-эпохи (секунды или миллисекунды).
     */
    private fun parseAlorTime(node: tools.jackson.databind.JsonNode): Instant? {
        val raw = node.asLong(0)
        if (raw <= 0) return null
        return if (raw > 9_999_999_999L) Instant.ofEpochMilli(raw) else Instant.ofEpochSecond(raw)
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

    /**
     * Определённый отказ биржи (4xx, кроме 429): ордер не принят — ретраить бессмысленно.
     * Всё остальное (сеть, таймаут, 5xx, 429) — retryable/UNCERTAIN.
     */
    private fun isDefinitiveRejection(t: Throwable): Boolean =
        t is WebClientResponseException &&
            t.statusCode.value() in 400..499 &&
            t.statusCode.value() != 429

    /**
     * Оборачивает HTTP-вызов в Resilience4j: RateLimiter (внутри, лимит на каждую
     * попытку) → Retry с exponential backoff + jitter (снаружи). Конфиг — application.yml
     * (resilience4j.retry.instances.alor / ratelimiter.instances.alor).
     */
    private suspend fun <T> resilient(block: suspend () -> T): T {
        var call: suspend () -> T = block
        if (alorConfig.rateLimiterEnabled) {
            call = rateLimiterRegistry.rateLimiter("alor").decorateSuspendFunction { call() }
        }
        if (alorConfig.retryEnabled) {
            call = retryRegistry.retry("alor").decorateSuspendFunction { call() }
        }
        return call()
    }

    private fun recordLatency(
        operation: String,
        startMs: Long,
    ) {
        meterRegistry
            .timer("alor.api.latency", Tags.of("operation", operation))
            .record(System.currentTimeMillis() - startMs, TimeUnit.MILLISECONDS)
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
            accessToken = j.path("accessToken").asString(accessToken)
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
