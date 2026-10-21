package com.trading.bot.infrastructure.cbr

import com.trading.bot.model.entity.CbrRateRecord
import com.trading.bot.repository.CbrRatesHistoryRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Донакачка дневного ряда ставок ЦБ РФ (P2 c1 плана 18_10_2026, research).
 *
 * Тянет ключевую ставку и RUONIA за окно `[today - days, today]` через
 * [CbrRatesProvider] и сохраняет объединённый по дате ряд в `cbr_rates_history`
 * ([CbrRatesHistoryRepository]). Ряды RUONIA и ключевой ставки независимы
 * (первый торгуется только по рабочим дням, второй меняется редко), поэтому
 * объединение идёт по календарной дате, а конфликтующие поля дозаполняются
 * в репозитории.
 *
 * В торговом контуре не участвует.
 */
@Service
class CbrRatesHistoryLoader(
    private val provider: CbrRatesProvider,
    private val repository: CbrRatesHistoryRepository,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Moscow")),
) {
    private val logger = KotlinLogging.logger {}

    /** Донакачка и запись ряда за последние [days] дней. */
    suspend fun loadAndSave(days: Int): LoadResult {
        val to = LocalDate.now(clock)
        val from = to.minusDays(days.toLong())
        val keyRates = provider.fetchKeyRates(from, to)
        val ruonia = provider.fetchRuonia(from, to)
        val merged = merge(keyRates, ruonia)
        val upserted = repository.saveAll(merged)
        meterRegistry.counter("macro.cbr.rates.loaded").increment(merged.size.toDouble())
        logger.info {
            "CbrRatesHistoryLoader: window=$from..$to keyRates=${keyRates.size} " +
                "ruonia=${ruonia.size} merged=${merged.size} upserted=$upserted"
        }
        return LoadResult(
            from = from,
            to = to,
            loaded = merged.size,
            upserted = upserted,
            keyRates = keyRates.size,
            ruonia = ruonia.size,
        )
    }

    /** Объединение двух рядов по календарной дате (ключевая ставка + RUONIA). */
    internal fun merge(
        keyRates: List<CbrRateRecord>,
        ruonia: List<CbrRateRecord>,
    ): List<CbrRateRecord> {
        val byDate = LinkedHashMap<LocalDate, CbrRateRecord>()
        keyRates.forEach { byDate[it.rateDate] = it }
        ruonia.forEach { record ->
            val existing = byDate[record.rateDate]
            byDate[record.rateDate] =
                if (existing == null) {
                    record
                } else {
                    existing.copy(ruonia = record.ruonia)
                }
        }
        return byDate.values.sortedBy { it.rateDate }
    }

    data class LoadResult(
        val from: LocalDate,
        val to: LocalDate,
        val loaded: Int,
        val upserted: Int,
        val keyRates: Int,
        val ruonia: Int,
    )
}
