package com.trading.bot.controller

import com.trading.bot.infrastructure.cbr.CbrRatesHistoryLoader
import com.trading.bot.repository.CbrRatesHistoryRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Research-доступ к дневному ряду ставок ЦБ РФ (P2 c1 плана 18_10_2026).
 *
 * - GET /api/v1/macro/rates-history — донакачка ряда (KeyRate + RUONIA) в БД;
 * - GET /api/v1/macro/rates-history/status — покрытие ряда и последняя ставка;
 * - GET /api/v1/macro/rates-history/series — выборка ряда за окно дней (JSON).
 *
 * Слой research: в торговом контуре не участвует и дефолтную ставку
 * [com.trading.bot.service.MacroContextService] не меняет
 * (`macro.live-rate-enabled=false`). Требует аутентификации (пути `/api/v1`).
 */
@RestController
@RequestMapping("/api/v1/macro")
class MacroRatesController(
    private val loader: CbrRatesHistoryLoader,
    private val repository: CbrRatesHistoryRepository,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Moscow")),
) {
    /**
     * Донакачка ряда ставок за последние `days` дней (по умолчанию 730).
     *
     * @param days глубина окна в календарных днях (1..3650)
     */
    @GetMapping("/rates-history")
    suspend fun ratesHistory(
        @RequestParam(required = false) days: Int?,
    ): Map<String, Any?> {
        val effectiveDays = (days ?: DEFAULT_BACKFILL_DAYS).coerceIn(1, MAX_DAYS)
        meterRegistry.counter("api.macro.rates_history").increment()
        val result = loader.loadAndSave(effectiveDays)
        return mapOf(
            "from" to result.from,
            "to" to result.to,
            "days" to effectiveDays,
            "loaded" to result.loaded,
            "upserted" to result.upserted,
            "keyRates" to result.keyRates,
            "ruonia" to result.ruonia,
        )
    }

    /** Покрытие ряда: число дат, границы и последняя известная ключевая ставка. */
    @GetMapping("/rates-history/status")
    suspend fun ratesHistoryStatus(): Map<String, Any?> {
        val coverage = repository.coverage()
        return mapOf(
            "count" to coverage.count,
            "from" to coverage.from,
            "to" to coverage.to,
            "latestKeyRate" to repository.findLatestKeyRate(),
        )
    }

    /** Выборка ряда за последние `days` дней (по умолчанию 365). */
    @GetMapping("/rates-history/series")
    suspend fun ratesHistorySeries(
        @RequestParam(required = false) days: Int?,
    ): Map<String, Any?> {
        val effectiveDays = (days ?: DEFAULT_SERIES_DAYS).coerceIn(1, MAX_DAYS)
        val to = LocalDate.now(clock)
        val from = to.minusDays(effectiveDays.toLong())
        val rows = repository.findBetween(from, to)
        return mapOf(
            "from" to from,
            "to" to to,
            "count" to rows.size,
            "rows" to rows.map { mapOf("date" to it.rateDate, "keyRate" to it.keyRate, "ruonia" to it.ruonia) },
        )
    }

    private companion object {
        const val DEFAULT_BACKFILL_DAYS = 730
        const val DEFAULT_SERIES_DAYS = 365
        const val MAX_DAYS = 3650
    }
}
