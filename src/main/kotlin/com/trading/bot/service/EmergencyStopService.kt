package com.trading.bot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

/**
 * Аварийная остановка торговли (Emergency Stop).
 *
 * - Флаг активен локально ([active]) и в Redis (`bot:emergency-stop`) — наблюдаемость
 *   и контроль извне (Ops) без доступа к JVM.
 * - Причина персистится через [TradingHaltService] (reason = EMERGENCY_STOP), поэтому
 *   остановка переживает рестарт: флаг восстанавливается при старте.
 * - Новые входы блокируются единой точкой отключения [com.trading.bot.application.TradingGate]
 *   (halt EMERGENCY_STOP → TradingBlockReason.EMERGENCY_STOP).
 * - Опционально закрывает все открытые позиции рыночными ордерами ([TradingControlService.forceCloseNow]).
 * - Снятие остановки — только явный [resume] (POST /api/v1/bot/resume).
 *
 * Метрики: bot.emergency_stop{source}, bot.emergency_resume.
 */
@Service
class EmergencyStopService(
    private val redisTemplate: StringRedisTemplate,
    private val tradingHaltService: TradingHaltService,
    private val tradingControlService: TradingControlService,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    /** Redis-ключ флага (см. docs/13-roadmap.md, 5.8). */
    companion object {
        const val REDIS_KEY = "bot:emergency-stop"
        const val HALT_REASON = "EMERGENCY_STOP"
    }

    @Volatile
    private var active: Boolean = false

    @Volatile
    private var lastReason: String? = null

    /**
     * Восстановление состояния после рестарта: если в trading_halt сохранена
     * EMERGENCY_STOP — поднимаем флаг снова (локально + Redis).
     */
    @EventListener(ApplicationReadyEvent::class)
    fun init() {
        val last = tradingHaltService.last() ?: return
        if (last.reason == HALT_REASON) {
            active = true
            lastReason = last.detail.ifBlank { "restored after restart" }
            try {
                redisTemplate.opsForValue().set(REDIS_KEY, "true")
            } catch (e: Exception) {
                logger.warn(e) { "Failed to persist emergency-stop flag in Redis (in-memory only)" }
            }
            logger.warn { "Emergency stop restored after restart: reason=$lastReason" }
        }
    }

    /**
     * Активна ли аварийная остановка (быстрая синхронная проверка для циклов).
     */
    fun isActive(): Boolean = active

    /**
     * Причина последней остановки (для логов/статуса) или null.
     */
    fun lastReason(): String? = lastReason

    /**
     * Включает аварийную остановку: флаг Redis + локальный, персист причины,
     * (опционально) принудительное закрытие всех позиций.
     *
     * @param reason человекочитаемая причина (detail в trading_halt)
     * @param source инициатор: MANUAL (оператор/API) или AUTO (автоматика)
     * @param liquidate закрыть ли все открытые позиции рыночными ордерами
     * @return количество закрытых позиций (0, если liquidate == false)
     */
    suspend fun stop(
        reason: String,
        source: EmergencyStopSource = EmergencyStopSource.MANUAL,
        liquidate: Boolean = false,
    ): Int {
        active = true
        lastReason = reason
        try {
            redisTemplate.opsForValue().set(REDIS_KEY, "true")
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist emergency-stop flag in Redis (in-memory only)" }
        }
        tradingHaltService.record(HALT_REASON, source.name, reason)
        meterRegistry.counter("bot.emergency_stop", Tags.of("source", source.name)).increment()
        logger.warn { "EMERGENCY STOP activated: reason=$reason source=${source.name} liquidate=$liquidate" }
        val closed = if (liquidate) tradingControlService.forceCloseNow("EMERGENCY_STOP") else 0
        logger.warn { "EMERGENCY STOP done: positionsLiquidated=$closed" }
        return closed
    }

    /**
     * Снимает аварийную остановку: локальный флаг, Redis-ключ и запись в trading_halt.
     */
    suspend fun resume() {
        active = false
        lastReason = null
        try {
            redisTemplate.delete(REDIS_KEY)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to clear emergency-stop flag in Redis" }
        }
        tradingHaltService.clear()
        meterRegistry.counter("bot.emergency_resume").increment()
        logger.info { "Emergency stop lifted" }
    }
}

/**
 * Инициатор аварийной остановки.
 */
enum class EmergencyStopSource {
    /** Оператор через API/UI. */
    MANUAL,

    /** Автоматика (roadmap: убыток за час > 10% от max-position-rub). */
    AUTO,
}
