package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Конфигурация плеча для фьючерсов (prefix = "leverage").
 *
 * Дефолт: плечо 2x (конфигурируемое через `leverage.user-leverage`).
 * Хард-кэпы: min 1.0 (без плеча), max 3.0.
 *
 * Формула эффективного плеча:
 *   effective = clamp(userLeverage, minLeverage, maxLeverage)
 */
@Component
@ConfigurationProperties(prefix = "leverage")
class LeverageConfig {
    var enabled: Boolean = true
    var defaultLeverage: BigDecimal = BigDecimal("2.0")
    var maxLeverage: BigDecimal = BigDecimal("3.0")
    var minLeverage: BigDecimal = BigDecimal("1.0")
    var userLeverage: BigDecimal = BigDecimal("2.0")

    /** Эффективное плечо: пользовательское, ограниченное хард-кэпами. */
    fun effective(): BigDecimal =
        if (!enabled) BigDecimal.ONE
        else userLeverage.coerceIn(minLeverage, maxLeverage)

    /** Валидация значения плеча против допустимого диапазона. */
    fun isValid(leverage: BigDecimal): Boolean =
        leverage >= minLeverage && leverage <= maxLeverage
}
