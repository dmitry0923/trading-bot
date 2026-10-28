package com.trading.bot.service

import com.trading.bot.model.entity.TradingHaltRecord
import com.trading.bot.repository.TradingHaltRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Состояние последней глобальной остановки торговли.
 *
 * - Кэшируется в памяти ([cachedLast]) — горячие проверки [com.trading.bot.application.TradingGate]
 *   не ходят в БД.
 * - Восстанавливается из trading_halt на старте ([ApplicationReadyEvent]) — причина
 *   останова переживает рестарт.
 * - Персистится синхронно ([record]) через блокирующий вызов suspend-репозитория,
 *   как это уже делается в [SettingsService].
 */
@Service
class TradingHaltService(
    private val repository: TradingHaltRepository,
) {
    companion object {
        /** Причина halt от дневного лимита убытка (сбрасывается со сменой торгового дня). */
        const val DAILY_LOSS_LIMIT_REASON: String = "DAILY_LOSS_LIMIT"

        /** Причина halt от emergency stop (снимается только `resume()`). */
        const val EMERGENCY_STOP_REASON: String = "EMERGENCY_STOP"

        private val BUSINESS_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }

    private val logger = KotlinLogging.logger {}

    @Volatile
    private var cachedLast: TradingHaltRecord? = null

    @EventListener(ApplicationReadyEvent::class)
    fun init() {
        try {
            cachedLast = runBlocking { repository.latest() }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load last trading halt from DB" }
        }
        cachedLast?.let { logger.info { "Restored last trading halt: ${it.reason} at ${it.haltedAt}" } }
    }

    /**
     * Фиксирует новую остановку торговли (кэш + БД).
     */
    suspend fun record(
        reason: String,
        source: String,
        detail: String = "",
        haltedAt: Instant = Instant.now(),
    ) {
        val record = TradingHaltRecord(reason = reason, source = source, detail = detail, haltedAt = haltedAt)
        cachedLast = record
        try {
            repository.save(record)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist trading halt: $reason (in-memory only)" }
        }
        logger.warn { "Trading halt recorded: reason=$reason source=$source detail=$detail at=$haltedAt" }
    }

    /**
     * Последняя зафиксированная остановка (кэш, без БД) или null.
     *
     * `DAILY_LOSS_LIMIT` действует только в свой торговый день: иначе halt от дневного
     * лимита блокировал бы входы навсегда (включая рестарты), хотя риск-слой на новом
     * дне уже сброшен (скан-15 P1).
     */
    fun last(): TradingHaltRecord? {
        val record = cachedLast ?: return null
        if (record.reason == DAILY_LOSS_LIMIT_REASON) {
            val haltedDay = LocalDateTime.ofInstant(record.haltedAt, BUSINESS_ZONE).toLocalDate()
            val today = LocalDate.now(BUSINESS_ZONE)
            if (haltedDay.isBefore(today)) {
                logger.info { "Stale $DAILY_LOSS_LIMIT_REASON halt from $haltedDay ignored (today=$today)" }
                return null
            }
        }
        return record
    }

    /**
     * Сбрасывает сохранённую остановку (ручное включение торговли).
     *
     * @param keepReasons причины, которые разблокировать нельзя (например EMERGENCY_STOP —
     *   снимается только `EmergencyStopService.resume()`).
     */
    suspend fun clear(keepReasons: Set<String> = emptySet()) {
        val current = cachedLast
        if (current != null && current.reason in keepReasons) {
            logger.info { "Trading halt ${current.reason} is not clearable via this path — kept" }
            return
        }
        cachedLast = null
        try {
            repository.deleteAll()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to clear trading halt from DB" }
        }
    }
}
