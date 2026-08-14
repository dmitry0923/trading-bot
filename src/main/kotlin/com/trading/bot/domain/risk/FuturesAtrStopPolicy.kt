package com.trading.bot.domain.risk

/**
 * Чистая доменная политика дистанции стопа фьючерса в пунктах.
 *
 * Вынесена из [com.trading.bot.config.RiskConfig], чтобы domain не зависел от
 * конфигурационного слоя (архитектурное правило LayerArchitectureTest). Маппинг
 * из RiskConfig выполняется на стороне вызывающего контура (config-слой), политика
 * передаётся в [FuturesStopResolver] на каждый вызов.
 */
data class FuturesAtrStopPolicy(
    val enabled: Boolean,
    val defaultStopLossPoints: Int,
    val multiplier: Double,
    val minPoints: Int,
    val maxPoints: Int,
)
