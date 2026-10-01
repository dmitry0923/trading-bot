package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.MicrostructureSnapshot
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Хранилище микроструктурных снапшотов стакана ([MicrostructureSnapshot]).
 *
 * Запись идёт батчами по [BATCH_SIZE] строк одним multi-row INSERT с
 * `ON CONFLICT (ticker, time) DO NOTHING`: бакет уникален по ключу, повторный
 * flush того же бакета (ретрай/перезапуск) не должен ни дублировать, ни
 * перетирать уже записанные агрегаты.
 */
@Repository
class MicrostructureSnapshotRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toSnapshot(row: Row): MicrostructureSnapshot =
        MicrostructureSnapshot(
            ticker = row.require("ticker", String::class.java),
            time = row.require("time", LocalDateTime::class.java),
            updateCount = row.require("update_count", Long::class.javaObjectType),
            price = row["price", BigDecimal::class.java],
            bid = row["bid", BigDecimal::class.java],
            ask = row["ask", BigDecimal::class.java],
            bidSize = row["bid_size", Long::class.javaObjectType],
            askSize = row["ask_size", Long::class.javaObjectType],
            spreadBps = row["spread_bps", BigDecimal::class.java],
            obi = row["obi", BigDecimal::class.java],
            microprice = row["microprice", BigDecimal::class.java],
            micropriceDeviationBps = row["microprice_deviation_bps", BigDecimal::class.java],
        )

    /**
     * Батчевая идемпотентная запись снапшотов.
     *
     * @return количество реально вставленных строк (конфликты не считаются)
     */
    suspend fun saveAll(snapshots: List<MicrostructureSnapshot>): Int {
        if (snapshots.isEmpty()) return 0
        var inserted = 0
        snapshots.chunked(BATCH_SIZE).forEach { batch ->
            val bindings = mutableListOf<Bound>()
            val values =
                batch.indices.joinToString(",") { i ->
                    val s = batch[i]
                    bindings += Bound("ticker_$i", s.ticker, String::class.java)
                    bindings += Bound("time_$i", s.time, LocalDateTime::class.java)
                    bindings += Bound("update_count_$i", s.updateCount, Long::class.javaObjectType)
                    bindings += Bound("price_$i", s.price, BigDecimal::class.java)
                    bindings += Bound("bid_$i", s.bid, BigDecimal::class.java)
                    bindings += Bound("ask_$i", s.ask, BigDecimal::class.java)
                    bindings += Bound("bid_size_$i", s.bidSize, Long::class.javaObjectType)
                    bindings += Bound("ask_size_$i", s.askSize, Long::class.javaObjectType)
                    bindings += Bound("spread_bps_$i", s.spreadBps, BigDecimal::class.java)
                    bindings += Bound("obi_$i", s.obi, BigDecimal::class.java)
                    bindings += Bound("microprice_$i", s.microprice, BigDecimal::class.java)
                    bindings += Bound("dev_bps_$i", s.micropriceDeviationBps, BigDecimal::class.java)
                    "(:ticker_$i, :time_$i, :update_count_$i, :price_$i, :bid_$i, :ask_$i, " +
                        ":bid_size_$i, :ask_size_$i, :spread_bps_$i, :obi_$i, :microprice_$i, :dev_bps_$i)"
                }
            val sql =
                """
                INSERT INTO microstructure_snapshots
                    (ticker, time, update_count, price, bid, ask, bid_size, ask_size,
                     spread_bps, obi, microprice, microprice_deviation_bps)
                VALUES $values
                ON CONFLICT (ticker, time) DO NOTHING
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
     * Снапшоты тикера на окне `[from, to)` в хронологическом порядке.
     *
     * Strictly-before семантика как у свечей: бакет, начавшийся в `to`,
     * ещё не закрыт и не должен попадать в point-in-time признаки (lookahead).
     */
    suspend fun findByTickerAndTimeBetween(
        ticker: String,
        from: LocalDateTime,
        toExclusive: LocalDateTime,
    ): List<MicrostructureSnapshot> {
        val sql =
            """
            SELECT * FROM microstructure_snapshots
            WHERE ticker = :ticker AND time >= :from AND time < :toExclusive
            ORDER BY time
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .bind("from", from)
            .bind("toExclusive", toExclusive)
            .map { row, _ -> toSnapshot(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

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
