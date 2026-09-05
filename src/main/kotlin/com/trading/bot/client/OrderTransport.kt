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
 * Ордер отклонён execution interlock'ом (тикер не одобрен / fingerprint / build
 * не совпадает — [LiveFrozenStrategyResolver.isOrderAllowed] == false). Это
 * ОКОНЧАТЕЛЬНЫЙ отказ: outbox переводит строку в терминальное состояние
 * `BLOCKED`, которое worker НЕ переотправляет (в отличие от [OrderDeliveryUncertainException]).
 *
 * Отдельный тип от [OrderDeliveryUncertainException] важен: заблокированный ENTRY
 * должен быть окончательно отвергнут, а НЕ стать retryable FAILED — иначе после
 * получения LIVE-approval старый (устаревший) ордер уйдёт на биржу.
 */
class OrderInterlockDeniedException(
    message: String,
    cause: Throwable? = null,
) : OrderTransportException(message, cause)

/**
 * ОРДЕР ОКОНЧАТЕЛЬНО ОТКЛОНЁН БИРЖЕЙ (REST 4xx кроме 429 / WS-событие rejected):
 * заявка долетела до биржи, но получила определённый отказ. Это ТЕРМИНАЛЬНЫЙ исход:
 * outbox переводит строку в `REJECTED`, который worker НЕ переотправляет (в отличие
 * от [OrderDeliveryUncertainException]), — в отличие от `null`, означающего
 * «не отправлено до биржи» (rate limit / CB / spread-block) и допускающего retry.
 *
 * Маршрутизатор НЕ переключается на fallback: определённый отказ не изменится от
 * смены транспорта, повторная отправка приведёт к тому же REJECT.
 */
class OrderRejectedException(
    message: String,
    cause: Throwable? = null,
) : OrderTransportException(message, cause)

/**
 * Абстракция доставки ордеров (roadmap 13.8.2 «WebSocket-only исполнение»).
 *
 * Единый контракт четырёх исходов для размещения и отмены:
 * - возврат `orderNumber` (non-null) — биржа приняла заявку;
 * - возврат `null` — команда НЕ отправлена на биржу (rate limit / разомкнутый CB /
 *   spread-block / нет котировки) — БЕЗОПАСНО ретраить на следующем цикле outbox;
 * - исключение [OrderRejectedException] — биржа определённо отвергла заявку
 *   (4xx/WS-reject) — ТЕРМИНАЛЬНО, outbox пометит `REJECTED`, ретраить не нужно;
 * - исключение [OrderInterlockDeniedException] — execution interlock deny —
 *   ТЕРМИНАЛЬНО, outbox пометит `BLOCKED`;
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
     * @param purpose назначение ордера: [OrderPurpose.ENTRY] проверяется execution
     *   interlock'ом (тикер должен быть LIVE-approved), CLOSE/SL/TP проходят и при
     *   наличии открытой позиции по тикеру (P1-a — бот обязан мочь закрыть позицию
     *   после revoke/смены build SHA). По умолчанию [OrderPurpose.ENTRY] — самый
     *   строгий вариант для прямых вызовов без явного назначения.
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
        purpose: OrderPurpose = OrderPurpose.ENTRY,
    ): String?

    /**
     * Размещение условной заявки (stop / take-profit) через единый тип [type].
     * Назначение [purpose] используется тем же interlock'ом, что и [placeLimit].
     */
    suspend fun placeConditional(
        type: String,
        ticker: String,
        side: String,
        qty: Int,
        stopPrice: BigDecimal,
        idempotencyKey: String,
        portfolio: String,
        purpose: OrderPurpose = OrderPurpose.ENTRY,
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
