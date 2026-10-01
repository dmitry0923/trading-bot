package com.trading.bot.service

import org.springframework.stereotype.Component

/**
 * Источник времени для бакетизации микроструктуры.
 *
 * Выделен в интерфейс ради тестируемости: бакеты выравниваются по миллисекундам,
 * и детерминированно проверить переходы «бакет закрыт → следующий открыт» и
 * отбрасывание устаревших котировок можно только подменяя часы. В проде
 * используются системные часы — как и во всём остальном торговом коде
 * (см. `TradingHoursGuard`, `DrawdownProtectionService`).
 */
fun interface MicrostructureTimeSource {
    fun nowMillis(): Long
}

@Component
class SystemMicrostructureTimeSource : MicrostructureTimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
