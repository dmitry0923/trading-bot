package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.service.LiveFrozenStrategyResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.kotlin.circuitbreaker.decorateSuspendFunction
import io.github.resilience4j.kotlin.ratelimiter.decorateSuspendFunction
import io.github.resilience4j.kotlin.retry.decorateSuspendFunction
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.ratelimiter.RequestNotPermitted
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
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * REST-доставка ордеров (roadmap 13.8.2, fallback-транспорт).
 *
 * Тела запросов вынесены сюда без изменения семантики из [AlorClient]:
 * - размещение лимитной: POST `commandapi/warptrans/TRADE/v2/client/orders/actions/limit`;
 * - размещение условной (stop/take-profit): тот же endpoint, тип в теле, `stopEndUnixTime`=0;
 * - отмена: POST `.../actions/cancel`.
 *
 * Контракт доставки (единый с [WsOrderTransport], см. [OrderTransport]):
 * - `orderNumber` (non-null) — принято; `null` — определённый отказ биржи (4xx,
 *   кроме 429) — ретраить не нужно;
 * - сетевой сбой/таймаут/5xx/429 после исчерпания Resilience4j-ретраев →
 *   [OrderDeliveryUncertainException] (outbox пометит UNCERTAIN + State Reconciliation);
 * - до отправки (rate-limit / разомкнутый circuit breaker) — `null` с метрикой
 *   `alor.order.blocked` (безопасно переотправить на следующем цикле).
 *
 * Resilience4j-обвязка (Retry + RateLimiter + CircuitBreaker, инстанс "alor")
 * и метрики `alor.order.*`, `alor.api.latency` идентичны прежним в [AlorClient].
 */
