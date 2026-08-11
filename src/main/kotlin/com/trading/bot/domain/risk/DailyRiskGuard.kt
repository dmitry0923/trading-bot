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
    /** Достигнут ли дневной лимит убытка (кэш, без БД). accountId = null → legacy global. */
    fun isDailyLossLimitReached(accountId: Long? = null): Boolean

    /** Заблокированы ли новые входы (все tier-лимиты и Shadow/Read-only). */
    fun isEntryBlocked(accountId: Long? = null): Boolean

    /** Текущий дневной P&L (кэш, без БД). accountId = null → legacy global. */
    fun getDailyPnl(accountId: Long? = null): BigDecimal

    /** Синхронный учёт P&L закрытой сделки. accountId = null → legacy global. */
    fun updateDailyPnl(
        pnl: BigDecimal,
        accountId: Long? = null,
    )

    /** Текущий статус из кэша (консервативно-нейтральный до первого цикла). */
    fun cachedOrNeutral(accountId: Long? = null): DrawdownStatus
}
