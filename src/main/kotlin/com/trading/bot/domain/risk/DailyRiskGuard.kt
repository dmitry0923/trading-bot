package com.trading.bot.domain.risk

import com.trading.bot.model.dto.DrawdownStatus
import java.math.BigDecimal

/**
 * Единый источник дневного P&L и лимитов убытка.
 *
 * Контракт, который domain предъявляет инфраструктуре: FuturesRiskEngine зависит
 * только от этого интерфейса, а не от конкретного сервиса (инверсия зависимости
 * domain → service). Реализация — [com.trading.bot.service.DrawdownProtectionService].
 */
interface DailyRiskGuard {
    /** Достигнут ли дневной лимит убытка (кэш, без БД). */
    fun isDailyLossLimitReached(): Boolean

    /** Заблокированы ли новые входы (все tier-лимиты и Shadow/Read-only). */
    fun isEntryBlocked(): Boolean

    /** Текущий дневной P&L (кэш, без БД). */
    fun getDailyPnl(): BigDecimal

    /** Синхронный учёт P&L закрытой сделки. */
    fun updateDailyPnl(pnl: BigDecimal)

    /** Текущий статус из кэша (консервативно-нейтральный до первого цикла). */
    fun cachedOrNeutral(): DrawdownStatus
}
