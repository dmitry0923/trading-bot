package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.dto.MicrostructureCoverage
import com.trading.bot.model.entity.TradeTick
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Хранилище исполненных сделок MOEX Типа B ([TradeTick]).
 *
 * Повторный прогон загрузки (ретрай, докачка, перезапуск) идемпотентен через
 * `ON CONFLICT (ticker, ts, deal_id) DO NOTHING` — уже записанная сделка не
 * дублируется и не перетирается. Ключ включает `ts` не из логики сделки
 * (`dealId` уникален в пределах тикера), а потому что TimescaleDB не допускает
 * уникальный индекс гипертаблицы без колонки партиционирования: с
 * `UNIQUE (ticker, deal_id)` вызов `create_hypertable` падает с
 * "cannot create a unique index without the column ts", и вся миграция
 * пропускается целиком.
 */
@Repository
class TradeTickRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toTick(row: Row): TradeTick =
        TradeTick(
            ticker = row.require("ticker", String::class.java),
            time = row.require("ts", LocalDateTime::class.java),
            dealId = row.require("deal_id", Long::class.javaObjectType),
            price = row.require("price", BigDecimal::class.java),
            volume = row.require("volume", Long::class.javaObjectType),
            direction = row.require("direction", String::class.java),
            openInterest = row["open_interest", Long::class.javaObjectType],
        )

    /**
     * Батчевая идемпотентная запись сделок.
     *
     * @return количество реально вставленных строк (конфликты не считаются)
     */
    suspend fun saveAll(ticks: List<TradeTick>): Int {
        if (ticks.isEmpty()) return 0
        var inserted = 0
        ticks.chunked(BATCH_SIZE).forEach { batch ->
            val bindings = mutableListOf<Bound>()
            val values =
                batch.indices.joinToString(",") { i ->
                    val t = batch[i]
                    bindings += Bound("ticker_$i", t.ticker, String::class.java)
                    bindings += Bound("ts_$i", t.time, LocalDateTime::class.java)
                    bindings += Bound("deal_id_$i", t.dealId, Long::class.javaObjectType)
                    bindings += Bound("price_$i", t.price, BigDecimal::class.java)
                    bindings += Bound("volume_$i", t.volume, Long::class.javaObjectType)
                    bindings += Bound("direction_$i", t.direction, String::class.java)
                    bindings += Bound("open_interest_$i", t.openInterest, Long::class.javaObjectType)
                    "(:ticker_$i, :ts_$i, :deal_id_$i, :price_$i, :volume_$i, " +
                        ":direction_$i, :open_interest_$i)"
                }
            val sql =
                """
                INSERT INTO ticks (ticker, ts, deal_id, price, volume, direction, open_interest)
                VALUES $values
                ON CONFLICT (ticker, ts, deal_id) DO NOTHING
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
            inserted +=
                spec
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
                    .toInt()
        }
        return inserted
    }

    /**
     * Сделки тикера на окне `[from, to)` в хронологическом порядке.
     *
     * Полуоткрытый интервал: сделка ровно в `toExclusive` не возвращается, что
     * соответствует point-in-time выборке «строго до `to`».
     *
     * @param limit предохранитель от неограниченной выгрузки; по умолчанию
     *   [Int.MAX_VALUE] (без ограничения, обратная совместимость)
     */
    suspend fun findByTickerAndTimeBetween(
        ticker: String,
        from: LocalDateTime,
        toExclusive: LocalDateTime,
        limit: Int = Int.MAX_VALUE,
    ): List<TradeTick> {
        val sql =
            """
            SELECT * FROM ticks
            WHERE ticker = :ticker AND ts >= :from AND ts < :toExclusive
            ORDER BY ts
            LIMIT :limit
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .bind("from", from)
            .bind("toExclusive", toExclusive)
            .bind("limit", limit)
            .map { row, _ -> toTick(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /** Покрытие таблицы по тикеру: число строк и временные границы (для research-выгрузки). */
    suspend fun coverage(ticker: String): MicrostructureCoverage {
        val sql =
            """
            SELECT count(*) AS cnt, min(ts) AS lo, max(ts) AS hi
            FROM ticks
            WHERE ticker = :ticker
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .map { row, _ -> toCoverage(ticker, row) }
            .one()
            .awaitSingle()
    }

    private fun toCoverage(
        ticker: String,
        row: Row,
    ): MicrostructureCoverage =
        MicrostructureCoverage(
            source = SOURCE,
            ticker = ticker,
            rows = row.require("cnt", Long::class.javaObjectType),
            from = row["lo", LocalDateTime::class.java],
            to = row["hi", LocalDateTime::class.java],
        )

    /** Параметр multi-row INSERT с явным типом: нужен для корректного `bindNull` по R2DBC. */
    private data class Bound(
        val name: String,
        val value: Any?,
        val type: Class<*>,
    )

    private companion object {
        const val BATCH_SIZE = 500

        /** Имя таблицы-источника в [MicrostructureCoverage.source]. */
        const val SOURCE = "ticks"
    }
}
