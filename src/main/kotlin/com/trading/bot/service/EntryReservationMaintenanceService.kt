package com.trading.bot.service

import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Обслуживание таблицы entry_reservations (EXEC-002, MR-B).
 *
 * Резервация слота входа создаётся до отправки entry-ордера и снимается при
 * закрытии позиции / отказе биржи / abandonEntry. Если бот упал в окне «резервация →
 * создание позиции», резервация остаётся «осиротевшей» и блокирует повторный вход
 * по тикеру — периодическая чистка удаляет такие записи (старше [maxAgeMs],
 * без открытой позиции).
 */
@Component
class EntryReservationMaintenanceService(
    private val positionRepo: PositionRepository,
) {
    private val logger = KotlinLogging.logger {}

    @Scheduled(fixedDelay = 10L * 60 * 1000, initialDelay = 60_000L)
    fun cleanupStaleReservations() {
        try {
            val removed = runBlocking { positionRepo.cleanupStaleEntryReservations() }
            if (removed > 0) {
                logger.info { "Cleaned $removed stale entry reservations" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Stale entry reservations cleanup failed" }
        }
    }
}
