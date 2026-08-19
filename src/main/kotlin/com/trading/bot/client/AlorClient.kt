package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.domain.microstructure.MicropriceCalculator
import com.trading.bot.domain.microstructure.ObiCalculator
import com.trading.bot.domain.risk.DegenerateCaseDetector
import com.trading.bot.model.dto.MarketSnapshot
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.kotlin.circuitbreaker.decorateSuspendFunction
import io.github.resilience4j.kotlin.ratelimiter.decorateSuspendFunction
import io.github.resilience4j.kotlin.retry.decorateSuspendFunction
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * REST-клиент Alor (quotes, reconciliation) + делегирование доставки ордеров.
 *
 * - Авторизация: Bearer token через [AlorTokenProvider] (общий для всех транспортов).
 * - WebClient с таймаутом 10s; Resilience4j: Retry + RateLimiter + CircuitBreaker
 *   (инстанс "alor" в application.yml), защита от 429/сетевых сбоев.
 * - Размещение/отмена ордеров делегируются в [OrderTransport] (roadmap 13.8.2):
 *   [RoutedOrderTransport] шлёт по WebSocket primary, по REST — fallback.
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
    private val tokenProvider: AlorTokenProvider,
    private val orderTransport: RoutedOrderTransport,
    private val retryRegistry: RetryRegistry,
    private val rateLimiterRegistry: RateLimiterRegistry,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
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

    private val isLive: Boolean get() = tradingConfig.mode == "LIVE"

    /** LIVE-режим (публичное окно [isLive]) — используется для гейта биржевых SL/TP-заявок. */
    val isLiveMode: Boolean get() = isLive

    suspend fun getMarketSnapshot(ticker: String): MarketSnapshot? {
        if (!isLive) {
            val seed = ticker.hashCode().toLong().and(0x7FFFFFFFL)
            val base = 100 + (seed % 900)
            val price = BigDecimal(base).setScale(2)
            val bid = price.multiply(BigDecimal("0.999")).setScale(2)
            val ask = price.multiply(BigDecimal("1.001")).setScale(2)
            val bidSize = 150L + (seed % 850)
            val askSize = 150L + ((seed shr 8) % 850)
            logger.warn { "SIMULATION mode: returning synthetic price for $ticker = $price" }
            return MarketSnapshot(
                ticker = ticker,
                currentPrice = price,
                bid = bid,
                ask = ask,
                volume = 1_000_000L,
                bidSize = bidSize,
                askSize = askSize,
                microprice = MicropriceCalculator.calculate(bid, ask, bidSize, askSize),
                obi = ObiCalculator.calculate(bidSize, askSize),
            )
        }
        val start = System.currentTimeMillis()
        return try {
            val raw: String =
                resilient {
                    webClient
                        .get()
                        .uri("${alorConfig.apiUrl}/md/v2/Securities/${alorConfig.exchange}/$ticker/quotes")
                        .header("Authorization", "Bearer ${tokenProvider.actualToken()}")
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(Duration.ofSeconds(10))
                        .awaitSingle()
                }
            recordLatency("getQuotes", start)
            meterRegistry.counter("alor.quotes.ok", Tags.of("ticker", ticker)).increment()
            val j = objectMapper.readTree(raw)
            val bid = j.path("bid").asString().toBigDecimalOrNull()
            val ask = j.path("ask").asString().toBigDecimalOrNull()
            val bidSize = j.path("bidVolume").asLong(0).takeIf { it > 0 }
            val askSize = j.path("askVolume").asLong(0).takeIf { it > 0 }
            MarketSnapshot(
                ticker = ticker,
                currentPrice = BigDecimal(j.path("lastPrice").asString("0")),
                bid = bid,
                ask = ask,
                volume = j.path("volume").asLong(0),
                bidSize = bidSize,
                askSize = askSize,
                microprice = MicropriceCalculator.calculate(bid, ask, bidSize, askSize),
                obi = ObiCalculator.calculate(bidSize, askSize),
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
     * Плейс лимитного ордера с обязательным idempotency key (делегирование в [OrderTransport]).
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
        portfolio: String = alorConfig.portfolio,
    ): String? = orderTransport.placeLimit(ticker, side, qty, price, idempotencyKey, portfolio)

    /**
     * Маркет-ордер (через лимитный по лучшему ask/bid). Запрещён при спреде > 0.5%
     * (slippage control) — кроме [forceMarket] (ликвидационные/emergency-закрытия,
     * EXEC-003/004): там блокировка опаснее проскальзывания, поэтому ордер исполняется
     * по лучшему ask/bid (slippage ограничен стаканом), но факт флага логируется и
     * считает метрику alor.order.forced_market.
     * Использует тот же [idempotencyKey], что и базовый лимитный ордер.
     */
    suspend fun placeMarketOrder(
        ticker: String,
        side: String,
        qty: Int,
        idempotencyKey: String,
        portfolio: String = alorConfig.portfolio,
        forceMarket: Boolean = false,
    ): String? {
        if (!isLive) return "sim-$ticker-$idempotencyKey"
        val snapshot = getMarketSnapshot(ticker) ?: return null

        val spread = spreadPercent(snapshot)
        if (spread > BigDecimal("0.005")) {
            if (!forceMarket) {
                logger.warn { "Market order BLOCKED for $ticker: spread ${spread.movePointRight(2)}% > 0.5%" }
                meterRegistry.counter("alor.order.blocked", Tags.of("reason", "WIDE_SPREAD")).increment()
                return null
            }
            logger.warn {
                "Market order FORCED for $ticker (emergency/liquidation close): " +
                    "spread ${spread.movePointRight(2)}% > 0.5% — executing at best ${if (side == "buy") "ask" else "bid"}"
            }
            meterRegistry.counter("alor.order.forced_market", Tags.of("ticker", ticker)).increment()
        }

        val price =
            when (side) {
                "buy" -> snapshot.ask ?: snapshot.currentPrice
                "sell" -> snapshot.bid ?: snapshot.currentPrice
                else -> snapshot.currentPrice
            }
        val orderId = placeLimitOrder(ticker, side, qty, price, idempotencyKey, portfolio)
        if (orderId != null) {
            meterRegistry.counter("alor.order.placed", Tags.of("type", "market", "status", "OK")).increment()
        }
        return orderId
    }

    /**
     * Стоп-заявка (type="stop"): срабатывает при пересечении ценой [stopPrice]
     * (LONG: вниз, SHORT: вверх), после чего исполняется по рынку. Используется
     * как биржевой stop-loss при открытии позиции (roadmap v2.2).
     *
     * Тот же контракт доставки, что и [placeLimitOrder]: idempotencyKey обязателен,
     * null при определённом отказе, исключение при UNCERTAIN.
     */
    suspend fun placeStopOrder(
        ticker: String,
        side: String,
        qty: Int,
        stopPrice: BigDecimal,
        idempotencyKey: String,
        portfolio: String = alorConfig.portfolio,
    ): String? = placeConditionalOrder("stop", ticker, side, qty, stopPrice, idempotencyKey, portfolio)

    /**
     * Тейк-профит-заявка (type="take-profit"): срабатывает при пересечении ценой
     * [stopPrice] в прибыль (LONG: вверх, SHORT: вниз), исполняется по рынку.
     * Биржевой take-profit при открытии позиции.
     */
    suspend fun placeTakeProfitOrder(
        ticker: String,
        side: String,
        qty: Int,
        stopPrice: BigDecimal,
        idempotencyKey: String,
        portfolio: String = alorConfig.portfolio,
    ): String? = placeConditionalOrder("take-profit", ticker, side, qty, stopPrice, idempotencyKey, portfolio)

    /**
     * Условная (стоп/тейк) заявка (делегирование в [OrderTransport]).
     * `stopEndUnixTime`=0 — действует до конца торговой сессии.
     */
    private suspend fun placeConditionalOrder(
        type: String,
        ticker: String,
        side: String,
        qty: Int,
        stopPrice: BigDecimal,
        idempotencyKey: String,
        portfolio: String,
    ): String? = orderTransport.placeConditional(type, ticker, side, qty, stopPrice, idempotencyKey, portfolio)

    /**
     * Результат отмены заявки (см. [CancelResult]).
     */
    typealias CancelResult = com.trading.bot.client.CancelResult

    /**
     * Отмена заявки по orderId (делегирование в [OrderTransport]). Идемпотентный
     * ключ передаётся в поле "id" (защита от двойной отмены при повторах).
     */
    suspend fun cancelOrder(
        orderId: String,
        idempotencyKey: String,
        portfolio: String = alorConfig.portfolio,
    ): CancelResult = orderTransport.cancel(orderId, idempotencyKey, portfolio)

    /**
     * State Reconciliation: ищет на бирже реальный ордер по idempotency key
     * (GET orders по портфелю). Перед ЛЮБЫМ повторным запросом бот обязан
     * провести эту сверку — см. OrderOutboxService.dispatch.
     */
    suspend fun reconcileOrderByIdempotencyKey(
        idempotencyKey: String,
        ticker: String,
        side: String,
        portfolio: String = alorConfig.portfolio,
    ): OrderReconciliation {
        if (!isLive || idempotencyKey.isBlank()) return OrderReconciliation.NotFound
        return try {
            val orders = fetchOrdersJson(portfolio)
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
        portfolio: String = alorConfig.portfolio,
    ): OrderExecution? {
        if (!isLive) return null
        val start = System.currentTimeMillis()
        return try {
            val raw: String =
                resilient {
                    webClient
                        .get()
                        .uri("${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/orders/$orderId?portfolio=$portfolio")
                        .header("Authorization", "Bearer ${tokenProvider.actualToken()}")
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
                    // EXEC-2 (roadmap 13.27): fallback на filledQuantity, если Alor не
                    // вернул filledQty (единый парсинг с reconcileOrderByIdempotencyKey,
                    // getOpenOrders, parseExecution).
                    filledQuantity =
                        j
                            .path("filledQty")
                            .asInt(0)
                            .let { if (it == 0) j.path("filledQuantity").asInt(0) else it },
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
    suspend fun getOpenOrders(portfolio: String = alorConfig.portfolio): ReconcileResult<ExchangeOrder> {
        if (!isLive) return ReconcileResult.Ok(emptyList())
        return try {
            val arr = fetchOrdersJson(portfolio)
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
    suspend fun getPositions(portfolio: String = alorConfig.portfolio): ReconcileResult<ExchangePosition> {
        if (!isLive) return ReconcileResult.Ok(emptyList())
        return try {
            val raw: String =
                resilient {
                    webClient
                        .get()
                        .uri(
                            "${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/portfolios" +
                                "/$portfolio/positions",
                        ).header("Authorization", "Bearer ${tokenProvider.actualToken()}")
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
    suspend fun getRecentTrades(portfolio: String = alorConfig.portfolio): ReconcileResult<ExchangeTrade> {
        if (!isLive) return ReconcileResult.Ok(emptyList())
        return try {
            val raw: String =
                resilient {
                    webClient
                        .get()
                        .uri(
                            "${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/portfolios" +
                                "/$portfolio/trades",
                        ).header("Authorization", "Bearer ${tokenProvider.actualToken()}")
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
        ticker: String = "",
    ) {
        val slippageRub = expectedPrice.subtract(filledPrice).abs().multiply(BigDecimal(qty))
        meterRegistry.counter("trade.slippage.rub", Tags.of("ticker", ticker)).increment(slippageRub.toDouble())
        logger.info { "Slippage: expected=$expectedPrice filled=$filledPrice qty=$qty => $slippageRub RUB" }
    }

    private fun spreadPercent(snapshot: MarketSnapshot): BigDecimal =
        DegenerateCaseDetector.spreadPercent(snapshot.bid, snapshot.ask, snapshot.currentPrice)

    /**
     * Оборачивает HTTP-вызов в Resilience4j: RateLimiter (внутри, лимит на каждую
     * попытку) → Retry с exponential backoff + jitter (снаружи). Конфиг — application.yml
     * (resilience4j.retry.instances.alor / ratelimiter.instances.alor).
     */
    private suspend fun fetchOrdersJson(portfolio: String): tools.jackson.databind.JsonNode {
        val raw: String =
            resilient {
                webClient
                    .get()
                    .uri(
                        "${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/orders" +
                            "?portfolio=$portfolio&includeOrders=true",
                    ).header("Authorization", "Bearer ${tokenProvider.actualToken()}")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .awaitSingle()
            }
        val root = objectMapper.readTree(raw)
        return if (root.isArray) root else root.path("orders")
    }

    /**
     * Оборачивает HTTP-вызов в Resilience4j: CircuitBreaker (самый внутренний —
     * размыкается при падении биржи и гасит таранный залп) → RateLimiter (лимит
     * на каждую попытку) → Retry с exponential backoff + jitter (снаружи).
     * Конфиг — application.yml (resilience4j.circuitbreaker/ratelimiter/retry.instances.alor).
     */
    private suspend fun <T> resilient(block: suspend () -> T): T {
        var call: suspend () -> T = block
        if (alorConfig.circuitBreakerEnabled) {
            call = circuitBreakerRegistry.circuitBreaker("alor").decorateSuspendFunction { call() }
        }
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
}
