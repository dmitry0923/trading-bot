package com.trading.bot.domain.risk

/**
 * Проверка торговых часов.
 *
 * Контракт, который domain предъявляет инфраструктуре: FuturesRiskEngine зависит
 * только от этого интерфейса (инверсия зависимости domain → application).
 * Реализация — [com.trading.bot.application.TradingHoursGuard].
 */
fun interface TradingCalendar {
    /** Разрешена ли торговля сейчас (по текущему времени в МСК). */
    fun isTradingAllowed(): Boolean
}
