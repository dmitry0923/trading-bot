package com.trading.bot.repository

import com.trading.bot.model.Candle
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDateTime

@Repository
class CandleRepository(
    private val namedTemplate: NamedParameterJdbcTemplate,
) {
    private val rowMapper = RowMapper { rs: ResultSet, _: Int ->
        Candle(
            id = rs.getLong("id"),
            ticker = rs.getString("ticker"),
            timeframe = rs.getString("timeframe"),
            openPrice = rs.getBigDecimal("open_price"),
            highPrice = rs.getBigDecimal("high_price"),
            lowPrice = rs.getBigDecimal("low_price"),
            closePrice = rs.getBigDecimal("close_price"),
            volume = rs.getLong("volume"),
            time = rs.getTimestamp("time").toLocalDateTime()
        )
    }

    fun findByTickerAndTimeframeAndTimeBetween(ticker: String, timeframe: String, from: LocalDateTime, to: LocalDateTime): List<Candle> {
        val sql = """
            SELECT * FROM candles
            WHERE ticker = :ticker AND timeframe = :timeframe AND time BETWEEN :from AND :to
            ORDER BY time
        """.trimIndent()
        return namedTemplate.query(sql, mapOf("ticker" to ticker, "timeframe" to timeframe, "from" to from, "to" to to), rowMapper)
    }

    fun existsByTickerAndTimeframeAndTime(ticker: String, timeframe: String, time: LocalDateTime): Boolean {
        val sql = "SELECT COUNT(*) FROM candles WHERE ticker = :ticker AND timeframe = :timeframe AND time = :time"
        val count = namedTemplate.queryForObject(sql, mapOf("ticker" to ticker, "timeframe" to timeframe, "time" to time), Int::class.java)
        return (count ?: 0) > 0
    }

    fun save(candle: Candle) {
        val sql = """
            INSERT INTO candles (ticker, timeframe, open_price, high_price, low_price, close_price, volume, time)
            VALUES (:ticker, :timeframe, :openPrice, :highPrice, :lowPrice, :closePrice, :volume, :time)
            ON CONFLICT (ticker, timeframe, time) DO NOTHING
        """.trimIndent()
        namedTemplate.update(sql, mapOf(
            "ticker" to candle.ticker,
            "timeframe" to candle.timeframe,
            "openPrice" to candle.openPrice,
            "highPrice" to candle.highPrice,
            "lowPrice" to candle.lowPrice,
            "closePrice" to candle.closePrice,
            "volume" to candle.volume,
            "time" to candle.time
        ))
    }
}
