package com.trading.bot.service

import com.trading.bot.config.MlConfig
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.AgentLog
import com.trading.bot.model.entity.BlindSpotEntity
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import java.math.BigDecimal
import java.time.LocalDateTime

class MlDatasetServiceTest {
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
            positionRepository,
            candleRepository,
            agentLogRepository,
            blindSpotRepository,
            macroSnapshotRepository,
            macroContextService,
            meterRegistry,
        )

    @BeforeEach
    fun reset() {
        Mockito.reset(
            positionRepository,
            candleRepository,
            agentLogRepository,
            blindSpotRepository,
            macroSnapshotRepository,
            macroContextService,
        )
    }

    @Test
    fun `export builds rows from positions candles agent logs macro and blind spots`() {
        config.enabled = true
        config.dataset.maxRows = 10
        val openedAt = LocalDateTime.of(2026, 2, 1, 14, 0)
        val p1 = position(1L, "SBER", PositionDirection.LONG, openedAt, pnl = 500.0, cycleId = "c1")
        val p2 = position(2L, "GAZP", PositionDirection.SHORT, openedAt.plusDays(1), pnl = -200.0, cycleId = null)

        runBlocking {
            Mockito.`when`(positionRepository.findClosed(null, null)).thenReturn(listOf(p1, p2))
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any<String>(), any<String>(), any(), any()))
                .thenReturn(candles(60, openedAt))
                .thenReturn(candles(60, openedAt.plusDays(1)))
            Mockito
                .`when`(agentLogRepository.findStrategyDecision("c1"))
                .thenReturn(AgentLog(cycleId = "c1", agentName = "Agent-3-Strategist", action = "BUY", confidence = 0.85))
            Mockito
                .`when`(blindSpotRepository.findByIsActiveTrue())
                .thenReturn(
                    listOf(
                        BlindSpotEntity(
                            ticker = "SBER",
                            conditionPattern = "Entry at hour 14 for SBER",
                            lossRate = 0.7,
                            occurrenceCount = 3,
                            recommendation = "avoid",
                        ),
                    ),
                )
            Mockito
                .`when`(macroSnapshotRepository.findBetween(any(), any()))
                .thenReturn(
                    listOf(
                        MacroSnapshot(
                            capturedAt = openedAt.minusHours(1),
                            cbrRate = BigDecimal("16"),
                            brentPrice = BigDecimal("75"),
                            usdRub = BigDecimal("90"),
                        ),
                    ),
                )
            Mockito
                .`when`(
                    macroContextService.fetch(),
                ).thenReturn(
                    MacroContextService.MacroContext(cbrRate = BigDecimal("16"), brentPrice = BigDecimal("75"), usdRub = BigDecimal("90")),
                )
        }

        val export = runBlocking { service.export() }

        assertEquals("OK", export.mode)
        assertEquals(2, export.positionsCount)
        assertEquals(0, export.skippedInsufficientData)
        assertEquals(2, export.rows.size)

        val win = export.rows[0]
        assertEquals(1L, win.positionId)
        assertEquals(1, win.win)
        assertEquals(500.0, win.pnlRub.toDouble(), 0.0)
        assertEquals(14, win.hourOfDay)
        assertEquals("BUY", win.strategyAction)
        assertEquals(0.85, win.strategyConfidence!!, 1e-9)
        assertEquals(1, win.inBlindSpotHour)
        assertEquals(16.0, win.cbrRate.toDouble(), 0.0)
        assertEquals("SNAPSHOT", win.macroSource)
        assertTrue(win.rsi14 > 0.0)

        val loss = export.rows[1]
        assertEquals(2L, loss.positionId)
        assertEquals(0, loss.win)
        assertNull(loss.strategyAction)
        assertNull(loss.strategyConfidence)
        assertEquals(0, loss.inBlindSpotHour)
        assertEquals("SNAPSHOT", loss.macroSource)

        runBlocking {
            Mockito.verify(macroContextService, Mockito.never()).fetch()
        }
    }

    @Test
    fun `export skips positions without enough candle history`() {
        config.enabled = true
        val openedAt = LocalDateTime.of(2026, 2, 1, 14, 0)
        val p1 = position(1L, "SBER", PositionDirection.LONG, openedAt, pnl = 100.0, cycleId = null)
        val p2 = position(2L, "GAZP", PositionDirection.LONG, openedAt.plusDays(1), pnl = -50.0, cycleId = null)

        runBlocking {
            Mockito.`when`(positionRepository.findClosed(null, null)).thenReturn(listOf(p1, p2))
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any<String>(), any<String>(), any(), any()))
                .thenReturn(candles(60, openedAt))
                .thenReturn(candles(15, openedAt.plusDays(1)))
            Mockito.`when`(blindSpotRepository.findByIsActiveTrue()).thenReturn(emptyList())
            Mockito.`when`(macroSnapshotRepository.findBetween(any(), any())).thenReturn(emptyList())
            Mockito
                .`when`(
                    macroContextService.fetch(),
                ).thenReturn(MacroContextService.MacroContext(BigDecimal("16"), BigDecimal("75"), BigDecimal("90")))
        }

        val export = runBlocking { service.export() }

        assertEquals(1, export.rows.size)
        assertEquals(1, export.skippedInsufficientData)
        assertEquals(1L, export.rows[0].positionId)
        assertEquals("CURRENT", export.rows[0].macroSource)
    }

    @Test
    fun `export respects maxRows cap`() {
        config.enabled = true
        config.dataset.maxRows = 1
        val openedAt = LocalDateTime.of(2026, 2, 1, 14, 0)
        val positions = (1L..3L).map { position(it, "SBER", PositionDirection.LONG, openedAt.plusDays(it), pnl = 10.0, cycleId = null) }

        runBlocking {
            Mockito.`when`(positionRepository.findClosed(null, null)).thenReturn(positions)
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any<String>(), any<String>(), any(), any()))
                .thenReturn(candles(60, openedAt))
            Mockito.`when`(blindSpotRepository.findByIsActiveTrue()).thenReturn(emptyList())
            Mockito.`when`(macroSnapshotRepository.findBetween(any(), any())).thenReturn(emptyList())
            Mockito
                .`when`(
                    macroContextService.fetch(),
                ).thenReturn(MacroContextService.MacroContext(BigDecimal("16"), BigDecimal("75"), BigDecimal("90")))
        }

        val export = runBlocking { service.export() }

        assertEquals(1, export.rows.size)
        assertEquals(1L, export.rows[0].positionId)
    }

    @Test
    fun `export uses only snapshots captured at or before entry no lookahead`() {
        config.enabled = true
        config.dataset.maxRows = 10
        val openedAt = LocalDateTime.of(2026, 2, 1, 14, 0)
        val p1 = position(1L, "SBER", PositionDirection.LONG, openedAt, pnl = 100.0, cycleId = null)
        val p2 = position(2L, "SBER", PositionDirection.LONG, openedAt.plusHours(2), pnl = -50.0, cycleId = null)

        runBlocking {
            Mockito.`when`(positionRepository.findClosed(null, null)).thenReturn(listOf(p1, p2))
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any<String>(), any<String>(), any(), any()))
                .thenReturn(candles(60, openedAt))
                .thenReturn(candles(60, openedAt.plusHours(2)))
            Mockito.`when`(blindSpotRepository.findByIsActiveTrue()).thenReturn(emptyList())
            // Снапшоты строго ПОСЛЕ входа p1 (14:00): для p1 они недоступны (lookahead), для p2 (16:00) доступны.
            Mockito
                .`when`(macroSnapshotRepository.findBetween(any(), any()))
                .thenReturn(
                    listOf(
                        MacroSnapshot(capturedAt = openedAt.plusMinutes(30), cbrRate = BigDecimal("16"), brentPrice = BigDecimal("75"), usdRub = BigDecimal("90")),
                        MacroSnapshot(capturedAt = openedAt.plusMinutes(90), cbrRate = BigDecimal("17"), brentPrice = BigDecimal("80"), usdRub = BigDecimal("95")),
                    ),
                )
            Mockito
                .`when`(
                    macroContextService.fetch(),
                ).thenReturn(MacroContextService.MacroContext(BigDecimal("16"), BigDecimal("75"), BigDecimal("90")))
        }

        val export = runBlocking { service.export() }

        assertEquals(2, export.rows.size)
        val noLookahead = export.rows[0]
        assertEquals(1L, noLookahead.positionId)
        assertEquals("CURRENT", noLookahead.macroSource)
        assertEquals(16.0, noLookahead.cbrRate.toDouble(), 0.0)

        val withSnapshot = export.rows[1]
        assertEquals(2L, withSnapshot.positionId)
        assertEquals("SNAPSHOT", withSnapshot.macroSource)
        assertEquals(17.0, withSnapshot.cbrRate.toDouble(), 0.0)
        assertEquals(95.0, withSnapshot.usdRub.toDouble(), 0.0)
    }

    @Test
    fun `export returns DISABLED and does not touch db when ml disabled`() {
        config.enabled = false

        val export = runBlocking { service.export() }

        assertEquals("DISABLED", export.mode)
        assertTrue(export.rows.isEmpty())
        assertEquals(0, export.positionsCount)
        runBlocking {
            Mockito.verify(positionRepository, Mockito.never()).findClosed(any(), any())
            Mockito.verify(macroContextService, Mockito.never()).fetch()
        }
    }

    @Test
    fun `stats computes win rate and breakdowns`() {
        config.enabled = true
        val openedAt = LocalDateTime.of(2026, 2, 1, 14, 0)
        val positions =
            listOf(
                position(1L, "SBER", PositionDirection.LONG, openedAt, pnl = 100.0, cycleId = null),
                position(2L, "SBER", PositionDirection.LONG, openedAt.plusHours(1), pnl = -50.0, cycleId = null),
                position(3L, "GAZP", PositionDirection.SHORT, openedAt.plusDays(1), pnl = 30.0, cycleId = null),
            )
        runBlocking {
            Mockito.`when`(positionRepository.findClosed(null, null)).thenReturn(positions)
        }

        val stats = runBlocking { service.stats() }

        assertEquals("OK", stats["mode"])
        assertEquals(3, stats["positionsCount"])
        assertEquals(2.0 / 3.0, stats["winRate"] as Double, 1e-9)
        val byTicker = stats["byTicker"] as Map<*, *>
        assertEquals(2, (byTicker["SBER"] as Map<*, *>)["positions"])
    }

    private fun position(
        id: Long,
        ticker: String,
        direction: PositionDirection,
        openedAt: LocalDateTime,
        pnl: Double,
        cycleId: String?,
    ): Position =
        Position(
            id = id,
            ticker = ticker,
            direction = direction,
            quantity = 10,
            entryPrice = BigDecimal("100.0"),
            closePrice = BigDecimal("105.0"),
            pnl = BigDecimal(pnl),
            status = PositionStatus.CLOSED,
            openedAt = openedAt,
            closedAt = openedAt.plusHours(3),
            cycleId = cycleId,
        )

    private fun candles(
        count: Int,
        endAt: LocalDateTime,
    ): List<Candle> =
        (0 until count).map { i ->
            Candle(
                ticker = "SBER",
                timeframe = "MINUTE_10",
                openPrice = BigDecimal("100.0"),
                highPrice = BigDecimal("101.0"),
                lowPrice = BigDecimal("99.0"),
                closePrice = BigDecimal("100.5"),
                volume = 1000L,
                time = endAt.minusMinutes(10L * (count - 1 - i)),
            )
        }
}
