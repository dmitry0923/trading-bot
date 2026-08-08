package com.trading.bot.service

import com.trading.bot.model.entity.TradingHaltRecord
import com.trading.bot.repository.TradingHaltRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Instant

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
    fun record(
        reason: String,
        source: String,
        detail: String = "",
        haltedAt: Instant = Instant.now(),
    ) {
        val record = TradingHaltRecord(reason = reason, source = source, detail = detail, haltedAt = haltedAt)
        cachedLast = record
        try {
            runBlocking { repository.save(record) }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist trading halt: $reason (in-memory only)" }
        }
        logger.warn { "Trading halt recorded: reason=$reason source=$source detail=$detail at=$haltedAt" }
    }

    /**
     * Последняя зафиксированная остановка (кэш, без БД) или null.
     */
    fun last(): TradingHaltRecord? = cachedLast

    /**
     * Сбрасывает сохранённую остановку (ручное включение торговли).
     */
    fun clear() {
        cachedLast = null
        try {
            runBlocking { repository.deleteAll() }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to clear trading halt from DB" }
        }
    }
}
