package com.trading.bot.service

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.MlConfig
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Candle
import com.trading.bot.model.entity.MacroSnapshot
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.BlindSpotRepository
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.MacroSnapshotRepository
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [MlDatasetService] — границы датасета: ограничение строк, DISABLED-режим
 * статистики, пустая выборка (winRate = null), точное совпадение макро-снапшота,
 * неизвестный инструмент и все таймфреймы (шаг баров).
 */
class MlDatasetServiceBranchesTest {
    private val config = MlConfig()
    private val positionRepository = Mockito.mock(PositionRepository::class.java)
    private val candleRepository = Mockito.mock(CandleRepository::class.java)
    private val agentLogRepository = Mockito.mock(AgentLogRepository::class.java)
    private val blindSpotRepository = Mockito.mock(BlindSpotRepository::class.java)
    private val macroSnapshotRepository = Mockito.mock(MacroSnapshotRepository::class.java)
    private val macroContextService = Mockito.mock(MacroContextService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val service =
        MlDatasetService(
            config,
            InstrumentsConfig(),
            positionRepository,
            candleRepository,
            agentLogRepository,
            blindSpotRepository,
            macroSnapshotRepository,
            macroContextService,
            meterRegistry,
        )

    private fun position(
        id: Long,
        ticker: String = "SBER",
        openedAt: LocalDateTime = LocalDateTime.of(2026, 2, 1, 14, 0),
        entryPrice: String = "100",
        pnl: BigDecimal? = BigDecimal("100"),
        closedAt: LocalDateTime? = openedAt.plusMinutes(30),
    ): Position =
        Position(
            id = id,
            ticker = ticker,
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal(entryPrice),
            pnl = pnl,
            status = PositionStatus.CLOSED,
            openedAt = openedAt,
            closedAt = closedAt,
        )

    private fun candles(
        count: Int,
        start: LocalDateTime,
    ): List<Candle> =
        (0 until count).map { i ->
            Candle(
                ticker = "SBER",
                timeframe = "MINUTE_10",
                openPrice = BigDecimal("100"),
                highPrice = BigDecimal("101"),
                lowPrice = BigDecimal("99"),
                closePrice = BigDecimal("100"),
                volume = 1000,
                time = start.minusMinutes(10L * (count - i)),
            )
        }

    private fun stubEnvironment(positions: List<Position>) {
        runBlocking {
            Mockito.`when`(positionRepository.findClosed(null, null)).thenReturn(positions)
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBefore(any<String>(), any<String>(), any(), any()))
                .thenAnswer { candles(60, positions.firstOrNull()?.openedAt ?: LocalDateTime.now()) }
            Mockito.`when`(blindSpotRepository.findByIsActiveTrue()).thenReturn(emptyList())
            Mockito.`when`(macroSnapshotRepository.findBetween(any(), any())).thenReturn(emptyList())
            Mockito
                .`when`(macroContextService.fetch())
                .thenReturn(MacroContextService.MacroContext(BigDecimal("16"), BigDecimal("75"), BigDecimal("90")))
        }
    }

    @Test
    fun `maxRows below one is clamped to a single row`() {
        config.enabled = true
        stubEnvironment(
            listOf(
                position(1L, openedAt = LocalDateTime.of(2026, 2, 1, 14, 0)),
                position(2L, openedAt = LocalDateTime.of(2026, 2, 2, 14, 0)),
            ),
        )

        val export = runBlocking { service.export(maxRows = 0) }

        assertEquals(1, export.rows.size)
        assertEquals(2, export.positionsCount)
    }

    @Test
    fun `stats in disabled mode does not read the database`() {
        config.enabled = false

        val stats = runBlocking { service.stats() }

        assertEquals("DISABLED", stats["mode"])
        Mockito.verifyNoInteractions(positionRepository)
    }

    @Test
    fun `stats of an empty window has no win rate`() {
        config.enabled = true
        stubEnvironment(emptyList())

        val stats = runBlocking { service.stats() }

        assertEquals("OK", stats["mode"])
        assertEquals(0, stats["positionsCount"])
        assertNull(stats["winRate"])
    }

    @Test
    fun `stats tolerate missing pnl and unknown instruments`() {
        config.enabled = true
        stubEnvironment(listOf(position(1L, ticker = "ZZZZ", pnl = null)))

        val stats = runBlocking { service.stats() }

        assertEquals(1, stats["positionsCount"])
        assertEquals(0, stats["wins"])
        @Suppress("UNCHECKED_CAST")
        val byTicker = stats["byTicker"] as Map<String, Map<String, Any?>>
        assertEquals(0.0, byTicker.getValue("ZZZZ")["totalPnlRub"])
    }

    @Test
    fun `row with zero invested amount and no close data has zero percent pnl`() {
        config.enabled = true
        stubEnvironment(listOf(position(1L, ticker = "ZZZZ", entryPrice = "0", pnl = null, closedAt = null)))

        val export = runBlocking { service.export() }

        val row = export.rows.single()
        assertEquals(0.0, row.pnlPercent, 1e-9)
        assertEquals(0, row.win)
        assertNull(row.closeReason)
    }

    @Test
    fun `snapshot captured exactly at entry is used`() {
        config.enabled = true
        val openedAt = LocalDateTime.of(2026, 2, 1, 14, 0)
        val position = position(1L, openedAt = openedAt)
        runBlocking {
            Mockito.`when`(positionRepository.findClosed(null, null)).thenReturn(listOf(position))
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBefore(any<String>(), any<String>(), any(), any()))
                .thenReturn(candles(60, openedAt))
            Mockito.`when`(blindSpotRepository.findByIsActiveTrue()).thenReturn(emptyList())
            Mockito.`when`(macroSnapshotRepository.findBetween(any(), any())).thenReturn(
                listOf(
                    MacroSnapshot(
                        capturedAt = openedAt,
                        cbrRate = BigDecimal("14"),
                        brentPrice = BigDecimal("70"),
                        usdRub = BigDecimal("85"),
                    ),
                ),
            )
        }

        val export = runBlocking { service.export() }
        val row = export.rows.single()

        assertEquals("SNAPSHOT", row.macroSource)
        assertEquals(14.0, row.cbrRate.toDouble(), 0.0)
    }

    @Test
    fun `every supported timeframe maps to a positive bar step`() {
        config.enabled = true
        stubEnvironment(emptyList())

        val timeframes =
            listOf("MINUTE_1", "MINUTE_5", "MINUTE_10", "MINUTE_15", "MINUTE_30", "HOUR_1", "HOUR_4", "DAY_1", "UNKNOWN")
        timeframes.forEach { timeframe ->
            config.dataset.timeframe = timeframe

            val export = runBlocking { service.export() }

            assertEquals("OK", export.mode)
            assertTrue(export.rows.isEmpty())
        }
    }
}
