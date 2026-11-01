package com.trading.bot.model.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [MlDatasetRow.Companion.esc] — CSV-экранирование пустых, обычных и специальных значений.
 */
class MlDatasetRowEscTest {
    private fun row(closed: Boolean): MlDatasetRow =
        MlDatasetRow(
            positionId = 1,
            ticker = "SBER",
            direction = "LONG",
            openedAt = LocalDateTime.of(2026, 1, 1, 10, 0),
            closedAt = if (closed) LocalDateTime.of(2026, 1, 1, 11, 0) else null,
            durationMinutes = 60,
            entryPrice = BigDecimal("100"),
            exitPrice = if (closed) BigDecimal("101") else null,
            pnlRub = BigDecimal("1"),
            pnlPercent = 1.0,
            closeReason = if (closed) "TP" else null,
            win = if (closed) 1 else 0,
            hourOfDay = 10,
            rsi14 = 50.0,
            atrPercent = 0.0,
            macdHistogramPercent = 0.0,
            bbPercentB = 50.0,
            emaSlopePercent = 0.0,
            volatility20Percent = 0.0,
            return3 = 0.0,
            return10 = 0.0,
            return20 = 0.0,
            cbrRate = BigDecimal("14"),
            brentPrice = BigDecimal("75"),
            usdRub = BigDecimal("90"),
            macroSource = "config",
            strategyAction = if (closed) "BUY" else null,
            strategySignalStrength = if (closed) 0.7 else null,
            inBlindSpotHour = 0,
        )

    @Test
    fun `null and empty values become an empty field`() {
        assertEquals("", MlDatasetRow.esc(null))
        assertEquals("", MlDatasetRow.esc(""))
    }

    @Test
    fun `plain values are returned unchanged`() {
        assertEquals("SBER", MlDatasetRow.esc("SBER"))
    }

    @Test
    fun `comma quote newline and carriage return force quoting`() {
        assertEquals("\"a,b\"", MlDatasetRow.esc("a,b"))
        assertEquals("\"a\"\"b\"", MlDatasetRow.esc("a\"b"))
        assertEquals("\"a\nb\"", MlDatasetRow.esc("a\nb"))
        assertEquals("\"a\rb\"", MlDatasetRow.esc("a\rb"))
    }

    @Test
    fun `csv line renders empty fields for missing close data`() {
        val line = row(closed = false).toCsvLine()

        assertEquals(29, line.split(",").size)
        assertTrue(line.contains(",,"))
    }

    @Test
    fun `csv line renders close data when present`() {
        val line = row(closed = true).toCsvLine()

        assertEquals(29, line.split(",").size)
        assertTrue(line.contains("2026-01-01T11:00"))
        assertTrue(line.contains("101"))
        assertTrue(line.contains("0.7000"))
    }

    @Test
    fun `double values are formatted with four decimals`() {
        assertEquals("1.2346", MlDatasetRow.fmt(1.23456))
    }
}
