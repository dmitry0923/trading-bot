package com.trading.bot.application

import com.trading.bot.config.RiskConfig
import org.springframework.stereotype.Component
import java.time.LocalTime
import java.time.ZoneId

/**
 * Проверка торговых часов (МСК).
 *
 * Окно по умолчанию: 10:00–18:30 (risk.trading-hours-start / risk.trading-hours-end).
 * Границы исключены: в 10:00 и 18:30 вход запрещён (полуоткрытый интервал).
 */
@Component
class TradingHoursGuard(
    private val riskConfig: RiskConfig
) {
    private val moscowZone: ZoneId = ZoneId.of("Europe/Moscow")

    fun isTradingAllowed(): Boolean = isTradingAllowed(LocalTime.now(moscowZone))

    fun isTradingAllowed(now: LocalTime): Boolean {
        val start = LocalTime.parse(riskConfig.tradingHoursStart)
        val end = LocalTime.parse(riskConfig.tradingHoursEnd)
        if (!start.isBefore(end)) {
            return false
        }
        return now.isAfter(start) && now.isBefore(end)
    }
}
