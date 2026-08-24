package com.trading.bot.model.entity

import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Позиция бота.
 *
 * Стейт-машина исполнения (защита от double execution / потеря контроля):
 * - [pendingEntry] — ордер на вход принят биржей или доставка UNCERTAIN:
 *   позиция создана, но факт исполнения ещё сверяется (см. reconciler).
 * - [pendingClose] — ордер на закрытие в полёте (доставка через outbox).
 *   Пока флаг установлен, новый ордер на закрытие НЕ создаётся;
 *   [closeOrderId] сверяется через verifyOrder, частичное исполнение
 *   уменьшает [quantity] (дозакрытие остатка следующей итерацией).
 * - [realizedPnl] — накопленный реализованный P&L по уже закрытым частям
 *   (partial fills); итоговый [pnl] при полном закрытии = realizedPnl + остаток.
 * - [cumulativeCloseFillQty] — накопительное исполнение close-ордера в лотах
 *   (Alor: filledQtyBatch). Используется для расчёта дельты: только инкремент
 *   применяется к позиции, что предотвращает повторное закрытие при дубликатах WS events.
 * - [cumulativeSlFillQty] / [cumulativeTpFillQty] — накопительное исполнение SL/TP-заявки
 *   (биржа присылает кумулятивный filledQuantity). Дельта-модель для защитных заявок:
 *   без неё второй частичный fill отчёта повторно закрыл бы остаток позиции.
 *   Сбрасывается при отмене/замене ордера (новый ордер — новый счётчик с нуля).
 * - [slOrderId] / [tpOrderId] — биржевые защитные заявки (stop/take-profit), выставленные
 *   при открытии позиции (roadmap v2.2 «Точный контроль SL/TP»); [slOrderPrice] /
 *   [tpOrderPrice] — уровень заявки для детекции перевыставления, [slPendingReplace] /
 *   [tpPendingReplace] — перевыставление в полёте (отмена старой ещё не подтверждена).
 * - [slCancelPending] / [tpCancelPending] — cancel request отправлен на биржу,
 *   но подтверждение отмены ещё не получено. Пока флаг установлен, orderId НЕ
 *   очищается и новый protection order НЕ создаётся (защита от duplicate SL/TP).
 *   Очистка только после подтверждённой отмены или обнаружения что order gone.
 * - [closeCancelPending] — close order cancel request отправлен на биржу,
 *   но подтверждение отмены ещё не получено. Пока флаг установлен, НОВЫЙ
 *   close order НЕ создаётся (защита от over-close: старый ордер ещё live,
 *   может дозаполниться). Очистка только после подтверждённой отмены старого
 *   ордера или обнаружения что order gone (reconciler → resetPendingClose).
 */
data class Position(
    val id: Long? = null,
    var ticker: String,
    var direction: PositionDirection,
    var quantity: Int,
    var entryPrice: BigDecimal,
    var currentPrice: BigDecimal? = null,
    var closePrice: BigDecimal? = null,
    var stopLoss: BigDecimal? = null,
    var takeProfit: BigDecimal? = null,
    var instrumentType: InstrumentType = InstrumentType.STOCK,
    var leverage: BigDecimal? = null,
    var goPerContract: BigDecimal? = null,
    var marginUsed: BigDecimal? = null,
    var liquidationPrice: BigDecimal? = null,
    var variationMargin: BigDecimal = BigDecimal.ZERO,
    var stopLossPoints: Int? = null,
    var trailingStopPrice: BigDecimal? = null,
    var pnl: BigDecimal? = null,
    var status: PositionStatus = PositionStatus.OPEN,
    var alorOrderId: String? = null,
    var closeOrderId: String? = null,
    var slOrderId: String? = null,
    var tpOrderId: String? = null,
    var slOrderPrice: BigDecimal? = null,
    var tpOrderPrice: BigDecimal? = null,
    var slPendingReplace: Boolean = false,
    var tpPendingReplace: Boolean = false,
    var slCancelPending: Boolean = false,
    var tpCancelPending: Boolean = false,
    var closeCancelPending: Boolean = false,
    var pendingClose: Boolean = false,
    var pendingEntry: Boolean = false,
    var realizedPnl: BigDecimal = BigDecimal.ZERO,
    var closeReason: CloseReason? = null,
    var cumulativeCloseFillQty: Int = 0,
    var cumulativeSlFillQty: Int = 0,
    var cumulativeTpFillQty: Int = 0,
    var openedAt: LocalDateTime = LocalDateTime.now(),
    var closedAt: LocalDateTime? = null,
    var cycleId: String? = null,
    var accountId: Long? = null,
)
