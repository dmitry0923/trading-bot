package com.trading.bot.service

import com.trading.bot.event.AutoStopTriggeredEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Авто-стоп (5.8, source=AUTO): слушатель превращает [AutoStopTriggeredEvent] в
 * [EmergencyStopService.stop] с источником AUTO и без ликвидации позиций.
 */
class AutoStopEventListenerTest {
    @Test
    fun `publishes emergency stop with AUTO source and no liquidation`() =
        runBlocking {
            val emergencyStopService = Mockito.mock(EmergencyStopService::class.java)
            val listener = AutoStopEventListener(emergencyStopService)
            val event =
                AutoStopTriggeredEvent(
                    hourlyLossRub = BigDecimal("-6000"),
                    limitRub = BigDecimal("5000"),
                    windowMinutes = 60,
                    accountId = null,
                )

            listener.onAutoStopTriggered(event)

            Mockito.verify(emergencyStopService).stop(
                Mockito.anyString(),
                Mockito.eq(EmergencyStopSource.AUTO),
                Mockito.eq(false),
            )
        }

    @Test
    fun `includes account id in reason`() =
        runBlocking {
            val emergencyStopService = Mockito.mock(EmergencyStopService::class.java)
            val listener = AutoStopEventListener(emergencyStopService)

            listener.onAutoStopTriggered(
                AutoStopTriggeredEvent(
                    hourlyLossRub = BigDecimal("-6000"),
                    limitRub = BigDecimal("5000"),
                    windowMinutes = 60,
                    accountId = 7,
                ),
            )

            Mockito.verify(emergencyStopService).stop(
                Mockito.argThat { it.contains("account=7") },
                Mockito.eq(EmergencyStopSource.AUTO),
                Mockito.eq(false),
            )
        }

    @Test
    fun `reason shows hourly loss and limit`() =
        runBlocking {
            val emergencyStopService = Mockito.mock(EmergencyStopService::class.java)
            val listener = AutoStopEventListener(emergencyStopService)

            listener.onAutoStopTriggered(
                AutoStopTriggeredEvent(
                    hourlyLossRub = BigDecimal("-6000"),
                    limitRub = BigDecimal("5000"),
                    windowMinutes = 60,
                ),
            )

            Mockito.verify(emergencyStopService).stop(
                Mockito.argThat { it.contains("-6000") && it.contains("60 min") },
                Mockito.eq(EmergencyStopSource.AUTO),
                Mockito.eq(false),
            )
        }
}
