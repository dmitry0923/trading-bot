package com.trading.bot.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Месяц партиции — деловой (МСК), а не JVM-зоны.
 *
 * `positions.opened_at` пишется Московским временем. `YearMonth.now()` брал
 * месяц JVM-зоны (в Docker — UTC), поэтому 1-го числа 00:00–03:00 МСК партиция
 * текущего месяца не создавалась: строки падали в `positions_default`,
 * а последующий `CREATE TABLE ... PARTITION OF` падал на ATTACH.
 */
class PartitionMaintenanceServiceTest {
    @Test
    fun `current month follows Moscow business day at utc month boundary`() {
        // 2026-10-31T21:30Z = 2026-11-01 00:30 MSK (UTC всё ещё октябрь).
        val instant = Instant.parse("2026-10-31T21:30:00Z")
        val service =
            PartitionMaintenanceService(
                Mockito.mock(DatabaseClient::class.java),
                Clock.fixed(instant, ZoneId.of("Europe/Moscow")),
            )

        assertEquals(YearMonth.of(2026, 11), service.currentMonth())
    }

    @Test
    fun `current month stays in Moscow month during normal business hours`() {
        // 2026-11-30T20:00Z = 2026-11-30 23:00 MSK — ещё ноябрь.
        val instant = Instant.parse("2026-11-30T20:00:00Z")
        val service =
            PartitionMaintenanceService(
                Mockito.mock(DatabaseClient::class.java),
                Clock.fixed(instant, ZoneId.of("Europe/Moscow")),
            )

        assertEquals(YearMonth.of(2026, 11), service.currentMonth())
    }
}
