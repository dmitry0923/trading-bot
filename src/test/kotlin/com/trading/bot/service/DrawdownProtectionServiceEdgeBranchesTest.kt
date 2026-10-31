package com.trading.bot.service

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.DailyRiskSnapshotRepository
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [DrawdownProtectionService] — защитные (fail-soft) ветки дневного состояния:
 * позиции без даты закрытия/цены, незагруженный per-account снапшот, чтение
 * кэша лимита до первого расчёта и dirty-гвард аккаунта.
 */
class DrawdownProtectionServiceEdgeBranchesTest {
    private val positionRepo = mock<PositionRepository>()
    private val snapshotRepo = mock<DailyRiskSnapshotRepository>()
    private val instrumentsConfig = InstrumentsConfig()
    private val meterRegistry = SimpleMeterRegistry()
    private val aumProvider = mock<AumProvider>()
    private val tradingAccountService = mock<TradingAccountService>()
    private val publisher = mock<ApplicationEventPublisher>()
    private val clock = Clock.fixed(Instant.parse("2026-10-25T09:00:00Z"), ZoneId.of("Europe/Moscow"))
    private val today = LocalDate.of(2026, 10, 25)

    private fun anyDate(): LocalDate {
        Mockito.any(LocalDate::class.java)
        return today
    }

    private fun anyNullableId(): Long? {
        Mockito.any(Long::class.javaObjectType)
        return null
    }

    private fun service(
        config: RiskConfig = baseConfig(),
        balance: BigDecimal = BigDecimal("50000"),
    ): DrawdownProtectionService {
        whenever(aumProvider.latestAum(anyOrNull())).thenReturn(balance)
        return DrawdownProtectionService(
            config,
            positionRepo,
            snapshotRepo,
            instrumentsConfig,
            meterRegistry,
            aumProvider,
            tradingAccountService,
            publisher,
            clock,
        )
    }

    private fun baseConfig(): RiskConfig =
        RiskConfig().apply {
            autoStopEnabled = false
            shadowModeEnabled = true
        }

    private suspend fun stubNoOpen() {
        whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(emptyList())
        whenever(positionRepo.findClosedByAccountSince(anyOrNull(), any())).thenReturn(emptyList())
        whenever(positionRepo.findClosedAggregates(anyOrNull()))
            .thenReturn(PositionRepository.ClosedPositionAggregates(BigDecimal.ZERO, BigDecimal.ZERO))
    }

