package com.trading.bot.domain.technical

import com.trading.bot.model.entity.Candle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [IndicatorCache] — TTL/lazy-eviction и границы: пустой список, недостаток
 * данных (calculator вернул null → не кэшируем), попадание в кэш, разные бары,
 * инвалидация по тикеру и полная очистка.
 */
class IndicatorCacheTest {
    private val first = indicators()

    private fun indicators(): IndicatorCalculator.Indicators = Mockito.mock(IndicatorCalculator.Indicators::class.java)

    private fun candle(
        index: Int,
        ticker: String = "SBER",
    ): Candle =
        Candle(
            ticker = ticker,
            timeframe = "MINUTE_10",
            openPrice = BigDecimal("100"),
            highPrice = BigDecimal("101"),
            lowPrice = BigDecimal("99"),
            closePrice = BigDecimal("100.5"),
            volume = 10L,
            time = LocalDateTime.of(2026, 9, 1, 10, 0).plusMinutes(10L * index),
        )

    @AfterEach
    fun cleanup() {
        IndicatorCache.clear()
    }

    @Test
    fun `empty candle list is not calculated or cached`() {
        var calls = 0

        assertNull(
            IndicatorCache.getOrCalculate("SBER", "MINUTE_10", emptyList()) {
                calls++
                first
            },
        )
        assertEquals(0, calls)
    }

    @Test
    fun `null calculation is not cached`() {
        var calls = 0

        assertNull(
            IndicatorCache.getOrCalculate("SBER", "MINUTE_10", listOf(candle(0))) {
                calls++
                null
            },
        )

        assertNotNull(
            IndicatorCache.getOrCalculate("SBER", "MINUTE_10", listOf(candle(0))) {
                calls++
                first
            },
        )
        assertEquals(2, calls)
    }

    @Test
    fun `same ticker timeframe and last bar hit the cache`() {
        var calls = 0
        val candles = listOf(candle(0), candle(1))

        val firstResult =
            IndicatorCache.getOrCalculate("SBER", "MINUTE_10", candles) {
                calls++
                first
            }
        val secondResult =
            IndicatorCache.getOrCalculate("SBER", "MINUTE_10", candles) {
                calls++
                indicators()
            }

        assertSame(firstResult, secondResult)
        assertEquals(1, calls)
    }

    @Test
    fun `a new last bar or invalidation forces recalculation`() {
        var calls = 0
        val candles = listOf(candle(0))
        IndicatorCache.getOrCalculate("SBER", "MINUTE_10", candles) {
            calls++
            first
        }

        val nextBar = listOf(candle(0), candle(1))
        IndicatorCache.getOrCalculate("SBER", "MINUTE_10", nextBar) {
            calls++
            indicators()
        }
        assertEquals(2, calls)

        IndicatorCache.getOrCalculate("SBER", "MINUTE_10", nextBar) {
            calls++
            indicators()
        }
        assertEquals(2, calls)

        IndicatorCache.invalidate("SBER")
        IndicatorCache.getOrCalculate("SBER", "MINUTE_10", nextBar) {
            calls++
            indicators()
        }
        assertEquals(3, calls)

        // Инвалидация чужого тикера не трогает запись.
        IndicatorCache.invalidate("GAZP")
        IndicatorCache.getOrCalculate("SBER", "MINUTE_10", nextBar) {
            calls++
            indicators()
        }
        assertEquals(3, calls)

        IndicatorCache.clear()
        IndicatorCache.getOrCalculate("SBER", "MINUTE_10", nextBar) {
            calls++
            indicators()
        }
        assertEquals(4, calls)
    }
}
