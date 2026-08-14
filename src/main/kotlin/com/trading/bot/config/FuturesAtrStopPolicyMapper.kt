package com.trading.bot.config

import com.trading.bot.domain.risk.FuturesAtrStopPolicy

/**
 * Маппинг риск-конфигурации (config-слой) в чистую доменную политику ATR-стопа.
 * Выполняется на стороне вызывающего контура (FuturesEntryProfile / BacktestEngine),
 * чтобы domain не зависел от RiskConfig.
 */
fun RiskConfig.toFuturesAtrStopPolicy(): FuturesAtrStopPolicy =
    FuturesAtrStopPolicy(
        enabled = futuresAtrStopEnabled,
        defaultStopLossPoints = defaultStopLossPoints,
        multiplier = futuresAtrStopMultiplier,
        minPoints = futuresAtrStopMinPoints,
        maxPoints = futuresAtrStopMaxPoints,
    )
