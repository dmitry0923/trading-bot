package com.trading.bot.model.entity

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Агрегированный снимок микроструктуры стакана за один бакет (по умолчанию 1с).
 *
 * Источник — live L1 Alor. Сырые тики не сохраняются (частота котировок на
 * порядки выше торговой и Storage-неоптимальна), поэтому строка несёт
 * средние по бакету значения OBI/microprice/spread и последние наблюдаемые
 * цены/размеры.
 *
 * Nullable-поля: L1-котировка может прийти без одной из сторон стакана
 * либо без размеров (см. fail-closed в `MicrostructureRecorder`).
 */
data class MicrostructureSnapshot(
    val ticker: String,
    val time: LocalDateTime,
    val updateCount: Long,
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
