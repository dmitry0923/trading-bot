package com.trading.bot.model.entity

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Секундный BBO, восстановленный из тиков MOEX Типа B
 * (`YYYYMMDD_<ticker>_fut_tick.csv`) парсером [com.trading.bot.marketdata.MoexOrderLogParser].
 *
 * Отличие от [MicrostructureSnapshot]: там бакет — средние по множеству
 * котировок, полученных из live WebSocket. Здесь один MOMENT агрегируется в
 * единственный BBO по правилам биржи: bid = `max(PRICE)` среди заявок типа B,
 * ask = `min(PRICE)` среди типа S, размер — у лучшего уровня, отсутствующая
 * сторона переносится с предыдущего MOMENT (carry-forward).
 *
 * Агрессивные заявки (пересекающие уже известную противоположную сторону)
 * в стакане не остаются и в агрегацию не входят; исполнение таких заявок
 * фиксируется отдельно в [TradeTick]. Это проверено на полном торговом дне:
 * без фильтра скрещённым оказывался 40% бакетов, с фильтром — ни одного.
 *
 * `time` — начало бакета (усечение до границы ширины бакетов в зоне
 * `Europe/Moscow`), наивное локальное время без таймзоны, как в `Candle.time`.
 * Уникальный ключ `UNIQUE (ticker, ts)` даёт идемпотентность повторной загрузки.
 * Здесь `ts` входит в ключ не только ради уникальности бакета, но и потому,
 * что TimescaleDB требует колонку партиционирования в уникальном индексе.
 *
 * Nullable-поля: `price` для исторических данных всегда null (точная цена
 * сделки определяется point-in-time из `ticks` с `ts <= time`); признаки
 * вырождаются, если нет обеих сторон стакана.
 */
data class OrderbookBbo(
    val ticker: String,
    val time: LocalDateTime,
    val quoteCount: Long,
    val price: BigDecimal?,
    val bid: BigDecimal?,
    val ask: BigDecimal?,
    val bidSize: Long?,
    val askSize: Long?,
    val spreadBps: BigDecimal?,
    val obi: BigDecimal?,
    val microprice: BigDecimal?,
    val micropriceDeviationBps: BigDecimal?,
)
