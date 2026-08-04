package com.trading.bot.model

import java.math.BigDecimal
import java.time.LocalDateTime

data class Candle(
    val id: Long? = null,
    val ticker: String,
    val timeframe: String,
    val openPrice: BigDecimal,
    val highPrice: BigDecimal,
    val lowPrice: BigDecimal,
    val closePrice: BigDecimal,
    val volume: Long,
    val time: LocalDateTime,
)
