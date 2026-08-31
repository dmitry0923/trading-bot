package com.trading.bot.service

import com.trading.bot.event.AutoStopTriggeredEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.math.RoundingMode

/**
 * Обработчик [AutoStopTriggeredEvent] — автоматический emergency stop (5.8, source=AUTO).
 *
 * Слушает событие [DrawdownProtectionService] вместо прямой зависимости, чтобы не
 * создавать цикл DrawdownProtection → EmergencyStop → TradingControl → TradingGate →
 * DrawdownProtection. [EmergencyStopService.stop] выключает входы (redis-флаг + trading_halt)
 * и НЕ ликвидирует открытые позиции (liquidate=false) — точки риска остаются под мониторингом
 * SL/TP/trailing.
 */
@Component
class AutoStopEventListener(
    private val emergencyStopService: EmergencyStopService,
) {
    private val logger = KotlinLogging.logger {}

    @EventListener
    fun onAutoStopTriggered(event: AutoStopTriggeredEvent) {
        val scope = if (event.accountId != null) " account=${event.accountId}" else ""
        val pct =
            if (event.limitRub > java.math.BigDecimal.ZERO) {
                event.hourlyLossRub
                    .multiply(java.math.BigDecimal("100"))
                    .divide(event.limitRub, 1, RoundingMode.HALF_UP)
            } else {
                java.math.BigDecimal.ZERO
            }
        val reason =
            "AUTO: hourly realized loss ${event.hourlyLossRub} ₽ ($pct% of limit) " +
                "over ${event.windowMinutes} min" + scope
        runBlocking {
            emergencyStopService.stop(reason = reason, source = EmergencyStopSource.AUTO)
        }
        logger.error { "AUTO EMERGENCY STOP triggered$scope: $reason" }
    }
}
