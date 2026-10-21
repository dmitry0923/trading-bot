package com.trading.bot.infrastructure.cbr

import com.trading.bot.config.MacroConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Фоновая донакачка дневного ряда ставок ЦБ РФ (P2 c1 плана 18_10_2026).
 *
 * Каждый `macro.cbr-refresh-interval-ms` (по умолчанию 6 ч) дотягивает окно
 * `macro.cbr-refresh-days` (30 дней) через [CbrRatesHistoryLoader]. Нужен потому,
 * что при `macro.live-rate-enabled=true` ключевая ставка в торговом контуре
 * берётся из БД ([com.trading.bot.service.MacroContextService]): без фонового
 * обновления ряд остаётся на уровне последнего ручного бэкфилла и устаревает
 * молча (поэтому `MacroContextService` дополнительно логирует WARN по возрасту).
 *
 * Работает только при `macro.live-rate-enabled=true`; при выключенной live-ставке
 * ряд обновляется вручную — `GET /api/v1/macro/rates-history`.
 *
 * Fail-soft: ошибка ЦБ/БД не роняет приложение и не меняет текущую ставку —
 * последнее известное значение остаётся в силе, отказ виден по метрике
 * `macro.cbr.rates.refresh{status=ERROR|EMPTY}` и WARN в логе.
 */
@Service
class CbrRatesRefreshScheduler(
    private val macroConfig: MacroConfig,
    private val loader: CbrRatesHistoryLoader,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @PreDestroy
    fun close() {
        scope.cancel()
    }

    /** Не-suspend обёртка: `@Scheduled` не запускает корутины (паттерн MacroSnapshotCollector). */
    @Scheduled(fixedDelayString = "#{@macroConfig.cbrRefreshIntervalMs}", initialDelay = 120_000L)
    fun scheduledRefresh() {
        scope.launch {
            refresh()
        }
    }

    /** Одна донакачка окна `macro.cbr-refresh-days` (fail-soft). */
    suspend fun refresh() {
        if (!macroConfig.liveRateEnabled) return
        try {
            val result = loader.loadAndSave(macroConfig.cbrRefreshDays)
            if (result.loaded == 0) {
                meterRegistry.counter("macro.cbr.rates.refresh", Tags.of("status", "EMPTY")).increment()
                logger.warn {
                    "CBR rates refresh returned no rows (window ${result.from}..${result.to}) - " +
                        "live key rate may go stale (check CBR truststore / network)"
                }
            } else {
                meterRegistry.counter("macro.cbr.rates.refresh", Tags.of("status", "OK")).increment()
                logger.info { "CBR rates refresh: loaded=${result.loaded} upserted=${result.upserted}" }
            }
        } catch (e: Exception) {
            meterRegistry.counter("macro.cbr.rates.refresh", Tags.of("status", "ERROR")).increment()
            logger.warn(e) { "CBR rates refresh failed (live key rate keeps last known value)" }
        }
    }
}
