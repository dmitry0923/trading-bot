package com.trading.bot.domain.risk

/**
 * Провайдер текущего режима волатильности рынка.
 *
 * Domain-контракт, который риск-этапы (FuturesRiskEngine, AdaptiveRiskService)
 * предъявляют инфраструктуре (инверсия зависимости domain → service).
 * Реализация — [com.trading.bot.service.MarketRegimeService].
 */
fun interface MarketRegimeProvider {
    /** Текущий режим волатильности рынка. */
    fun currentRegime(): MarketRegime
}
