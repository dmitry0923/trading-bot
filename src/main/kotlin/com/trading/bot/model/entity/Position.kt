package com.trading.bot.model.entity

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
 * - [slOrderId] / [tpOrderId] — биржевые защитные заявки (stop/take-profit), выставленные
 *   при открытии позиции (roadmap v2.2 «Точный контроль SL/TP»); [slOrderPrice] /
 *   [tpOrderPrice] — уровень заявки для детекции перевыставления, [slPendingReplace] /
 *   [tpPendingReplace] — перевыставление в полёте (отмена старой ещё не подтверждена).
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
    var pendingClose: Boolean = false,
    var pendingEntry: Boolean = false,
    var realizedPnl: BigDecimal = BigDecimal.ZERO,
    var closeReason: String? = null,
    var openedAt: LocalDateTime = LocalDateTime.now(),
    var closedAt: LocalDateTime? = null,
    var cycleId: String? = null,
)
