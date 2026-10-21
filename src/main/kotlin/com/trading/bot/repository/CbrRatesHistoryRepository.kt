package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.CbrRateRecord
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Дневной ряд ставок Банка России (P2 c1 плана 18_10_2026, research).
 *
 * Хранит ключевую ставку и RUONIA, донакачанные
 * [com.trading.bot.infrastructure.cbr.CbrRatesHistoryLoader]. Запись идемпотентна
 * с дозаполнением: конфликт по [CbrRateRecord.rateDate] обновляет только непустые
 * поля (COALESCE), поэтому повторная донакачка дозаполняет `ruonia` за крайние
 * даты и не затирает уже известную ключевую ставку.
 *
 * В LIVE не участвует: [com.trading.bot.service.MacroContextService] читает
 * последнюю ставку только при `macro.live-rate-enabled=true`.
 */
@Repository
class CbrRatesHistoryRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toRecord(row: Row): CbrRateRecord =
        CbrRateRecord(
            rateDate = row.require("rate_date", LocalDate::class.java),
            keyRate = row.get("key_rate", BigDecimal::class.java),
            ruonia = row.get("ruonia", BigDecimal::class.java),
            source = row.require("source", String::class.java),
        )

    /**
     * Идемпотентная запись/дозаполнение ряда (multi-row upsert по rate_date).
     *
     * @return число затронутых строк (вставки + дозаполнения); при повторном
     *   прогоне без новых данных PostgreSQL всё равно считает конфликтные
     *   строки обновлёнными (DO UPDATE), поэтому это «обработано», а не «новых».
     */
    suspend fun saveAll(records: List<CbrRateRecord>): Int {
        if (records.isEmpty()) return 0
        var affected = 0
        records.chunked(BATCH_SIZE).forEach { batch ->
            val bindings = mutableListOf<Bound>()
            val values =
                batch.indices.joinToString(",") { i ->
                    val record = batch[i]
                    bindings += Bound("date_$i", record.rateDate, LocalDate::class.java)
                    bindings += Bound("key_$i", record.keyRate, BigDecimal::class.java)
                    bindings += Bound("ruonia_$i", record.ruonia, BigDecimal::class.java)
                    bindings += Bound("source_$i", record.source, String::class.java)
                    "(:date_$i, :key_$i, :ruonia_$i, :source_$i)"
                }
            val sql =
                """
                INSERT INTO cbr_rates_history (rate_date, key_rate, ruonia, source)
                VALUES $values
                ON CONFLICT (rate_date) DO UPDATE SET
                    key_rate = COALESCE(EXCLUDED.key_rate, cbr_rates_history.key_rate),
                    ruonia = COALESCE(EXCLUDED.ruonia, cbr_rates_history.ruonia),
                    source = EXCLUDED.source,
                    loaded_at = NOW()
                """.trimIndent()
            var spec = databaseClient.sql(sql)
            bindings.forEach { bound ->
                spec =
                    if (bound.value == null) {
                        spec.bindNull(bound.name, bound.type)
                    } else {
                        spec.bind(bound.name, bound.value)
                    }
            }
            affected +=
                spec
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
                    .toInt()
        }
        return affected
    }

    /** Ряд в диапазоне дат (включительно), по возрастанию. */
    suspend fun findBetween(
        from: LocalDate,
        to: LocalDate,
    ): List<CbrRateRecord> {
        val sql = "SELECT * FROM cbr_rates_history WHERE rate_date BETWEEN :from AND :to ORDER BY rate_date ASC"
        return databaseClient
            .sql(sql)
            .bind("from", from)
            .bind("to", to)
            .map { row, _ -> toRecord(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /** Последняя запись с непустой ключевой ставкой (null, если ряда ещё нет). */
    suspend fun findLatestKeyRateRecord(): CbrRateRecord? {
        val sql =
            "SELECT * FROM cbr_rates_history WHERE key_rate IS NOT NULL ORDER BY rate_date DESC LIMIT 1"
        return databaseClient
            .sql(sql)
            .map { row, _ -> toRecord(row) }
            .first()
            .awaitSingleOrNull()
    }

    /** Последняя известная ключевая ставка (null, если ряда ещё нет). */
    suspend fun findLatestKeyRate(): BigDecimal? = findLatestKeyRateRecord()?.keyRate

    /** Покрытие ряда: число дат и границы. */
    suspend fun coverage(): Coverage {
        val sql = "SELECT COUNT(*) AS cnt, MIN(rate_date) AS from_date, MAX(rate_date) AS to_date FROM cbr_rates_history"
        return databaseClient
            .sql(sql)
            .map { row, _ ->
                Coverage(
                    count = row.require("cnt", Long::class.javaObjectType),
                    from = row.get("from_date", LocalDate::class.java),
                    to = row.get("to_date", LocalDate::class.java),
                )
            }.one()
            .awaitSingle()
    }

    /** Полная очистка ряда (только для интеграционных тестов). */
    suspend fun deleteAll() {
        databaseClient.sql("DELETE FROM cbr_rates_history").then().awaitSingleOrNull()
    }

    data class Coverage(
        val count: Long,
        val from: LocalDate?,
        val to: LocalDate?,
    )

    /** Параметр multi-row INSERT с явным типом: нужен для корректного `bindNull` по R2DBC. */
    private data class Bound(
        val name: String,
        val value: Any?,
        val type: Class<*>,
    )

    private companion object {
        const val BATCH_SIZE = 500
    }
}
