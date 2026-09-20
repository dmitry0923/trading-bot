package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalTime

/**
 * Тесты research-фильтров входа (docs/17 этап 4c, pt.2): session (вход только в
 * определённые фазы сессии) и pullback к EMA (не гнаться за ценой).
 */
class EntryFiltersTest {
    private fun filter(
        sessionEnabled: Boolean = false,
        startMinutes: Int = 600,
        endMinutes: Int = 1080,
        pullbackEnabled: Boolean = false,
        emaPeriod: Int = 20,
        maxDeviationPercent: Double = 1.0,
        blockOnUnknown: Boolean = false,
    ): EntryFilters =
        EntryFilters(
            sessionEnabled = sessionEnabled,
            sessionStartMinutes = startMinutes,
            sessionEndMinutes = endMinutes,
            pullbackEnabled = pullbackEnabled,
            pullbackEmaPeriod = emaPeriod,
            pullbackMaxDeviationPercent = maxDeviationPercent,
            pullbackBlockOnUnknown = blockOnUnknown,
        )

    @Test
    fun `disabled filter never blocks`() {
        val f = filter()
        assertFalse(f.blocksEntry(LocalTime.of(3, 0), BigDecimal("100"), closes(100.0, 0.0, 30)))
        assertFalse(f.blocksEntry(LocalTime.of(23, 59), BigDecimal("100"), closes(100.0, 0.0, 30)))
    }

    @Test
    fun `session filter blocks outside window`() {
        val f = filter(sessionEnabled = true, startMinutes = 600, endMinutes = 1080)
        assertFalse(f.blocksEntry(LocalTime.of(10, 0), BigDecimal("100"), closes(100.0, 0.0, 30)))
        assertFalse(f.blocksEntry(LocalTime.of(18, 0), BigDecimal("100"), closes(100.0, 0.0, 30)))
        assertTrue(f.blocksEntry(LocalTime.of(9, 59), BigDecimal("100"), closes(100.0, 0.0, 30)))
        assertTrue(f.blocksEntry(LocalTime.of(18, 1), BigDecimal("100"), closes(100.0, 0.0, 30)))
    }

    @Test
    fun `pullback filter blocks far deviation and allows near`() {
        // Ряд 100, 101, ..., 149: EMA (period 5) отстаёт от close на ~2 пункта,
        // отклонение последнего бара ~1.36% от EMA.
        val closes = closes(100.0, 1.0, 50)

        // Полоса 2.0% шире фактического отклонения 1.36% → пропуск.
        val allow =
            filter(pullbackEnabled = true, emaPeriod = 5, maxDeviationPercent = 2.0, blockOnUnknown = false)
        assertFalse(allow.blocksEntry(LocalTime.of(10, 0), BigDecimal("149"), closes))

        // Узкая полоса 0.5% — отклонение 1.36% больше → блок.
        val block = filter(pullbackEnabled = true, emaPeriod = 5, maxDeviationPercent = 0.5, blockOnUnknown = false)
        assertTrue(block.blocksEntry(LocalTime.of(10, 0), BigDecimal("149"), closes))
    }

    @Test
    fun `pullback blockOnUnknown fail-closed when too few bars`() {
        val block = filter(pullbackEnabled = true, emaPeriod = 20, blockOnUnknown = true)
        assertTrue(block.blocksEntry(LocalTime.of(10, 0), BigDecimal("100"), closes(100.0, 1.0, 5)))

        val pass = filter(pullbackEnabled = true, emaPeriod = 20, blockOnUnknown = false)
        assertFalse(pass.blocksEntry(LocalTime.of(10, 0), BigDecimal("100"), closes(100.0, 1.0, 5)))
    }

    @Test
    fun `overrides override config and from applies bt config`() {
        val config = BacktestConfig()
        config.sessionFilterEnabled = true
        config.sessionFilterStartMinutes = 100
        config.sessionFilterEndMinutes = 200
        config.pullbackFilterEnabled = false

        // Без оверрайдов — из конфига.
        val fromConfig = EntryFilters.from(config)
        assertTrue(fromConfig.blocksEntry(LocalTime.of(1, 0), BigDecimal("100"), closes(100.0, 0.0, 30)))
        assertFalse(fromConfig.blocksEntry(LocalTime.of(2, 0), BigDecimal("100"), closes(100.0, 0.0, 30)))

        // С оверрайдами — query приоритетнее.
        val fromOverrides =
            EntryFilters.from(
                config,
                EntryFilterOverrides(
                    sessionFilterEnabled = true,
                    sessionFilterStartMinutes = 700,
                    sessionFilterEndMinutes = 800,
                ),
            )
        assertTrue(fromOverrides.blocksEntry(LocalTime.of(10, 0), BigDecimal("100"), closes(100.0, 0.0, 30)))
        assertFalse(fromOverrides.blocksEntry(LocalTime.of(12, 30), BigDecimal("100"), closes(100.0, 0.0, 30)))
        assertEquals(700, fromOverrides.sessionStartMinutes)
    }

    private fun closes(
        start: Double,
        step: Double,
        count: Int,
    ): List<BigDecimal> = (0 until count).map { i -> BigDecimal.valueOf(start + step * i) }
}
