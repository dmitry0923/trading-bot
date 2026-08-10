package com.trading.bot.domain.risk

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Строка опционной таблицы FORTS (блоки securities + marketdata) из MOEX ISS.
 *
 * Используется движком волатильности для расчёта подразумеваемой волатильности
 * фьючерса Si (Black-76) и выбора ATM-страйка ближайшего ликвидного месяца.
 */
data class OptionQuote(
    val secid: String,
    val assetCode: String,
    val kind: OptionKind,
    val strike: BigDecimal,
    val lastTradeDate: LocalDate,
    val underlyingAsset: String,
    val underlyingSettlePrice: BigDecimal?,
    val last: BigDecimal?,
    val bid: BigDecimal?,
    val openPosition: Long,
)