@Component
class RestOrderTransport(
    private val tradingConfig: TradingConfig,
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val tokenProvider: AlorTokenProvider,
    private val retryRegistry: RetryRegistry,
    private val rateLimiterRegistry: RateLimiterRegistry,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
    private val liveFrozenStrategyResolver: LiveFrozenStrategyResolver,
) : OrderTransport {
    private val logger = KotlinLogging.logger {}

    private val webClient = WebClient.create()

    private val isLive: Boolean get() = tradingConfig.mode == "LIVE"

/**
     * Execution interlock (P1): в LIVE-режиме реальный ордер уходит на биржу ТОЛЬКО
     * для тикера, чей runtime-фrintprиnt стратегии совпадает с одобренным
     * DeploymentGate (per-ticker approval + strategy fingerprint). Несоответствие —
     * определённый отказ (null): outbox не ретраит. В SIMULATION не влияет.
     * Независимо от [DeploymentApprovalService] — fail-closed (ошибка/неготовность
     * => deny), никакого fail-open при отсутствующем состоянии.
     *
     * Назначение ордера (P1-a): strict-denial только для ENTRY; risk-reducing закрытия
     * (close/SL/TP) разрешаются при наличии открытой позиции по тикеру даже после
     * revoke / смены build SHA, чтобы бот оставался способным выйти из позиции.
     */
    private suspend fun denyIfNotLiveApproved(
        ticker: String,
        purpose: OrderPurpose,
    ): Boolean {
        if (!isLive) return false
        if (liveFrozenStrategyResolver.isOrderAllowed(ticker, purpose)) return false
        logger.error {
            "LIVE order BLOCKED for $ticker (purpose=$purpose) — ticker not approved and no open position (execution interlock)"
        }
        meterRegistry.counter("alor.order.blocked", Tags.of("reason", "NOT_LIVE_APPROVED", "ticker", ticker)).increment()
        return true
    }

    override suspend fun placeLimit(
        ticker: String,
        side: String,
        qty: Int,
        price: BigDecimal,
        idempotencyKey: String,
        portfolio: String,
        purpose: OrderPurpose,
    ): String? {
        if (!isLive) return "sim-$ticker-$idempotencyKey"
        if (denyIfNotLiveApproved(ticker, purpose)) return null
        val start = System.currentTimeMillis()
        return try {
            val body =
                mapOf(
                    "portfolio" to portfolio,
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
                        .header("Authorization", "Bearer ${tokenProvider.actualToken()}")
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
                throw OrderDeliveryUncertainException("REST limit order delivery uncertain: ${e.message}", e)
            }
        } catch (_: RequestNotPermitted) {
            logger.warn { "Limit order $ticker NOT sent (rate limit) — will retry on next outbox cycle" }
            meterRegistry.counter("alor.order.blocked", Tags.of("reason", "RATE_LIMIT")).increment()
            null
        } catch (_: CallNotPermittedException) {
            logger.warn { "Limit order $ticker NOT sent (circuit breaker open) — will retry on next outbox cycle" }
            meterRegistry.counter("alor.order.blocked", Tags.of("reason", "CIRCUIT_OPEN")).increment()
            null
        } catch (e: Exception) {
            logger.error(e) { "placeLimitOrder failed for $ticker after retries — delivery UNCERTAIN" }
            meterRegistry.counter("alor.order.error", Tags.of("side", side, "type", "limit")).increment()
            throw OrderDeliveryUncertainException("REST limit order delivery uncertain: ${e.message}", e)
        }
    }

    override suspend fun placeConditional(
        type: String,
        ticker: String,
        side: String,
        qty: Int,
        stopPrice: BigDecimal,
        idempotencyKey: String,
        portfolio: String,
        purpose: OrderPurpose,
    ): String? {
        if (!isLive) return "sim-$type-$ticker-$idempotencyKey"
        if (denyIfNotLiveApproved(ticker, purpose)) return null
        val start = System.currentTimeMillis()
        return try {
            val body =
                mapOf(
                    "portfolio" to portfolio,
                    "ticker" to ticker,
                    "exchange" to alorConfig.exchange,
                    "side" to side,
                    "type" to type,
                    "quantity" to qty,
                    "stopPrice" to stopPrice.toPlainString(),
                    "stopEndUnixTime" to 0,
                    "id" to idempotencyKey,
                )
            val raw: String =
                resilient {
                    webClient
                        .post()
                        .uri("${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/orders/actions/limit")
                        .header("Authorization", "Bearer ${tokenProvider.actualToken()}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(objectMapper.writeValueAsString(body))
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(Duration.ofSeconds(10))
                        .awaitSingle()
                }
            recordLatency("placeConditionalOrder", start)
            val orderNumber =
                objectMapper
                    .readTree(raw)
                    .path("orderNumber")
                    .asString()
                    .ifBlank { null }
            if (orderNumber != null) {
                meterRegistry.counter("alor.order.placed", Tags.of("type", type, "status", "OK")).increment()
                logger.info { "Conditional order placed type=$type $side $qty $ticker @ $stopPrice -> $orderNumber (idem=$idempotencyKey)" }
            }
            orderNumber
        } catch (e: WebClientResponseException) {
            meterRegistry.counter("alor.order.error", Tags.of("side", side, "type", type)).increment()
            if (isDefinitiveRejection(e)) {
                logger.error(e) { "$type order REJECTED by Alor $ticker (${e.statusCode.value()}): ${e.responseBodyAsString.take(500)}" }
                null
            } else {
                logger.error(e) { "placeConditionalOrder failed for $ticker after retries (${e.statusCode.value()}) — delivery UNCERTAIN" }
                throw OrderDeliveryUncertainException("REST conditional order delivery uncertain: ${e.message}", e)
            }
        } catch (_: RequestNotPermitted) {
            logger.warn { "$type order $ticker NOT sent (rate limit) — will retry on next outbox cycle" }
            meterRegistry.counter("alor.order.blocked", Tags.of("reason", "RATE_LIMIT")).increment()
            null
        } catch (_: CallNotPermittedException) {
            logger.warn { "$type order $ticker NOT sent (circuit breaker open) — will retry on next outbox cycle" }
            meterRegistry.counter("alor.order.blocked", Tags.of("reason", "CIRCUIT_OPEN")).increment()
            null
        } catch (e: Exception) {
            logger.error(e) { "placeConditionalOrder failed for $ticker after retries — delivery UNCERTAIN" }
            meterRegistry.counter("alor.order.error", Tags.of("side", side, "type", type)).increment()
            throw OrderDeliveryUncertainException("REST conditional order delivery uncertain: ${e.message}", e)
        }
    }

    override suspend fun cancel(
        orderId: String,
        idempotencyKey: String,
        portfolio: String,
    ): CancelResult {
        if (!isLive) return CancelResult.CONFIRMED
        return try {
            val body =
                mapOf(
                    "portfolio" to portfolio,
                    "exchange" to alorConfig.exchange,
                    "orderId" to orderId,
                    "id" to idempotencyKey,
                )
            resilient {
                webClient
                    .post()
                    .uri("${alorConfig.apiUrl}/commandapi/warptrans/TRADE/v2/client/orders/actions/cancel")
                    .header("Authorization", "Bearer ${tokenProvider.actualToken()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .awaitSingle()
            }
            meterRegistry.counter("alor.order.cancelled", Tags.of("type", "limit")).increment()
            logger.info { "Order cancelled $orderId (idem=$idempotencyKey)" }
            CancelResult.CONFIRMED
        } catch (e: WebClientResponseException) {
            meterRegistry.counter("alor.order.error", Tags.of("type", "cancel")).increment()
            if (isDefinitiveRejection(e)) {
                logger.warn(e) { "Order cancel REJECTED $orderId (${e.statusCode.value()}): ${e.responseBodyAsString.take(500)}" }
                CancelResult.REJECTED
            } else {
                logger.error(e) { "Order cancel failed for $orderId after retries (${e.statusCode.value()}) — UNCERTAIN" }
                throw OrderDeliveryUncertainException("REST cancel delivery uncertain: ${e.message}", e)
            }
        } catch (_: RequestNotPermitted) {
            logger.warn { "Order cancel NOT sent for $orderId (rate limit) — will retry on next outbox cycle" }
            meterRegistry.counter("alor.order.error", Tags.of("type", "cancel")).increment()
            CancelResult.UNCERTAIN
        } catch (_: CallNotPermittedException) {
            logger.warn { "Order cancel NOT sent for $orderId (circuit breaker open) — will retry on next outbox cycle" }
            meterRegistry.counter("alor.order.error", Tags.of("type", "cancel")).increment()
            CancelResult.UNCERTAIN
        } catch (e: Exception) {
            logger.error(e) { "Order cancel failed for $orderId after retries — UNCERTAIN" }
            meterRegistry.counter("alor.order.error", Tags.of("type", "cancel")).increment()
            throw OrderDeliveryUncertainException("REST cancel delivery uncertain: ${e.message}", e)
        }
    }

    /**
     * Оборачивает HTTP-вызов в Resilience4j: CircuitBreaker (самый внутренний) →
     * RateLimiter → Retry с exponential backoff + jitter (снаружи).
     * Конфиг — application.yml (resilience4j.*.instances.alor).
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

    private fun isDefinitiveRejection(t: Throwable): Boolean =
        t is WebClientResponseException &&
            t.statusCode.value() in 400..499 &&
            t.statusCode.value() != 429

    private fun recordLatency(
        operation: String,
        startMs: Long,
    ) {
        meterRegistry
            .timer("alor.api.latency", Tags.of("operation", operation))
            .record(System.currentTimeMillis() - startMs, TimeUnit.MILLISECONDS)
    }
}
