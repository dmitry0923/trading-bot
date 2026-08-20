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
import org.mockito.kotlin.anyOrNull
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

    private fun service(
        config: RiskConfig = RiskConfig(),
        balance: BigDecimal = config.maxPositionRub,
    ): DrawdownProtectionService {
        val aumProvider = Mockito.mock(AumProvider::class.java)
        // latestAum() = ТЕКУЩИЙ баланс счёта (moneyAmount), уже включает реализованный P&L.
        Mockito.`when`(aumProvider.latestAum(anyOrNull())).thenReturn(balance)
        return DrawdownProtectionService(
            config,
            positionRepo,
            snapshotRepo,
            instrumentsConfig,
            SimpleMeterRegistry(),
            aumProvider,
            Mockito.mock(TradingAccountService::class.java),
        )
    }

    /**
     * Suspend-метод на моке возвращает null по умолчанию — явно стаббим пустой список
     * открытых позиций (вызывать только внутри runBlocking).
     */
    private suspend fun stubNoOpenPositions() {
        Mockito.`when`(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(emptyList())
    }

    /**
     * Стаббит оконные запросы [PositionRepository.findClosedByAccountSince] и
     * [PositionRepository.findClosedAggregates] — согласованно с одним списком.
     */
    private suspend fun stubClosedPositions(positions: List<Position>) {
        Mockito.`when`(positionRepo.findClosedByAccountSince(anyOrNull(), any())).thenAnswer { inv ->
            val since = inv.getArgument<LocalDateTime>(1)
            positions.filter { (it.closedAt ?: LocalDateTime.MIN) >= since }
        }
        Mockito.`when`(positionRepo.findClosedAggregates(anyOrNull())).thenReturn(
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
        accountId: Long? = null,
    ): Position =
        Position(
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("100"),
            pnl = pnl,
            status = PositionStatus.CLOSED,
            closedAt = closedAt,
            accountId = accountId,
        )

    /**
     * Per-account стаб оконных запросов и агрегатов: каждый аккаунт видит только свои
     * закрытые позиции (как findClosedByAccountSince/findClosedAggregates в БД).
     */
    private suspend fun stubAccountClosedPositions(positionsByAccount: Map<Long?, List<Position>>) {
        Mockito.`when`(positionRepo.findClosedByAccountSince(anyOrNull(), any())).thenAnswer { inv ->
            val accountId = inv.getArgument<Long?>(0)
            val since = inv.getArgument<LocalDateTime>(1)
            (positionsByAccount[accountId] ?: emptyList()).filter { (it.closedAt ?: LocalDateTime.MIN) >= since }
        }
        Mockito.`when`(positionRepo.findClosedAggregates(anyOrNull())).thenAnswer { inv ->
            val accountId = inv.getArgument<Long?>(0)
            val positions = positionsByAccount[accountId] ?: emptyList()
            PositionRepository.ClosedPositionAggregates(
                totalRealized = positions.sumOf { it.pnl ?: BigDecimal.ZERO },
                peakRealized = peakCumulativeRealized(positions),
            )
        }
    }

    @Test
    fun `aum does not double count realized pnl already in balance (F-3)`() =
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

            // Баланс (latestAum) = 50 000 уже содержит реализованный P&L (+20 000);
            // добавлять totalRealized нельзя — двойной счёт.
            assertEquals(0, BigDecimal("50000").compareTo(status.aum))
            // дневной лимит = 2% от AUM = 1 000
            assertEquals(0, BigDecimal("1000").compareTo(status.dailyLimitRub))
            // эффективный лимит берётся из кэша
            assertEquals(0, BigDecimal("1000").compareTo(s.effectiveDailyLossLimitRub()))
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
    fun `daily limit is min of percent aum and ruble cap`() =
        runBlocking {
            // Баланс упал до 30 000 (депозит 50 000 - убыток 20 000) → 10% = 3 000,
            // рублёвый потолок 5 000 > 3 000 → effective = 3 000
            stubNoOpenPositions()
            stubClosedPositions(
                listOf(closedPosition(BigDecimal("-20000"), LocalDateTime.now().minusDays(1))),
            )
            val config =
                RiskConfig().apply {
                    maxDailyLossRub = BigDecimal("5000")
                    maxDailyLossPercent = 10.0
                }

            val s = service(config, balance = BigDecimal("30000"))
            val status = s.computeStatus()

            assertEquals(0, BigDecimal("30000").compareTo(status.aum))
            assertEquals(0, BigDecimal("3000").compareTo(status.dailyLimitRub))
        }

    @Test
    fun `ruble cap kicks in when percent limit exceeds it`() =
        runBlocking {
            // AUM = 100 000 → 10% = 10 000, но рублёвый потолок = 5 000 → effective = 5 000
            stubNoOpenPositions()
            stubClosedPositions(emptyList())
            val config =
                RiskConfig().apply {
                    maxDailyLossRub = BigDecimal("5000")
                    maxDailyLossPercent = 10.0
                }

            val s = service(config, balance = BigDecimal("100000"))
            val status = s.computeStatus()

            assertEquals(0, BigDecimal("100000").compareTo(status.aum))
            assertEquals(0, BigDecimal("5000").compareTo(status.dailyLimitRub))
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
        assertEquals(0, BigDecimal("1000").compareTo(status.dailyLimitRub))
    }

    @Test
    fun `isDailyLossLimitReached restores per-account snapshot on demand after restart`() {
        val s = service()
        val today = LocalDate.now(ZoneId.of("Europe/Moscow"))
        Mockito
            .`when`(snapshotRepo.findByDate(today, 7L))
            .thenReturn(
                DailyRiskSnapshot(
                    tradeDate = today,
                    dailyPnl = BigDecimal("-9000"),
                    limitReached = true,
                    maxDrawdownToday = BigDecimal("-9000"),
                ),
            )

        assertTrue(s.isDailyLossLimitReached(7L))
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

            // Баланс (latestAum) = 50 000. Депозит = balance - totalRealized =
            // 50 000 - (-15 000) = 65 000. Пик equity = 65 000 + 10 000 (peak) = 75 000;
            // текущая = 65 000 - 15 000 = 50 000; dd = (75 000 - 50 000)/75 000 = 33.33%
            assertEquals(0, BigDecimal("75000").compareTo(status.peakAum))
            assertEquals(0, BigDecimal("50000").compareTo(status.aum))
            assertEquals(33.33, status.drawdownPercent, 0.01)
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

            // Депозит = 50 000 - 7 000 = 43 000; пик = 43 000 + 7 000 = 50 000;
            // текущий баланс = 50 000 → просадки нет
            assertEquals(0, BigDecimal("50000").compareTo(status.peakAum))
            assertEquals(0, BigDecimal("50000").compareTo(status.aum))
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

        s.updateDailyPnl(BigDecimal("-500"))

        assertFalse(s.isDailyLossLimitReached())
    }

    @Test
    fun `restores daily state from snapshot after restart`() {
        Mockito.`when`(snapshotRepo.findByDate(moscowToday)).thenReturn(
            DailyRiskSnapshot(
                id = 1,
                tradeDate = moscowToday,
                dailyPnl = BigDecimal("-500"),
                limitReached = false,
                maxDrawdownToday = BigDecimal("-500"),
            ),
        )
        val s = service()

        // любая точка дневного аккумулятора при первом касании дня восстанавливает снапшот
        s.updateDailyPnl(BigDecimal.ZERO)

        assertEquals(0, BigDecimal("-500").compareTo(s.getDailyPnl()))
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
    fun `init loads today daily pnl and limit flag from snapshot on startup`() {
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
        s.init()

        assertEquals(0, BigDecimal("-6000").compareTo(s.getDailyPnl()))
        assertTrue(s.isDailyLossLimitReached())
    }

    @Test
    fun `computeStatus reconciles daily pnl from db including open positions`() =
        runBlocking {
            // закрытая сегодня: -1000 (реализованный); открытая сегодня: -3000 unrealized
            stubClosedPositions(
                listOf(closedPosition(BigDecimal("-1000"), LocalDateTime.now())),
            )
            Mockito.`when`(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(
                listOf(
                    Position(
                        ticker = "SBER",
                        direction = PositionDirection.LONG,
                        quantity = 100,
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
            // AUM = баланс 50 000 (реализованный -1000 уже в балансе) + unrealized -3000 = 47 000
            assertEquals(0, BigDecimal("47000").compareTo(status.aum))
            assertEquals(0, BigDecimal("-4000").compareTo(s.getDailyPnl()))
        }

    @Test
    fun `computeStatus does not clobber live accumulator with stale recompute (RISK-OPEN-3)`() =
        runBlocking {
            // recompute из БД не видит concurrent-закрытие (запрос прошёл до его коммита):
            // закрытий «нет» → recompute dailyPnl = 0, хотя в аккумуляторе уже -1000.
            stubClosedPositions(emptyList())
            stubNoOpenPositions()

            val s = service()
            // Живое накопление: -1000 в аккумуляторе (dirty=true), персист -1000.
            s.updateDailyPnl(BigDecimal("-1000"))

            s.computeStatus()

            // dirty-аккумулятор НЕ перезаписывается stale-recompute (0): оба персиста
            // (updateDailyPnl и computeStatus) сохраняют -1000, а не 0 (без dirty-гварда
            // второй был бы 0 — потеря инкремента в daily_risk_snapshot при рестарте).
            Mockito.verify(snapshotRepo, Mockito.times(2)).upsert(moscowToday, BigDecimal("-1000"), true, BigDecimal("-1000"))
        }

    // ===================== Per-account скоуп (F-1/F-13/F-14) =====================

    @Test
    fun `computeStatus scopes windows and aggregates by account (F-1)`() =
        runBlocking {
            stubNoOpenPositions()
            stubAccountClosedPositions(
                mapOf(
                    7L to listOf(closedPosition(BigDecimal("-6000"), LocalDateTime.now(), 7L)),
                    8L to listOf(closedPosition(BigDecimal("1000"), LocalDateTime.now(), 8L)),
                ),
            )

            val s = service()
            val accountA = s.computeStatus(7L)
            val accountB = s.computeStatus(8L)

            // A: дневной убыток -6000 → лимит (5 000) пробит
            assertEquals(0, BigDecimal("-6000").compareTo(accountA.dailyPnlRub))
            assertTrue(accountA.dailyLimitBreached)
            assertTrue(s.isEntryBlocked(7L))
            // B: прибыль +1000 — не блокирован, несмотря на заблокированный A
            assertEquals(0, BigDecimal("1000").compareTo(accountB.dailyPnlRub))
            assertFalse(accountB.dailyLimitBreached)
            assertFalse(s.isEntryBlocked(8L))
        }

    @Test
    fun `cachedOrNeutral and entry blocking are per-account, not global (F-13)`() =
        runBlocking {
            stubNoOpenPositions()
            stubAccountClosedPositions(
                mapOf(
                    7L to listOf(closedPosition(BigDecimal("-6000"), LocalDateTime.now(), 7L)),
                    8L to listOf(closedPosition(BigDecimal("500"), LocalDateTime.now(), 8L)),
                ),
            )

            val s = service()
            s.computeStatus(7L)
            s.computeStatus(8L)

            // кэш читается по аккаунту — без глобального статуса
            assertTrue(s.cachedOrNeutral(7L).blocking())
            assertFalse(s.cachedOrNeutral(8L).blocking())
            assertTrue(s.isEntryBlocked(7L))
            assertFalse(s.isEntryBlocked(8L))
        }

    @Test
    fun `shadow mode activates per-account, not from pooled losses (F-14)`() =
        runBlocking {
            val now = LocalDateTime.now()
            stubNoOpenPositions()
            val accountA =
                listOf(
                    closedPosition(BigDecimal("-100"), now.minusMinutes(5), 7L),
                    closedPosition(BigDecimal("-200"), now.minusMinutes(10), 7L),
                    closedPosition(BigDecimal("-300"), now.minusMinutes(15), 7L),
                )
            // B: прибыльная сделка → серия сброшена, shadow не включается
            val accountB = listOf(closedPosition(BigDecimal("500"), now.minusMinutes(1), 8L))
            stubAccountClosedPositions(mapOf(7L to accountA, 8L to accountB))

            val s = service()
            s.computeStatus(7L)
            s.computeStatus(8L)

            assertTrue(s.isShadowModeActive(7L))
            assertFalse(s.isShadowModeActive(8L))
            assertTrue(s.isEntryBlocked(7L))
            assertFalse(s.isEntryBlocked(8L))
        }
}
