package com.trading.bot.service

import com.trading.bot.repository.PositionRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [EntryReservationMaintenanceService] — очистка устаревших броней: ноль, положительное
 * число и падение репозитория.
 */
class EntryReservationMaintenanceServiceTest {
    private val positionRepo = mock<PositionRepository>()
    private val service = EntryReservationMaintenanceService(positionRepo)

    @Test
    fun `cleanup is silent for zero and reports a positive count`() {
        runBlocking { whenever(positionRepo.cleanupStaleEntryReservations()).thenReturn(0) }
        service.cleanupStaleReservations()

        runBlocking { whenever(positionRepo.cleanupStaleEntryReservations()).thenReturn(3) }
        service.cleanupStaleReservations()
    }

    @Test
    fun `cleanup swallows a repository failure`() {
        runBlocking {
            whenever(positionRepo.cleanupStaleEntryReservations()).thenThrow(IllegalStateException("db down"))
        }

        service.cleanupStaleReservations()
    }
}
