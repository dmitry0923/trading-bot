package com.trading.bot.repository
import com.trading.bot.model.Candle
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface CandleRepository : JpaRepository<Candle, Long> {
    fun findByTickerAndTimeframeOrderByTimeDesc(ticker: String, timeframe: String, pageable: Pageable): List<Candle>
    fun findByTickerAndTimeframeAndTimeBetween(ticker: String, timeframe: String, from: LocalDateTime, to: LocalDateTime): List<Candle>
    fun existsByTickerAndTimeframeAndTime(ticker: String, timeframe: String, time: LocalDateTime): Boolean
    fun findByTickerAndTimeframeAndTimeBetween(
        ticker: String, timeframe: String, from: LocalDateTime, to: LocalDateTime
    ): List<Candle>
}
