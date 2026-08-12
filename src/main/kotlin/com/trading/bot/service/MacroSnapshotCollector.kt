package com.trading.bot.service

import com.trading.bot.config.MacroConfig
import com.trading.bot.model.entity.MacroSnapshot
import com.trading.bot.repository.MacroSnapshotRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Периодический сбор исторических макро-снапшотов (roadmap v2.4, раздел 13.11.2).
 *
 * Каждый [MacroConfig.snapshotIntervalMs] берёт актуальный макро-контекст
 * ([MacroContextService.fetch]) и сохраняет в `macro_snapshots`. Экспорт
 * ML-датасета использует эти снапшоты вместо «текущих» значений, чтобы
 * макро-признаки в обучающей строке соответствовали моменту входа в позицию
 * (без lookahead-утечки).
 *
 * Выключен ([MacroConfig.snapshotEnabled]=false) — коллектор не работает.
 * При любой ошибке сбора фиксируем метрику и не падаем (graceful degradation).
 */
@Service
class MacroSnapshotCollector(
    private val macroConfig: MacroConfig,
    private val macroContextService: MacroContextService,
    private val macroSnapshotRepository: MacroSnapshotRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Собственно сбор: не-suspend обёртка обязательна, @Scheduled не запускает корутины. */
    @Scheduled(fixedDelayString = "#{@macroConfig.snapshotIntervalMs}", initialDelay = 60_000L)
    fun scheduledCollect() {
        scope.launch {
            collect()
        }
    }

    suspend fun collect() {
        if (!macroConfig.snapshotEnabled) return
        try {
            val ctx = macroContextService.fetch()
            macroSnapshotRepository.save(
                MacroSnapshot(
                    capturedAt = LocalDateTime.now(),
                    cbrRate = ctx.cbrRate,
                    brentPrice = ctx.brentPrice,
                    usdRub = ctx.usdRub,
                ),
            )
            meterRegistry.counter("macro.snapshot.saved").increment()
        } catch (e: Exception) {
            logger.warn(e) { "Macro snapshot collection failed" }
            meterRegistry.counter("macro.snapshot.collect.error").increment()
        }
    }
}
