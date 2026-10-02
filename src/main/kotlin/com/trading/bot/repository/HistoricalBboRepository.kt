package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.OrderbookBbo
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Хранилище исторического BBO, восстановленного из тиков MOEX Типа B
 * ([OrderbookBbo]).
 *
 * Отличие от [MicrostructureSnapshotRepository]: бакет здесь уникален по
 * `UNIQUE (ticker, ts)` во всём историческом диапазоне, а не только внутри
 * текущего окна, поэтому повторный прогон загрузки (ретрай, докачка,
 * перезапуск) не должен ни дублировать, ни перетирать уже записанные строки —
 * отсюда `ON CONFLICT (ticker, ts) DO NOTHING` на multi-row INSERT.
 *
 * Чтение отдаёт полуоткрытый интервал `[from, to)`: бакет, начавшийся в `to`,
 * на момент `to` ещё не закрыт, и его включение дало бы lookahead в
 * point-in-time признаках (та же семантика, что у `candles`).
 */
@Repository
class HistoricalBboRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toBbo(row: Row): OrderbookBbo =
        OrderbookBbo(
            ticker = row.require("ticker", String::class.java),
            time = row.require("ts", LocalDateTime::class.java),
            quoteCount = row.require("quote_count", Long::class.javaObjectType),
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
     * Батчевая идемпотентная запись BBO-бакетов.
     *
     * @return количество реально вставленных строк (конфликты не считаются)
     */
    suspend fun saveAll(buckets: List<OrderbookBbo>): Int {
        if (buckets.isEmpty()) return 0
        var inserted = 0
        buckets.chunked(BATCH_SIZE).forEach { batch ->
            val bindings = mutableListOf<Bound>()
            val values =
                batch.indices.joinToString(",") { i ->
                    val b = batch[i]
                    bindings += Bound("ticker_$i", b.ticker, String::class.java)
                    bindings += Bound("ts_$i", b.time, LocalDateTime::class.java)
                    bindings += Bound("quote_count_$i", b.quoteCount, Long::class.javaObjectType)
                    bindings += Bound("price_$i", b.price, BigDecimal::class.java)
                    bindings += Bound("bid_$i", b.bid, BigDecimal::class.java)
                    bindings += Bound("ask_$i", b.ask, BigDecimal::class.java)
                    bindings += Bound("bid_size_$i", b.bidSize, Long::class.javaObjectType)
                    bindings += Bound("ask_size_$i", b.askSize, Long::class.javaObjectType)
                    bindings += Bound("spread_bps_$i", b.spreadBps, BigDecimal::class.java)
                    bindings += Bound("obi_$i", b.obi, BigDecimal::class.java)
                    bindings += Bound("microprice_$i", b.microprice, BigDecimal::class.java)
                    bindings += Bound("dev_bps_$i", b.micropriceDeviationBps, BigDecimal::class.java)
                    "(:ticker_$i, :ts_$i, :quote_count_$i, :price_$i, :bid_$i, :ask_$i, " +
                        ":bid_size_$i, :ask_size_$i, :spread_bps_$i, :obi_$i, :microprice_$i, :dev_bps_$i)"
                }
            val sql =
                """
                INSERT INTO orderbook_bbo
                    (ticker, ts, quote_count, price, bid, ask, bid_size, ask_size,
                     spread_bps, obi, microprice, microprice_deviation_bps)
                VALUES $values
                ON CONFLICT (ticker, ts) DO NOTHING
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
     * BBO тикера на окне `[from, to)` в хронологическом порядке.
     *
     * Полуоткрытый интервал: бакет, начавшийся в `toExclusive`, ещё не закрыт
     * и не должен попадать в point-in-time признаки (lookahead).
     */
    suspend fun findByTickerAndTimeBetween(
        ticker: String,
        from: LocalDateTime,
        toExclusive: LocalDateTime,
    ): List<OrderbookBbo> {
        val sql =
            """
            SELECT * FROM orderbook_bbo
            WHERE ticker = :ticker AND ts >= :from AND ts < :toExclusive
            ORDER BY ts
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .bind("from", from)
            .bind("toExclusive", toExclusive)
            .map { row, _ -> toBbo(row) }
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
