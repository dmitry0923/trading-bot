package com.trading.bot.domain.risk

/**
 * Фильтр аномальной волатильности рынка.
 *
 * Контракт, который domain предъявляет инфраструктуре: FuturesRiskEngine зависит
 * только от этого интерфейса (инверсия зависимости domain → service).
 * Реализация — [com.trading.bot.service.VolatilityIndexService].
 */
fun interface VolatilityFilter {
    /** Аномален ли текущий уровень индекса волатильности (торговля на паузе). */
    fun isVolatilityAnomalous(): Boolean
}
