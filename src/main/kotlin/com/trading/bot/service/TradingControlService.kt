package com.trading.bot.service

import com.trading.bot.application.TradingGate
import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.model.CloseReason
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Управление торговлей вручную.
 *
 * - Единый флаг включения/выключения возможности торгов (tradingEnabled)
 * - Принудительное закрытие всех позиций сейчас ([forceCloseNow])
 * - Принудительное закрытие всех позиций по времени (forceCloseTime, каждую минуту
 *   сверяется с настройками; флаг forceCloseEnabled — арм).
 */
@Service
class TradingControlService(
    private val tradingGate: TradingGate,
    private val tradingBotService: TradingBotService,
    private val futuresTradingBotService: com.trading.bot.application.FuturesTradingBotService,
    private val settingsService: SettingsService,
    private val tradingHaltService: TradingHaltService,
    private val positionRepo: PositionRepository,
    private val meterRegistry: MeterRegistry,
    private val distributedLockService: DistributedLockService,
    private val distributedLockConfig: DistributedLockConfig,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @PreDestroy
    fun close() {
        scope.cancel()
    }

    private val moscowZone: ZoneId = ZoneId.of("Europe/Moscow")

    private val lastForceCloseDate = AtomicReference<LocalDate?>(null)

    /** Защита от наложения: два тика/ручной запуск не должны закрывать дважды. */
    private val forceCloseInFlight = AtomicBoolean(false)

    fun isTradingEnabled(): Boolean = tradingGate.isTradingEnabled()

    /**
     * Включает/выключает торговлю единым флагом.
     */
    suspend fun setTradingEnabled(enabled: Boolean) {
        val current = settingsService.getSettings()
        settingsService.updateSettings(current.copy(tradingEnabled = enabled))
        if (enabled) {
            // Ручное включение снимает последнюю глобальную остановку (если была).
            tradingHaltService.clear()
        } else {
            // Персистим причину ручной остановки — видна в статусе/логах даже после рестарта.
            tradingHaltService.record(
                reason = "MANUAL_DISABLE",
                source = "MANUAL",
                detail = "disabled via UI/API single flag",
            )
        }
        meterRegistry.counter("trading.control.toggle", Tags.of("enabled", enabled.toString())).increment()
        logger.info { "Trading ${if (enabled) "ENABLED" else "DISABLED"} via single flag" }
    }

    /**
     * Принудительное закрытие всех позиций сейчас.
     *
     * @param reason причина (FORCE_CLOSE / FORCE_CLOSE_SCHEDULED и т.п.)
     * @return общее количество закрытых позиций
     */
    suspend fun forceCloseNow(reason: CloseReason = CloseReason.FORCE_CLOSE): Int {
        val openCount = positionRepo.findOpenCount()
        val stocks = tradingBotService.forceCloseAll(reason)
        val futures = futuresTradingBotService.forceCloseAll(reason)
        val closed = stocks + futures
        meterRegistry.counter("trading.control.force_close", Tags.of("reason", reason.code)).increment()
        logger.info { "Force close completed: closed=$closed (open before=$openCount), reason=$reason" }
        return closed
    }

    /**
     * Плановое закрытие по времени: каждую минуту проверяем, не настало ли
     * время forceCloseTime при включённом forceCloseEnabled.
     */
    @Scheduled(fixedDelayString = "60000")
    fun scheduledForceClose() {
        val settings = settingsService.getSettings()
        if (!settings.forceCloseEnabled || settings.forceCloseTime.isBlank()) return
        val now = LocalTime.now(moscowZone)
        val target = runCatching { LocalTime.parse(settings.forceCloseTime) }.getOrNull() ?: return
        // Условие «>=» + защита от повтора: fixedDelay отсчитывается от завершения
        // предыдущего запуска, поэтому точная минута могла пропускаться.
        val today = LocalDate.now(moscowZone)
        if (now.isBefore(target)) return
        // Атомарный claim дня + in-flight: два тика 60-секундного fixedDelay (или тик и
        // ручной force-close) не должны закрывать позиции дважды (скан-8 P2).
        if (!forceCloseInFlight.compareAndSet(false, true)) return
        if (!scope.isActive) return
        logger.info { "Scheduled force close triggered at ${settings.forceCloseTime}" }
        scope.launch {
            try {
                var closed = 0
                distributedLockService.runExclusive(
                    name = "scheduler:force-close",
                    ttlSeconds = distributedLockConfig.schedulerTtlSeconds,
                ) {
                    closed = forceCloseNow(CloseReason.FORCE_CLOSE_SCHEDULED)
                }
                // День помечаем ПОСЛЕ закрытия: исключение не должно «съесть» попытку
                // до следующего рестарта (скан-9 P1).
                lastForceCloseDate.set(today)
                logger.info { "Scheduled force close done: closed=$closed" }
            } catch (e: kotlinx.coroutines.CancellationException) {
                logger.warn { "Scheduled force close cancelled during shutdown" }
                throw e
            } catch (e: Exception) {
                // Alor/БД/лок: день НЕ помечаем, следующий тик повторит попытку.
                logger.error(e) { "Scheduled force close failed - will retry on next tick" }
            } finally {
                forceCloseInFlight.set(false)
            }
        }
    }
}
