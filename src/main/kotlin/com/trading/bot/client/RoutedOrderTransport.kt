package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Маршрутизатор доставки ордеров (roadmap 13.8.2 «WebSocket-only исполнение»).
 *
 * WebSocket — primary-транспорт, REST — fallback:
 * - WS используется, когда включён [AlorConfig.wsOrdersEnabled], активен LIVE-режим
 *   и портфель — дефолтный (канал подписан только на него);
 * - если WS-транспорт НЕ доступен ДО отправки команды
 *   ([OrderTransportUnavailableException]) — команда гарантированно не ушла,
 *   безопасно переключаемся на REST (нет double execution);
 * - [OrderDeliveryUncertainException] НЕ перехватывается: команда могла дойти до
 *   биржи, fallback на REST создал бы риск двойного исполнения — outbox пометит
 *   доставку UNCERTAIN и повторит только после State Reconciliation.
 *
 * Метрики: alor.ws.orders.fallback{type,reason}.
 */
@Component
class RoutedOrderTransport(
    private val alorConfig: AlorConfig,
    private val tradingConfig: TradingConfig,
    private val meterRegistry: MeterRegistry,
    private val wsOrderTransport: WsOrderTransport,
    private val restOrderTransport: RestOrderTransport,
) : OrderTransport {
    private val logger = KotlinLogging.logger {}

    private val isLive: Boolean get() = tradingConfig.mode == "LIVE"

    override suspend fun placeLimit(
        ticker: String,
        side: String,
        qty: Int,
        price: BigDecimal,
        idempotencyKey: String,
        portfolio: String,
    ): String? {
        if (canUseWs(portfolio)) {
            try {
                return wsOrderTransport.placeLimit(ticker, side, qty, price, idempotencyKey, portfolio)
            } catch (e: OrderTransportUnavailableException) {
                recordFallback("limit", e)
            }
        }
        return restOrderTransport.placeLimit(ticker, side, qty, price, idempotencyKey, portfolio)
    }

    override suspend fun placeConditional(
        type: String,
        ticker: String,
        side: String,
        qty: Int,
        stopPrice: BigDecimal,
        idempotencyKey: String,
        portfolio: String,
    ): String? {
        if (canUseWs(portfolio)) {
            try {
                return wsOrderTransport.placeConditional(type, ticker, side, qty, stopPrice, idempotencyKey, portfolio)
            } catch (e: OrderTransportUnavailableException) {
                recordFallback(type, e)
            }
        }
        return restOrderTransport.placeConditional(type, ticker, side, qty, stopPrice, idempotencyKey, portfolio)
    }

    override suspend fun cancel(
        orderId: String,
        idempotencyKey: String,
        portfolio: String,
    ): CancelResult {
        if (canUseWs(portfolio)) {
            try {
                return wsOrderTransport.cancel(orderId, idempotencyKey, portfolio)
            } catch (e: OrderTransportUnavailableException) {
                recordFallback("cancel", e)
            }
        }
        return restOrderTransport.cancel(orderId, idempotencyKey, portfolio)
    }

    private fun canUseWs(portfolio: String): Boolean = alorConfig.wsOrdersEnabled && isLive && portfolio == alorConfig.portfolio

    private fun recordFallback(
        type: String,
        e: OrderTransportUnavailableException,
    ) {
        logger.info { "WS order transport unavailable (${e.message}) — falling back to REST" }
        meterRegistry
            .counter("alor.ws.orders.fallback", Tags.of("type", type, "reason", "WS_UNAVAILABLE"))
            .increment()
    }
}
