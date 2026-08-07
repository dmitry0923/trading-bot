package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.model.Position
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Multi-Tier Drawdown Protection: лимиты в % от AUM (с рублёвым «полом»),
 * скользящие просадки 7д/30д и Shadow/Read-only режим по серии убытков.
 */
class DrawdownProtectionServiceTest {
    private val positionRepo = Mockito.mock(PositionRepository::class.java)

    private fun service(config: RiskConfig = RiskConfig()): DrawdownProtectionService =
        DrawdownProtectionService(config, positionRepo, SimpleMeterRegistry())

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
            Mockito.`when`(positionRepo.findClosed()).thenReturn(
                listOf(
                    closedPosition(BigDecimal("30000"), LocalDateTime.now().minusDays(1)),
                    closedPosition(BigDecimal("-10000"), LocalDateTime.now().minusDays(2)),
                ),
            )

            val s = service()
            val status = s.computeStatus()

            // AUM = 50 000 + 30 000 - 10 000 = 70 000
            assertEquals(0, BigDecimal("70000").compareTo(status.aum))
            // дневной лимит = 10% от AUM = 7 000 (превышает рублёвый floor 5 000)
            assertEquals(0, BigDecimal("7000").compareTo(status.dailyLimitRub))
            // эффективный лимит берётся из кэша
            assertEquals(0, BigDecimal("7000").compareTo(s.effectiveDailyLossLimitRub()))
            assertFalse(status.blocking())
        }

    @Test
    fun `daily loss breach blocks entry`() =
        runBlocking {
            Mockito.`when`(positionRepo.findClosed()).thenReturn(
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
            Mockito.`when`(positionRepo.findClosed()).thenReturn(
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
            Mockito.`when`(positionRepo.findClosed()).thenReturn(
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
    fun `ruble floor keeps effective daily limit from collapsing in drawdown`() =
        runBlocking {
            // AUM упал до 30 000 → 10% = 3 000, но рублёвый floor 5 000 доминирует
            Mockito.`when`(positionRepo.findClosed()).thenReturn(
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
            assertEquals(0, BigDecimal("5000").compareTo(status.dailyLimitRub))
        }

    @Test
    fun `consecutive losses activate shadow mode and profit clears it`() =
        runBlocking {
            val config = RiskConfig()
            val s = service(config)
            val now = LocalDateTime.now()

            Mockito.`when`(positionRepo.findClosed()).thenReturn(
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
            Mockito.`when`(positionRepo.findClosed()).thenReturn(
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
            Mockito.`when`(positionRepo.findClosed()).thenReturn(
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
            Mockito.`when`(positionRepo.findClosed()).thenReturn(
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
}
