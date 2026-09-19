package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.FundingHistoryRecord
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Исторический ряд funding по датам клирингов (research, донакачка SWAPRATE MOEX).
 *
 * Хранит ФАКТИЧЕСКУЮ ставку funding за каждый торговый день (RUB/контракт/клиринг,
 * [FundingHistoryRecord.valueRubPerContract]) — источник для P&L бэктеста
 * ([com.trading.bot.backtest.BacktestEngine]) и для калибровки порогов funding-veto
 * (WFA, открытый P1). В LIVE не участвует: живой контур использует
 * [com.trading.bot.application.funding.FundingSnapshotService] (MOEX snapshot).
 */
@Repository
class FundingHistoryRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toRecord(row: Row): FundingHistoryRecord =
        FundingHistoryRecord(
            id = row.require("id", Long::class.java),
            ticker = row.require("ticker", String::class.java),
            clearingDate = row.require("clearing_date", LocalDate::class.java),
            rawValue = row.require("raw_value", BigDecimal::class.java),
            valueRubPerContract = row.require("value_rub_per_contract", BigDecimal::class.java),
            source = row.require("source", String::class.java),
        )

    /**
     * Ставки funding в диапазоне дат (включительно). Возвращает отображение
     * дата клиринга -> RUB/контракт/клиринг (пусто, если данных нет).
     */
    suspend fun findValuesBetween(
        ticker: String,
        from: LocalDate,
        to: LocalDate,
    ): Map<LocalDate, BigDecimal> {
        val sql =
            """
            SELECT clearing_date, value_rub_per_contract FROM funding_history
            WHERE ticker = :ticker AND clearing_date BETWEEN :from AND :to
            ORDER BY clearing_date
            """.trimIndent()
        val datesToValues: List<Pair<LocalDate, BigDecimal>> =
            databaseClient
                .sql(sql)
                .bind("ticker", ticker)
                .bind("from", from)
                .bind("to", to)
                .map { row, _ ->
                    row.require("clearing_date", LocalDate::class.java) to
                        row.require("value_rub_per_contract", BigDecimal::class.java)
                }.all()
                .collectList()
                .awaitSingle()
        return datesToValues.toMap()
    }

    /** Признак наличия хотя бы одной записи по тикеру (переключение на исторический ряд). */
    suspend fun hasData(ticker: String): Boolean {
        val sql = "SELECT COUNT(*) AS cnt FROM funding_history WHERE ticker = :ticker"
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .map { row, _ -> row.require("cnt", Long::class.javaObjectType) }
            .one()
            .awaitSingle() > 0
    }

    /**
     * Массовая идемпотентная запись одним multi-row INSERT
     * (ON CONFLICT DO NOTHING по (ticker, clearing_date)).
     *
     * @return количество реально вставленных строк (конфликты не считаются)
     */
    suspend fun saveAll(records: List<FundingHistoryRecord>): Int {
        if (records.isEmpty()) return 0
        var inserted = 0
        records.chunked(BATCH_SIZE).forEach { batch ->
            val bindings = mutableListOf<Pair<String, Any>>()
            val values =
                batch.indices.joinToString(",") { i ->
                    val record = batch[i]
                    bindings += "ticker_$i" to record.ticker
                    bindings += "date_$i" to record.clearingDate
                    bindings += "raw_$i" to record.rawValue
                    bindings += "value_$i" to record.valueRubPerContract
                    bindings += "source_$i" to record.source
                    "(:ticker_$i, :date_$i, :raw_$i, :value_$i, :source_$i)"
                }
            val sql =
                """
                INSERT INTO funding_history (ticker, clearing_date, raw_value, value_rub_per_contract, source)
                VALUES $values
                ON CONFLICT (ticker, clearing_date) DO NOTHING
                """.trimIndent()
            var spec = databaseClient.sql(sql)
            bindings.forEach { (name, value) -> spec = spec.bind(name, value) }
            inserted +=
                spec
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
                    .toInt()
        }
        return inserted
    }

    private companion object {
        const val BATCH_SIZE = 500
    }
}