    private fun closed(
        pnl: BigDecimal?,
        closedAt: LocalDateTime?,
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

    @Test
    fun `consecutive losses tolerate missing timestamps and pnl`() {
        val now = LocalDateTime.now(clock)
        val drawdown = service()

        // null-дата сортируется в конец и не мешает серии убытков
        val streak =
            drawdown.consecutiveLosses(
                listOf(
                    closed(BigDecimal("-10"), null),
                    closed(BigDecimal("-20"), now.minusMinutes(1)),
                    closed(BigDecimal("50"), now.minusMinutes(2)),
                ),
            )

        assertEquals(1, streak)
        // null-pnl не считается убытком и обрывает серию
        assertEquals(0, drawdown.consecutiveLosses(listOf(closed(null, now.minusMinutes(1)))))
        assertEquals(0, drawdown.consecutiveLosses(emptyList()))
    }

    @Test
    fun `non open position in the open list contributes zero unrealized pnl`(): Unit =
        runBlocking {
            stubNoOpen()
            whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(
                listOf(
                    closed(BigDecimal("-999"), LocalDateTime.now(clock)),
                    Position(
                        ticker = "SBER",
                        direction = PositionDirection.LONG,
                        quantity = 10,
                        entryPrice = BigDecimal("100"),
                        currentPrice = BigDecimal("90"),
                        status = PositionStatus.OPEN,
                    ),
                ),
            )
            val drawdown = service()

            val status = drawdown.computeStatus()

            // закрытая строка в списке открытых не даёт unrealized, открытая даёт -1000
            assertEquals(0, BigDecimal("-1000").compareTo(status.dailyPnlRub))
        }

    @Test
    fun `closed position without closedAt is excluded from rolling windows`(): Unit =
        runBlocking {
            stubNoOpen()
            whenever(positionRepo.findClosedByAccountSince(anyOrNull(), any())).thenReturn(
                listOf(
                    closed(BigDecimal("-6000"), null),
                    closed(null, LocalDateTime.now(clock)),
                ),
            )
            val drawdown = service()

            val status = drawdown.computeStatus()

            // строка без closedAt не попадает в 7д окно (там фильтр по дате)
            assertEquals(0, BigDecimal.ZERO.compareTo(status.rolling7dPnlRub))
            // 30д окно берёт все закрытия выборки, включая строку без метки времени
            assertEquals(0, BigDecimal("-6000").compareTo(status.rolling30dPnlRub))
            // дневной P&L считается по всем закрытиям дня, независимо от метки времени
            assertEquals(0, BigDecimal("-6000").compareTo(status.dailyPnlRub))
        }

    @Test
    fun `cached status before first compute uses account limit override`() {
        whenever(tradingAccountService.cachedMaxDailyLossRubFor(anyOrNull())).thenReturn(BigDecimal("7000"))
        val drawdown = service()

        val status = drawdown.cachedOrNeutral(7L)

        assertEquals(0, BigDecimal.ZERO.compareTo(status.dailyPnlRub))
        assertFalse(status.dailyLimitBreached)
        assertEquals(0, BigDecimal("7000").compareTo(status.dailyLimitRub))
    }

    @Test
    fun `effective daily limit prefers the cached status aum`(): Unit =
        runBlocking {
            stubNoOpen()
            whenever(tradingAccountService.cachedMaxDailyLossRubFor(anyOrNull())).thenReturn(null)
            val drawdown = service()

            assertEquals(0, BigDecimal("1000").compareTo(drawdown.effectiveDailyLossLimitRub()))
            drawdown.computeStatus()

            // AUM вырос после расчёта — кэш статуса остаётся источником лимита
            whenever(aumProvider.latestAum(anyOrNull())).thenReturn(BigDecimal("100000"))
            assertEquals(0, BigDecimal("1000").compareTo(drawdown.effectiveDailyLossLimitRub()))
        }

    @Test
    fun `account accumulator starts from zero without prior status computation`(): Unit =
        runBlocking {
            whenever(tradingAccountService.cachedMaxDailyLossRubFor(anyOrNull())).thenReturn(null)
            val drawdown = service()

            drawdown.updateDailyPnl(BigDecimal("-150"), 7L)

            assertEquals(0, BigDecimal("-150").compareTo(drawdown.getDailyPnl(7L)))
            assertFalse(drawdown.isDailyLossLimitReached(7L))
        }

    @Test
    fun `account daily state is loaded once per day`() {
        var loads = 0
        whenever(snapshotRepo.findByDate(anyDate(), anyNullableId())).thenAnswer {
            loads++
            null
        }
        val drawdown = service()

        drawdown.getDailyPnl(7L)
        drawdown.getDailyPnl(7L)
        drawdown.isDailyLossLimitReached(7L)

        assertEquals(1, loads)
    }

    @Test
    fun `awaited account state is loaded once per day`(): Unit =
        runBlocking {
            stubNoOpen()
            whenever(tradingAccountService.cachedMaxDailyLossRubFor(anyOrNull())).thenReturn(null)
            var loads = 0
            whenever(snapshotRepo.findByDateAwait(anyDate(), anyNullableId())).thenAnswer {
                loads++
                null
            }
            val drawdown = service()

            drawdown.computeStatus(7L)
            drawdown.computeStatus(7L)

            assertEquals(1, loads)
        }

    @Test
    fun `dirty account accumulator is not clobbered by recompute`(): Unit =
        runBlocking {
            stubNoOpen()
            whenever(tradingAccountService.cachedMaxDailyLossRubFor(anyOrNull())).thenReturn(null)
            whenever(positionRepo.findClosedByAccountSince(anyOrNull(), any())).thenReturn(
                listOf(closed(BigDecimal("-900"), LocalDateTime.now(clock), 7L)),
            )
            val drawdown = service()

            drawdown.updateDailyPnl(BigDecimal("-100"), 7L)
            val status = drawdown.computeStatus(7L)

            // живому инкременту -100 recompute из БД (-900) не мешает в аккумуляторе,
            // но сам статус остаётся снимком фактических сделок из БД
            assertEquals(0, BigDecimal("-100").compareTo(drawdown.getDailyPnl(7L)))
            assertEquals(0, BigDecimal("-900").compareTo(status.dailyPnlRub))
        }

    @Test
    fun `account loss limit is restored from snapshot`() {
        whenever(snapshotRepo.findByDate(anyDate(), anyNullableId())).thenReturn(
            com.trading.bot.model.entity.DailyRiskSnapshot(
                tradeDate = today,
                dailyPnl = BigDecimal("-8000"),
                limitReached = true,
                maxDrawdownToday = BigDecimal("-8000"),
            ),
        )
        val drawdown = service()

        assertTrue(drawdown.isDailyLossLimitReached(7L))
        assertEquals(0, BigDecimal("-8000").compareTo(drawdown.getDailyPnl(7L)))
    }

    @Test
    fun `futures position with zero variation margin falls back to point value`(): Unit =
        runBlocking {
            stubNoOpen()
            whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(
                listOf(
                    Position(
                        ticker = "Si",
                        direction = PositionDirection.LONG,
                        quantity = 1,
                        entryPrice = BigDecimal("100"),
                        currentPrice = BigDecimal("102"),
                        variationMargin = BigDecimal.ZERO,
                        instrumentType = InstrumentType.FUTURES,
                        status = PositionStatus.OPEN,
                    ),
                ),
            )
            val drawdown = service()

            val status = drawdown.computeStatus()

            // Si: pointValue = 10 / 0.01 = 1000 → (102-100) * 1000 = 2000
            assertEquals(0, BigDecimal("2000").compareTo(status.dailyPnlRub))
        }
}
