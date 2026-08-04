package com.trading.bot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.trading.bot.model.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneOffset

/**
 * Проверка расчёта ATR/SMA на фиксированных свечах из Redis-кэша.
 *
 * Критерий приёмки: адаптивный стоп-лосс пересчитывается по ATR корректно.
 */
class CandleCacheServiceTest {

    private val redisTemplate = Mockito.mock(StringRedisTemplate::class.java)
    private val zset: ZSetOperations<String, String> = Mockito.mock(ZSetOperations::class.java) as ZSetOperations<String, String>
    private val objectMapper = ObjectMapper().apply {
        registerModule(JavaTimeModule())
        registerModule(KotlinModule.Builder().build())
    }
    private val service = CandleCacheService(redisTemplate, objectMapper)

    private fun candle(high: String, low: String, close: String, time: LocalDateTime) = Candle(
        ticker = "SBER",
        timeframe = "MINUTE_10",
        openPrice = BigDecimal(close),
        highPrice = BigDecimal(high),
        lowPrice = BigDecimal(low),
        closePrice = BigDecimal(close),
        volume = 0,
        time = time
    )

    private fun mockCandles(candles: List<Candle>) {
        Mockito.`when`(redisTemplate.opsForZSet()).thenReturn(zset)
        val json = candles.map { objectMapper.writeValueAsString(it) }
        Mockito.`when`(
            zset.reverseRange(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyLong()
            )
        ).thenAnswer { inv ->
            val end = inv.getArgument<Long>(2).toInt() + 1
            json.takeLast(end).toSet()
        }
    }

    @Test
    fun `atr uses true range against previous close`() {
        // TR1 = max(1.5, 1.5, 0) = 1.5; TR2 = max(2, 2, 0) = 2.0 → ATR(2) = 1.75
        val candles = listOf(
            candle("10", "9", "9.5", LocalDateTime.of(2026, Month.AUGUST, 3, 10, 0)),
            candle("11", "9.5", "10", LocalDateTime.of(2026, Month.AUGUST, 3, 10, 10)),
            candle("12", "10", "11", LocalDateTime.of(2026, Month.AUGUST, 3, 10, 20))
        )
        mockCandles(candles)

        val atr = service.calculateAtr("SBER", "MINUTE_10", period = 2)

        assertEquals(0, BigDecimal("1.75").compareTo(atr!!))
    }

    @Test
    fun `sma averages close prices`() {
        val candles = listOf(
            candle("10", "9", "9", LocalDateTime.of(2026, Month.AUGUST, 3, 10, 0)),
            candle("11", "9", "10", LocalDateTime.of(2026, Month.AUGUST, 3, 10, 10)),
            candle("12", "10", "11", LocalDateTime.of(2026, Month.AUGUST, 3, 10, 20))
        )
        mockCandles(candles)

        assertEquals(0, BigDecimal("10.5").compareTo(service.calculateSma("SBER", "MINUTE_10", 2)!!))
    }

    @Test
    fun `atr returns null when not enough candles`() {
        mockCandles(listOf(candle("10", "9", "9.5", LocalDateTime.of(2026, Month.AUGUST, 3, 10, 0))))

        assertNull(service.calculateAtr("SBER", "MINUTE_10", period = 14))
    }

    @Test
    fun `addCandle writes json member with epoch score and trims to limit`() {
        Mockito.`when`(redisTemplate.opsForZSet()).thenReturn(zset)
        val c = candle("10", "9", "9.5", LocalDateTime.of(2026, Month.AUGUST, 3, 10, 0))
        val key = "candles:SBER:MINUTE_10"

        service.addCandle(c)

        val score = c.time.toEpochSecond(ZoneOffset.UTC) * 1000.0
        Mockito.verify(zset).add(ArgumentMatchers.eq(key), Mockito.anyString(), ArgumentMatchers.eq(score))
        Mockito.verify(zset).removeRange(ArgumentMatchers.eq(key), ArgumentMatchers.eq(0L), ArgumentMatchers.eq(-501L))
        Mockito.verify(redisTemplate).expire(ArgumentMatchers.eq(key), ArgumentMatchers.any())
    }
}
