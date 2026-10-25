package com.trading.bot.service

import com.trading.bot.event.AutoStopTriggeredEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Авто-стоп (5.8, source=AUTO): слушатель превращает [AutoStopTriggeredEvent] в
 * [EmergencyStopService.stop] с источником AUTO и без ликвидации позиций.
 *
 * Матчеры Mockito в Kotlin возвращают nullable и не проходят в non-null параметры,
 * поэтому используются helper'ы: они регистрируют матчер и возвращают реальное
 * значение (паттерн из AGENTS.md).
 */
class AutoStopEventListenerTest {
    @Test
    fun `publishes emergency stop with AUTO source and no liquidation`(): Unit =
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
                eqSource(EmergencyStopSource.AUTO),
                eqFlag(false),
            )
        }

    @Test
    fun `includes account id in reason`(): Unit =
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
                reasonContaining("account=7"),
                eqSource(EmergencyStopSource.AUTO),
                eqFlag(false),
            )
        }

    @Test
    fun `reason shows hourly loss and limit`(): Unit =
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
                reasonContaining("-6000", "60 min"),
                eqSource(EmergencyStopSource.AUTO),
                eqFlag(false),
            )
        }

    /** Матчер `eq(source)` без null: возвращает реальное значение для non-null параметра. */
    private fun eqSource(value: EmergencyStopSource): EmergencyStopSource {
        Mockito.eq(value)
        return value
    }

    private fun eqFlag(value: Boolean): Boolean {
        Mockito.eq(value)
        return value
    }

    /** Матчер «reason содержит все фрагменты»; dummy-значение нужно только компилятору. */
    private fun reasonContaining(vararg parts: String): String {
        Mockito.argThat<String> { reason -> parts.all { reason.contains(it) } }
        return parts.first()
    }
}
