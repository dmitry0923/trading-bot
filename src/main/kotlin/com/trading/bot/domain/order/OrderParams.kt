package com.trading.bot.domain.order

import com.trading.bot.model.PositionDirection
import java.math.BigDecimal

/**
 * Готовые параметры заявки на вход, собранные OrderBuilder
 * из Signal + RiskVerdict + PositionSizeResult.
 *
 * Направление и объём обязательны; SL/TP/liq — опциональны (акции без
 * гарантированного стопа на бирже всё равно хранят стоп в позиции для мониторинга).
 */
data class OrderParams(
    val direction: PositionDirection,
    val quantity: Int,
    val stopLossPrice: BigDecimal? = null,
    val takeProfitPrice: BigDecimal? = null,
    val marginRequired: BigDecimal? = null,
    val liquidationPrice: BigDecimal? = null,
    val leverage: BigDecimal? = null,
    val goPerContract: BigDecimal? = null,
    val stopLossPoints: Int? = null,
    val trailingStopPrice: BigDecimal? = null,
)
