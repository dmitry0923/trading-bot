package com.trading.bot.integration

import com.trading.bot.model.entity.MicrostructureSnapshot
import java.math.BigDecimal
import java.time.LocalDateTime

/** Фабрики снапшотов микроструктуры для интеграционных тестов репозитория. */
internal object MicrostructureSnapshotFactory {
    fun full(
        time: LocalDateTime,
        obi: BigDecimal,
        microprice: BigDecimal,
    ): MicrostructureSnapshot =
        MicrostructureSnapshot(
            ticker = "CNYRUBF",
            time = time,
            updateCount = 12L,
            price = BigDecimal("100.0"),
            bid = BigDecimal("99.9"),
            ask = BigDecimal("100.1"),
            bidSize = 7L,
            askSize = 3L,
            spreadBps = BigDecimal("19.99000"),
            obi = obi,
            microprice = microprice,
            micropriceDeviationBps = BigDecimal("9.99500"),
        )

    /** Бакет, где L1-котировки пришли без одной из сторон стакана и без размеров. */
    fun sparse(time: LocalDateTime): MicrostructureSnapshot =
        MicrostructureSnapshot(
            ticker = "CNYRUBF",
            time = time,
            updateCount = 1L,
            price = BigDecimal("100.0"),
            bid = null,
            ask = null,
            bidSize = null,
            askSize = null,
            spreadBps = null,
            obi = null,
            microprice = null,
            micropriceDeviationBps = null,
        )
}
