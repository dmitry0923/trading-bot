package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.Candle
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
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
            id = row.get("id", Long::class.javaObjectType),
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

    suspend fun existsByTickerAndTimeframeAndTime(
        ticker: String,
        timeframe: String,
        time: LocalDateTime,
    ): Boolean {
        val sql = "SELECT COUNT(*) AS c FROM candles WHERE ticker = :ticker AND timeframe = :timeframe AND time = :time"
        val count =
            databaseClient
                .sql(sql)
                .bind("ticker", ticker)
                .bind("timeframe", timeframe)
                .bind("time", time)
                .map { row, _ -> row.get("c", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        return count > 0
    }

    suspend fun save(candle: Candle) {
        val sql =
            """
            INSERT INTO candles (ticker, timeframe, open_price, high_price, low_price, close_price, volume, time)
            VALUES (:ticker, :timeframe, :openPrice, :highPrice, :lowPrice, :closePrice, :volume, :time)
            ON CONFLICT (ticker, timeframe, time) DO NOTHING
            """.trimIndent()
        databaseClient
            .sql(sql)
            .bind("ticker", candle.ticker)
            .bind("timeframe", candle.timeframe)
            .bind("openPrice", candle.openPrice)
            .bind("highPrice", candle.highPrice)
            .bind("lowPrice", candle.lowPrice)
            .bind("closePrice", candle.closePrice)
            .bind("volume", candle.volume)
            .bind("time", candle.time)
            .then()
            .awaitSingleOrNull()
    }
}
