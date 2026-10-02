package com.trading.bot.model.entity

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Одна исполненная сделка из файла сделок MOEX Типа B
 * (`YYYYMMDD_<ticker>_fut_deal.csv`).
 *
 * Формат исходной строки:
 * `#SYMBOL,SYSTEM,MOMENT,ID_DEAL,PRICE_DEAL,VOLUME,OPEN_POS,DIRECTION`
 *
 * В отличие от `MicrostructureSnapshot` (forward L1, котировки) тик — это
 * **сделка**: цена `price` — фактическая цена исполнения, а не котировка
 * стакана, поэтому точная цена point-in-time берётся отсюда, а не из
 * `orderbook_bbo.price` (оно остаётся null для исторических данных).
 *
 * `ts` — наивное московское время из MOMENT: файлы несут вечернюю сессию
 * предыдущих календарных суток, поэтому дата торговой сессии определяется
 * именем файла, а время берётся из MOMENT (как в `Candle.time`).
 *
 * `dealId` уникален в пределах тикера — на этом построена идемпотентность
 * загрузки. Ключ уникальности в таблице — `UNIQUE (ticker, ts, deal_id)`:
 * `ts` добавлен не ради логики сделки, а потому что TimescaleDB не допускает
 * уникальный индекс гипертаблицы без колонки партиционирования.
 */
data class TradeTick(
    val ticker: String,
    val time: LocalDateTime,
    val dealId: Long,
    val price: BigDecimal,
    val volume: Long,
    val direction: String,
    val openInterest: Long?,
)
