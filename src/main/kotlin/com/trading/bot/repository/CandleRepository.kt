package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.Candle
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

@Repository
class CandleRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toCandle(row: Row): Candle =
        Candle(
            ticker = row.require("ticker", String::class.java),
            timeframe = row.require("timeframe", String::class.java),
            openPrice = row.require("open_price", BigDecimal::class.java),
            highPrice = row.require("high_price", BigDecimal::class.java),
            lowPrice = row.require("low_price", BigDecimal::class.java),
            closePrice = row.require("close_price", BigDecimal::class.java),
            volume = row.require("volume", Long::class.javaObjectType),
            time = row.require("time", LocalDateTime::class.java),
        )

    suspend fun findByTickerAndTimeframeAndTimeBetween(
        ticker: String,
        timeframe: String,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<Candle> {
        val sql =
            """
            SELECT * FROM candles
            WHERE ticker = :ticker AND timeframe = :timeframe AND time BETWEEN :from AND :to
            ORDER BY time
            """.trimIndent()
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .bind("timeframe", timeframe)
            .bind("from", from)
            .bind("to", to)
            .map { row, _ -> toCandle(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /**
     * Массовая идемпотентная запись свечей одним multi-row INSERT
     * (ON CONFLICT DO NOTHING вместо паттерна exists + save на каждую строку).
     *
     * Батчи по [BATCH_SIZE] строк: 8 параметров на строку, чтобы не упереться
     * в лимит PostgreSQL на число параметров (65535).
     *
     * @return количество реально вставленных строк (конфликты не считаются)
     */
    suspend fun saveAll(candles: List<Candle>): Int {
        if (candles.isEmpty()) return 0
        var inserted = 0
        candles.chunked(BATCH_SIZE).forEach { batch ->
            val bindings = mutableListOf<Pair<String, Any>>()
            val values =
                batch.indices.joinToString(",") { i ->
                    val candle = batch[i]
                    bindings += "ticker_$i" to candle.ticker
                    bindings += "timeframe_$i" to candle.timeframe
                    bindings += "open_$i" to candle.openPrice
                    bindings += "high_$i" to candle.highPrice
                    bindings += "low_$i" to candle.lowPrice
                    bindings += "close_$i" to candle.closePrice
                    bindings += "volume_$i" to candle.volume
                    bindings += "time_$i" to candle.time
                    "(:ticker_$i, :timeframe_$i, :open_$i, :high_$i, :low_$i, :close_$i, :volume_$i, :time_$i)"
                }
            val sql =
                """
                INSERT INTO candles (ticker, timeframe, open_price, high_price, low_price, close_price, volume, time)
                VALUES $values
                ON CONFLICT (ticker, timeframe, time) DO NOTHING
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
