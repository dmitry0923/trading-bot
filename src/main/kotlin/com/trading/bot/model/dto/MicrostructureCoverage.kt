package com.trading.bot.model.dto

import java.time.LocalDateTime

/**
 * Покрытие research-таблицы микроструктуры по одному тикеру.
 *
 * Возвращается read-слоем ([com.trading.bot.service.MicrostructureResearchService])
 * для трёх источников: forward-L1 (`microstructure_snapshots`), исторического
 * BBO (`orderbook_bbo`) и исторических сделок (`ticks`). Нужен, чтобы понять,
 * какие данные вообще есть в БД под пилота, не выгружая сами строки.
 *
 * @property source имя таблицы-источника (`microstructure_snapshots` /
 *   `orderbook_bbo` / `ticks`)
 * @property ticker тикер, по которому считалось покрытие
 * @property rows число строк тикера в источнике
 * @property from минимальная метка времени (`null`, если строк нет)
 * @property to максимальная метка времени (`null`, если строк нет)
 */
data class MicrostructureCoverage(
    val source: String,
    val ticker: String,
    val rows: Long,
    val from: LocalDateTime?,
    val to: LocalDateTime?,
)
