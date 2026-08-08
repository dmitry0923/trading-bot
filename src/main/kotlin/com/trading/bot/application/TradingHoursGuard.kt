package com.trading.bot.application

import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.TradingCalendar
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
    private val riskConfig: RiskConfig,
) : TradingCalendar {
    private val moscowZone: ZoneId = ZoneId.of("Europe/Moscow")

    /**
     * Разрешена ли торговля сейчас (по текущему времени в МСК).
     *
     * @return true внутри торгового окна (полуоткрытый интервал)
     */
    override fun isTradingAllowed(): Boolean = isTradingAllowed(LocalTime.now(moscowZone))

    /**
     * Разрешена ли торговля в указанное время.
     *
     * @param now время для проверки
     * @return true, если now строго внутри торгового окна (границы исключены)
     */
    fun isTradingAllowed(now: LocalTime): Boolean {
        val start = LocalTime.parse(riskConfig.tradingHoursStart)
        val end = LocalTime.parse(riskConfig.tradingHoursEnd)
        if (!start.isBefore(end)) {
            return false
        }
        return now.isAfter(start) && now.isBefore(end)
    }
}
