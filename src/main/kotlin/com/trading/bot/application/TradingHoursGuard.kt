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
        // Fail-closed: битое значение окна (например "9:00" из ручного POST /settings)
        // раньше бросало DateTimeParseException, из-за чего /trading/status отвечал 500,
        // а гейт входа падал на каждом цикле (скан-13 P1).
        val start = parseOrNull(riskConfig.tradingHoursStart) ?: return false
        val end = parseOrNull(riskConfig.tradingHoursEnd) ?: return false
        if (!start.isBefore(end)) {
            return false
        }
        return now.isAfter(start) && now.isBefore(end)
    }

    private fun parseOrNull(value: String): LocalTime? = runCatching { LocalTime.parse(value) }.getOrNull()
}
