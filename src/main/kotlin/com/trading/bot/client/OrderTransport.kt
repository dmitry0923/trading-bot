package com.trading.bot.client

import java.math.BigDecimal

/**
 * Итог отмены заявки (контракт доставки, единый для всех транспортов).
 *
 * - [CONFIRMED]: биржа подтвердила отмену — заявки на бирже больше нет;
 * - [REJECTED]: определённый отказ (не найдена / уже отменена / уже исполнена) —
 *   заявка «не рабочая», повторять не нужно;
 * - [UNCERTAIN]: состояние неизвестно (сеть/таймаут/лимит/разрыв соединения) —
 *   отмена могла и не дойти, повторять на следующем цикле.
 */
enum class CancelResult {
    CONFIRMED,
    REJECTED,
    UNCERTAIN,
}

/**
 * Ошибка доставки ордера по транспорту.
 *
 * Базовый класс двух исходов, различаемых маршрутизатором [RoutedOrderTransport]
 * и outbox ([com.trading.bot.service.OrderOutboxService]):
 */
open class OrderTransportException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Транспорт недоступен ДО отправки команды (нет соединения / не LIVE / не тот
 * портфель / WS выключен). Маршрутизатор перехватывает и переключается на REST.
 * Команда гарантированно НЕ отправлена — fallback безопасен (нет double execution).
 */
class OrderTransportUnavailableException(
    message: String,
    cause: Throwable? = null,
) : OrderTransportException(message, cause)

/**
 * Команда могла дойти до биржи, но результат неизвестен (таймаут ответа, обрыв
 * соединения после отправки). НЕ перехватывается маршрутизатором — outbox помечает
 * доставку UNCERTAIN, повторная отправка идёт ТОЛЬКО после State Reconciliation
 * по idempotency key (защита от double execution).
 */
class OrderDeliveryUncertainException(
    message: String,
    cause: Throwable? = null,
) : OrderTransportException(message, cause)

/**
 * Абстракция доставки ордеров (roadmap 13.8.2 «WebSocket-only исполнение»).
 *
 * Единый контракт трёх исходов для размещения и отмены:
 * - возврат `orderNumber` (non-null) — биржа приняла заявку;
 * - возврат `null` — определённый отказ биржи (4xx / WS-reject) — ретраить не нужно;
 * - исключение [OrderDeliveryUncertainException] — результат неизвестен (UNCERTAIN),
 *   outbox пометит доставку и переотправит только после State Reconciliation;
 * - исключение [OrderTransportUnavailableException] — до отправки команды транспорт
 *   недоступен, маршрутизатор переключается на REST-fallback.
 *
 * Реализации:
 * - [WsOrderTransport] — WebSocket (primary при [AlorConfig.wsOrdersEnabled]);
 * - [RestOrderTransport] — REST (fallback).
 */
interface OrderTransport {
    /**
     * Размещение лимитной заявки с обязательным idempotency key.
     *
     * @param idempotencyKey уникальный клиентский id — все повторные доставки/ретраи
     *   используют ТОТ ЖЕ ключ (Alor дедуплицирует).
     */
    suspend fun placeLimit(
        ticker: String,
        side: String,
        qty: Int,
        price: BigDecimal,
        idempotencyKey: String,
        portfolio: String,
    ): String?

    /**
     * Размещение условной заявки (stop / take-profit) через единый тип [type].
     */
    suspend fun placeConditional(
        type: String,
        ticker: String,
        side: String,
        qty: Int,
        stopPrice: BigDecimal,
        idempotencyKey: String,
        portfolio: String,
    ): String?

    /**
     * Отмена заявки по [orderId] с idempotency key (защита от двойной отмены).
     */
    suspend fun cancel(
        orderId: String,
        idempotencyKey: String,
        portfolio: String,
    ): CancelResult
}
