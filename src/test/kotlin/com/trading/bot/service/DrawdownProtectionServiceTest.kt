package com.trading.bot.service

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.DailyRiskSnapshot
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.DailyRiskSnapshotRepository
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Multi-Tier Drawdown Protection — единый источник дневного P&L:
 * лимиты строго в % от AUM (без рублёвого «пола»), скользящие просадки 7д/30д,
 * Shadow/Read-only режим по серии убытков, unrealized P&L открытых позиций
 * включён в AUM и дневной лимит.
 */
class DrawdownProtectionServiceTest {
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val snapshotRepo = Mockito.mock(DailyRiskSnapshotRepository::class.java)
    private val instrumentsConfig = InstrumentsConfig()
    private val moscowToday = LocalDate.now(ZoneId.of("Europe/Moscow"))

    private fun service(config: RiskConfig = RiskConfig()): DrawdownProtectionService {
        val aumProvider = Mockito.mock(AumProvider::class.java)
        Mockito.`when`(aumProvider.latestAum()).thenReturn(config.maxPositionRub)
        return DrawdownProtectionService(
            config,
            positionRepo,
            snapshotRepo,
            instrumentsConfig,
            SimpleMeterRegistry(),
            aumProvider,
        )
    }

    /**
     * Suspend-метод на моке возвращает null по умолчанию — явно стаббим пустой список
     * открытых позиций (вызывать только внутри runBlocking).
     */
    private suspend fun stubNoOpenPositions() {
        Mockito.`when`(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(emptyList())
    }

    /**
     * Стаббит оконные запросы [PositionRepository.findClosedSince] и
     * [PositionRepository.findClosedAggregates] — согласованно с одним списком.
     */
    private suspend fun stubClosedPositions(positions: List<Position>) {
        Mockito.`when`(positionRepo.findClosedSince(any())).thenAnswer { inv ->
            val since = inv.getArgument<LocalDateTime>(0)
            positions.filter { (it.closedAt ?: LocalDateTime.MIN) >= since }
        }
        Mockito.`when`(positionRepo.findClosedAggregates()).thenReturn(
            PositionRepository.ClosedPositionAggregates(
                totalRealized = positions.sumOf { it.pnl ?: BigDecimal.ZERO },
                peakRealized = peakCumulativeRealized(positions),
            ),
        )
    }

    private fun peakCumulativeRealized(positions: List<Position>): BigDecimal {
        var running = BigDecimal.ZERO
        var peak = BigDecimal.ZERO
        for (pos in positions.filter { it.pnl != null }.sortedBy { it.closedAt ?: LocalDateTime.MIN }) {
            running = running.add(pos.pnl)
            if (running > peak) peak = running
        }
        return peak
    }

    private fun closedPosition(
        pnl: BigDecimal,
        closedAt: LocalDateTime,
    ): Position =
        Position(
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("100"),
            pnl = pnl,
            status = PositionStatus.CLOSED,
            closedAt = closedAt,
        )

    @Test
    fun `aum includes realized pnl and daily limit scales with it`() =
        runBlocking {
            stubNoOpenPositions()
            stubClosedPositions(
                listOf(
                    closedPosition(BigDecimal("30000"), LocalDateTime.now()),
                    closedPosition(BigDecimal("-10000"), LocalDateTime.now().minusMinutes(5)),
                ),
            )

            val s = service()
            val status = s.computeStatus()

            // AUM = 50 000 + 30 000 - 10 000 = 70 000
            assertEquals(0, BigDecimal("70000").compareTo(status.aum))
            // дневной лимит = 10% от AUM = 7 000
            assertEquals(0, BigDecimal("7000").compareTo(status.dailyLimitRub))
            // эффективный лимит берётся из кэша
            assertEquals(0, BigDecimal("7000").compareTo(s.effectiveDailyLossLimitRub()))
            assertFalse(status.blocking())
        }

    @Test
    fun `daily loss breach blocks entry`() =
        runBlocking {
            stubNoOpenPositions()
            stubClosedPositions(
                listOf(closedPosition(BigDecimal("-6000"), LocalDateTime.now())),
            )

            val s = service()
            val status = s.computeStatus()

            assertTrue(status.dailyLimitBreached)
            assertTrue(status.blocking())
            assertTrue(s.isEntryBlocked())
            assertTrue(s.entryBlockReason().contains("DAILY_LOSS"))
        }

    @Test
    fun `rolling 7d loss breach blocks entry even if today is flat`() =
        runBlocking {
            stubNoOpenPositions()
            stubClosedPositions(
                listOf(closedPosition(BigDecimal("-8000"), LocalDateTime.now().minusDays(6))),
            )

            val s = service()
            val status = s.computeStatus()

            assertFalse(status.dailyLimitBreached)
            assertTrue(status.rolling7dBreached)
            assertTrue(status.blocking())
            assertTrue(status.reasons.any { it.startsWith("ROLLING_7D_LOSS") })
        }

    @Test
    fun `rolling 30d loss breach blocks entry, isolated from 7d window`() =
        runBlocking {
            // 20 дней назад — вне окна 7д, но внутри 30д
            stubNoOpenPositions()
            stubClosedPositions(
                listOf(closedPosition(BigDecimal("-13000"), LocalDateTime.now().minusDays(20))),
            )

            val s = service()
            val status = s.computeStatus()

            assertFalse(status.rolling7dBreached)
            assertTrue(status.rolling30dBreached)
            assertTrue(status.blocking())
            assertTrue(status.reasons.any { it.startsWith("ROLLING_30D_LOSS") })
        }

    @Test
    fun `daily limit is pure percent of aum with no ruble floor`() =
        runBlocking {
            // AUM упал до 30 000 → 10% = 3 000, рублёвый floor НЕ применяется
            stubNoOpenPositions()
            stubClosedPositions(
                listOf(closedPosition(BigDecimal("-20000"), LocalDateTime.now().minusDays(1))),
            )
            val config =
                RiskConfig().apply {
                    maxDailyLossRub = BigDecimal("5000")
                    maxDailyLossPercent = 10.0
                }

            val s = service(config)
            val status = s.computeStatus()

            assertEquals(0, BigDecimal("30000").compareTo(status.aum))
            assertEquals(0, BigDecimal("3000").compareTo(status.dailyLimitRub))
        }

    @Test
    fun `consecutive losses activate shadow mode and profit clears it`() =
        runBlocking {
            val config = RiskConfig()
            val s = service(config)
            val now = LocalDateTime.now()

            stubNoOpenPositions()
            stubClosedPositions(
                listOf(
                    closedPosition(BigDecimal("-100"), now.minusMinutes(5)),
                    closedPosition(BigDecimal("-200"), now.minusMinutes(10)),
                    closedPosition(BigDecimal("-300"), now.minusMinutes(15)),
                ),
            )

            val status = s.computeStatus()

            assertEquals(3, status.consecutiveLosses)
            assertTrue(status.shadowModeActive)
            assertNotNull(status.shadowModeUntil)
            assertTrue(s.isShadowModeActive())
            assertTrue(status.blocking())
            assertTrue(status.reasons.any { it.startsWith("SHADOW_MODE") })

            // прибыльная сделка сбрасывает серию → shadow снимается
            stubClosedPositions(
                listOf(
                    closedPosition(BigDecimal("500"), now.minusMinutes(1)),
                    closedPosition(BigDecimal("-100"), now.minusMinutes(5)),
                    closedPosition(BigDecimal("-200"), now.minusMinutes(10)),
                    closedPosition(BigDecimal("-300"), now.minusMinutes(15)),
                ),
            )

            val cleared = s.computeStatus()

            assertEquals(0, cleared.consecutiveLosses)
            assertFalse(cleared.shadowModeActive)
            assertFalse(s.isShadowModeActive())
            assertFalse(cleared.blocking())
        }

    @Test
    fun `cachedOrNeutral before first compute is conservative and non-blocking`() {
        val s = service()

        val status = s.cachedOrNeutral()

        assertEquals(0, BigDecimal("50000").compareTo(status.aum))
        assertFalse(status.blocking())
        assertFalse(s.isEntryBlocked())
        assertEquals(0, BigDecimal("5000").compareTo(status.dailyLimitRub))
    }

    @Test
    fun `drawdown percent is measured from peak aum`() =
        runBlocking {
            stubNoOpenPositions()
            stubClosedPositions(
                listOf(
                    closedPosition(BigDecimal("10000"), LocalDateTime.now().minusDays(2)),
                    closedPosition(BigDecimal("-25000"), LocalDateTime.now().minusDays(1)),
                ),
            )

            val s = service()
            val status = s.computeStatus()

            // equity: 50000 -> 60000 (peak) -> 35000; dd = (60000-35000)/60000 = 41.67%
            assertEquals(0, BigDecimal("60000").compareTo(status.peakAum))
            assertEquals(0, BigDecimal("35000").compareTo(status.aum))
            assertEquals(41.67, status.drawdownPercent, 0.01)
        }

    @Test
    fun `drawdown percent is zero when equity at all time high`() =
        runBlocking {
            stubNoOpenPositions()
            stubClosedPositions(
                listOf(
                    closedPosition(BigDecimal("5000"), LocalDateTime.now().minusDays(2)),
                    closedPosition(BigDecimal("2000"), LocalDateTime.now().minusDays(1)),
                ),
            )

            val s = service()
            val status = s.computeStatus()

            assertEquals(0, BigDecimal("57000").compareTo(status.peakAum))
            assertEquals(0, BigDecimal("57000").compareTo(status.aum))
            assertEquals(0.0, status.drawdownPercent, 1e-9)
        }

    // ===================== Дневной P&L аккумулятор =====================

    @Test
    fun `updateDailyPnl accumulates and persists daily snapshot`() {
        val s = service()

        s.updateDailyPnl(BigDecimal("1000"))
        s.updateDailyPnl(BigDecimal("-400"))

        assertEquals(0, BigDecimal("600").compareTo(s.getDailyPnl()))
        assertFalse(s.isDailyLossLimitReached())
        Mockito.verify(snapshotRepo).upsert(moscowToday, BigDecimal("1000"), false, BigDecimal.ZERO)
        Mockito.verify(snapshotRepo).upsert(moscowToday, BigDecimal("600"), false, BigDecimal.ZERO)
    }

    @Test
    fun `updateDailyPnl triggers limit below percent of aum`() {
        val s = service()

        s.updateDailyPnl(BigDecimal("-5001"))

        // лимит = 10% от 50 000 = 5 000 (без рублёвого пола)
        assertTrue(s.isDailyLossLimitReached())
        assertEquals(0, BigDecimal("-5001").compareTo(s.getDailyPnl()))
        Mockito.verify(snapshotRepo).upsert(moscowToday, BigDecimal("-5001"), true, BigDecimal("-5001"))
    }

    @Test
    fun `small losses do not trigger the limit`() {
        val s = service()

        s.updateDailyPnl(BigDecimal("-3000"))

        assertFalse(s.isDailyLossLimitReached())
    }

    @Test
    fun `restores daily state from snapshot after restart`() {
        Mockito.`when`(snapshotRepo.findByDate(moscowToday)).thenReturn(
            DailyRiskSnapshot(
                id = 1,
                tradeDate = moscowToday,
                dailyPnl = BigDecimal("-3000"),
                limitReached = false,
                maxDrawdownToday = BigDecimal("-3000"),
            ),
        )
        val s = service()

        // любая точка дневного аккумулятора при первом касании дня восстанавливает снапшот
        s.updateDailyPnl(BigDecimal.ZERO)

        assertEquals(0, BigDecimal("-3000").compareTo(s.getDailyPnl()))
        assertFalse(s.isDailyLossLimitReached())
    }

    @Test
    fun `restores limit reached flag from snapshot`() {
        Mockito.`when`(snapshotRepo.findByDate(moscowToday)).thenReturn(
            DailyRiskSnapshot(
                id = 1,
                tradeDate = moscowToday,
                dailyPnl = BigDecimal("-6000"),
                limitReached = true,
                maxDrawdownToday = BigDecimal("-6000"),
            ),
        )
        val s = service()

        s.updateDailyPnl(BigDecimal.ZERO)

        assertTrue(s.isDailyLossLimitReached())
    }

    @Test
    fun `computeStatus reconciles daily pnl from db including open positions`() =
        runBlocking {
            // закрытая сегодня: -1000 (реализованный); открытая сегодня: -3000 unrealized
            stubClosedPositions(
                listOf(closedPosition(BigDecimal("-1000"), LocalDateTime.now())),
            )
            Mockito.`when`(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(
                listOf(
                    Position(
                        ticker = "SBER",
                        direction = PositionDirection.LONG,
                        quantity = 1000,
                        entryPrice = BigDecimal("100"),
                        currentPrice = BigDecimal("97"),
                        status = PositionStatus.OPEN,
                        openedAt = LocalDateTime.now(),
                    ),
                ),
            )

            val s = service()
            val status = s.computeStatus()

            // dailyPnl = -1000 (realized) + -3000 (unrealized open) = -4000
            assertEquals(0, BigDecimal("-4000").compareTo(status.dailyPnlRub))
            // AUM = 50 000 - 1000 - 3000 = 46 000
            assertEquals(0, BigDecimal("46000").compareTo(status.aum))
            assertEquals(0, BigDecimal("-4000").compareTo(s.getDailyPnl()))
        }
}
