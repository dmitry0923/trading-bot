package com.trading.bot.service

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.InstrumentsConfig.InstrumentSpec
import com.trading.bot.config.RiskConfig
import com.trading.bot.event.PositionClosedEvent
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
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Дополнительные ветки [DrawdownProtectionService]:
 *
 * - per-account дневной аккумулятор (`updateDailyPnl(accountId)`) и его персист;
 * - fail-soft при недоступной БД (чтение снапшота, запись снапшота, окно закрытий);
 * - futures-часть unrealized P&L (вариационная маржа и fallback на pointValue);
 * - границы эффективного дневного лимита (% / ₽ / оба выключены);
 * - вырожденный HWM (нулевой пик → просадка 0) и удержание Shadow-режима в кд;
 * - реакция на событие закрытия позиции (пересчёт статуса в фоне).
 */
class DrawdownProtectionServiceAccountTest {
    private val positionRepo = mock<PositionRepository>()
    private val snapshotRepo = mock<DailyRiskSnapshotRepository>()
    private val instrumentsConfig =
        InstrumentsConfig().apply {
            instruments =
                listOf(
                    InstrumentSpec(
                        ticker = "Si",
                        type = "FUTURES",
                        lotSize = 1,
                        priceStep = BigDecimal("0.01"),
                        priceStepCost = BigDecimal("1.0"),
                    ),
                )
        }
    private val meterRegistry = SimpleMeterRegistry()
    private val aumProvider = mock<AumProvider>()
    private val tradingAccountService = mock<TradingAccountService>()
    private val publisher = mock<ApplicationEventPublisher>()
    private val clock = Clock.fixed(Instant.parse("2026-10-25T09:00:00Z"), ZoneId.of("Europe/Moscow"))
    private val today = LocalDate.of(2026, 10, 25)

    // ─── matcher-хелперы (Mockito.any(Class) для non-null типов возвращает null) ──

    private fun anyDate(): LocalDate {
        Mockito.any(LocalDate::class.java)
        return today
    }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun anyBoolean(): Boolean {
        Mockito.any(Boolean::class.javaObjectType)
        return false
    }

    private fun anyNullableId(): Long? {
        Mockito.any(Long::class.javaObjectType)
        return null
    }

    private fun anyNullableDecimal(): BigDecimal? {
        Mockito.any(BigDecimal::class.java)
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

    private suspend fun stubEmptyPositions() {
        whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(emptyList())
        whenever(positionRepo.findClosedByAccountSince(anyOrNull(), any())).thenReturn(emptyList())
        whenever(positionRepo.findClosedAggregates(anyOrNull()))
            .thenReturn(PositionRepository.ClosedPositionAggregates(BigDecimal.ZERO, BigDecimal.ZERO))
    }

    // ─── per-account accumulator ──────────────────────────────────────

    @Test
    fun `account daily pnl accumulates, persists and publishes tagged gauges`(): Unit =
        runBlocking {
            stubEmptyPositions()
            whenever(tradingAccountService.cachedMaxDailyLossRubFor(anyOrNull())).thenReturn(null)
            val drawdown = service()
            // прогреваем кэш статуса аккаунта, чтобы проверить синхронное обновление
            drawdown.computeStatus(7L)

            drawdown.updateDailyPnl(BigDecimal("-400"), 7L)
            drawdown.updateDailyPnl(BigDecimal("-100"), 7L)

            assertEquals(0, BigDecimal("-500").compareTo(drawdown.getDailyPnl(7L)))
            assertFalse(drawdown.isDailyLossLimitReached(7L))
            val pnlGauge = meterRegistry.find("risk.daily.pnl").tag("account", "7").gauge()
            assertEquals(-500.0, pnlGauge?.value() ?: 0.0, 1e-9)
            val limitGauge = meterRegistry.find("risk.daily.limit.reached").tag("account", "7").gauge()
            assertEquals(0.0, limitGauge?.value() ?: 1.0, 1e-9)
            // кэш статуса аккаунта обновлён синхронно — вход блокируется без ожидания цикла
            assertEquals(0, BigDecimal("-500").compareTo(drawdown.cachedOrNeutral(7L).dailyPnlRub))
        }

    @Test
    fun `account daily pnl breach flips limit flag and cached status`(): Unit =
        runBlocking {
            stubEmptyPositions()
            whenever(tradingAccountService.cachedMaxDailyLossRubFor(anyOrNull())).thenReturn(null)
            val drawdown = service()
            drawdown.computeStatus(7L)

            drawdown.updateDailyPnl(BigDecimal("-5000"), 7L)

            assertTrue(drawdown.isDailyLossLimitReached(7L))
            assertTrue(drawdown.cachedOrNeutral(7L).dailyLimitBreached)
            val limitGauge = meterRegistry.find("risk.daily.limit.reached").tag("account", "7").gauge()
            assertEquals(1.0, limitGauge?.value() ?: 0.0, 1e-9)
        }

    @Test
    fun `account daily state read failure degrades to zero instead of crashing`() {
        whenever(snapshotRepo.findByDate(anyDate(), anyNullableId()))
            .thenThrow(IllegalStateException("db down"))
        val drawdown = service()

        assertEquals(0, BigDecimal.ZERO.compareTo(drawdown.getDailyPnl(9L)))
        assertFalse(drawdown.isDailyLossLimitReached(9L))
    }

    @Test
    fun `account snapshot persist failure does not break accounting`() {
        whenever(
            snapshotRepo.upsert(
                anyDate(),
                anyBigDecimal(),
                anyBoolean(),
                anyBigDecimal(),
                anyNullableId(),
                anyNullableDecimal(),
            ),
        ).thenThrow(IllegalStateException("db down"))
        val drawdown = service()

        drawdown.updateDailyPnl(BigDecimal("-100"), 7L)

        assertEquals(0, BigDecimal("-100").compareTo(drawdown.getDailyPnl(7L)))
    }

    @Test
    fun `startup survives daily snapshot read failure`() {
        whenever(snapshotRepo.findByDate(anyDate(), anyNullableId()))
            .thenThrow(IllegalStateException("db down"))
        val drawdown = service()

        drawdown.init()

        assertEquals(0, BigDecimal.ZERO.compareTo(drawdown.getDailyPnl()))
    }

    // ─── futures unrealized P&L ──────────────────────────────────────

    @Test
    fun `futures variation margin takes precedence over mark to market`(): Unit =
        runBlocking {
            stubClosedOnly()
            whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(
                listOf(
                    futuresPosition(variationMargin = BigDecimal("1234.5")),
                ),
            )
            val drawdown = service()

            val status = drawdown.computeStatus()

            // вариационная маржа уже содержит P&L — mark-to-market не применяется
            assertEquals(0, BigDecimal("1234.5").compareTo(status.dailyPnlRub))
            assertEquals(0, BigDecimal("51234.5").compareTo(status.aum))
        }

    @Test
    fun `futures unrealized falls back to point value when margin is zero`(): Unit =
        runBlocking {
            stubClosedOnly()
            whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(
                listOf(
                    futuresPosition(variationMargin = BigDecimal.ZERO),
                ),
            )
            val drawdown = service()

            val status = drawdown.computeStatus()

            // pointValue = priceStepCost/priceStep = 1.0/0.01 = 100 → 2 × 2 × 100 = 400
            assertEquals(0, BigDecimal("400").compareTo(status.dailyPnlRub))
        }

    // ─── границы лимита и просадки ───────────────────────────────────

    @Test
    fun `drawdown is zero when peak equity is zero`(): Unit =
        runBlocking {
            stubEmptyPositions()
            val drawdown = service(balance = BigDecimal.ZERO)

            val status = drawdown.computeStatus()

            assertEquals(0, BigDecimal.ZERO.compareTo(status.peakAum))
            assertEquals(0.0, status.drawdownPercent, 1e-9)
        }

    @Test
    fun `daily limit uses percent only when ruble cap is disabled`(): Unit =
        runBlocking {
            stubEmptyPositions()
            val config =
                baseConfig().apply {
                    maxDailyLossPercent = 10.0
                    maxDailyLossRub = BigDecimal.ZERO
                }

            val status = service(config).computeStatus()

            assertEquals(0, BigDecimal("5000").compareTo(status.dailyLimitRub))
        }

    @Test
    fun `daily limit uses ruble cap only when percent is disabled`(): Unit =
        runBlocking {
            stubEmptyPositions()
            val config =
                baseConfig().apply {
                    maxDailyLossPercent = 0.0
                    maxDailyLossRub = BigDecimal("7000")
                }

            val status = service(config).computeStatus()

            assertEquals(0, BigDecimal("7000").compareTo(status.dailyLimitRub))
        }

    @Test
    fun `daily limit is zero when both limits are disabled`(): Unit =
        runBlocking {
            stubEmptyPositions()
            val config =
                baseConfig().apply {
                    maxDailyLossPercent = 0.0
                    maxDailyLossRub = BigDecimal.ZERO
                }

            val status = service(config).computeStatus()

            assertEquals(0, BigDecimal.ZERO.compareTo(status.dailyLimitRub))
        }

    @Test
    fun `shadow mode keeps extended window on repeated scan within cooldown`(): Unit =
        runBlocking {
            val now = LocalDateTime.now(clock)
            whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(emptyList())
            whenever(positionRepo.findClosedByAccountSince(anyOrNull(), any())).thenReturn(
                listOf(
                    closedLoss(now.minusMinutes(5)),
                    closedLoss(now.minusMinutes(10)),
                    closedLoss(now.minusMinutes(15)),
                ),
            )
            whenever(positionRepo.findClosedAggregates(anyOrNull()))
                .thenReturn(PositionRepository.ClosedPositionAggregates(BigDecimal("-600"), BigDecimal.ZERO))
            val drawdown = service()

            val first = drawdown.computeStatus()
            val second = drawdown.computeStatus()

            assertTrue(first.shadowModeActive)
            assertTrue(second.shadowModeActive)
            assertEquals(first.shadowModeUntil, second.shadowModeUntil)
            assertEquals(1.0, meterRegistry.counter("drawdown.shadow.activated").count())
        }

    // ─── реакция на закрытие позиции ─────────────────────────────────

    @Test
    fun `position close event recomputes status for its account`() {
        val refreshed = CountDownLatch(1)
        runBlocking {
            stubEmptyPositions()
            whenever(positionRepo.findOpenByAccount(anyOrNull())).thenAnswer {
                refreshed.countDown()
                emptyList<Position>()
            }
        }
        val drawdown = service()

        drawdown.onPositionClosed(closeEvent())

        assertTrue(refreshed.await(3, TimeUnit.SECONDS), "close event must trigger status recompute")
    }

    @Test
    fun `position close event survives repository failure`() {
        val attempted = CountDownLatch(1)
        runBlocking {
            whenever(positionRepo.findClosedByAccountSince(anyOrNull(), any())).thenAnswer {
                attempted.countDown()
                throw IllegalStateException("db down")
            }
        }
        val drawdown = service()

        drawdown.onPositionClosed(closeEvent())

        assertTrue(attempted.await(3, TimeUnit.SECONDS), "recompute attempted")
        // фоновый сбой не пробрасывается в вызывающий поток (fail-soft)
        assertFalse(drawdown.isEntryBlocked())
    }

    @Test
    fun `legacy accumulator updates cached status synchronously`(): Unit =
        runBlocking {
            stubEmptyPositions()
            val drawdown = service()
            // заполняем кэш статуса (как первый стратегический цикл)
            drawdown.computeStatus()

            drawdown.updateDailyPnl(BigDecimal("-1500"))

            // кэш обновлён без ожидания следующего цикла — вход блокируется сразу
            assertTrue(drawdown.cachedOrNeutral().dailyLimitBreached)
            assertEquals(0, BigDecimal("-1500").compareTo(drawdown.cachedOrNeutral().dailyPnlRub))
            assertEquals(1.0, meterRegistry.find("risk.daily.limit.reached").gauge()?.value() ?: 0.0, 1e-9)
            drawdown.close()
        }

    @Test
    fun `legacy snapshot persist failure does not break accounting`() {
        whenever(
            snapshotRepo.upsert(anyDate(), anyBigDecimal(), anyBoolean(), anyBigDecimal(), isNull(), anyNullableDecimal()),
        ).thenThrow(IllegalStateException("db down"))
        val drawdown = service()

        drawdown.updateDailyPnl(BigDecimal("-100"))

        assertEquals(0, BigDecimal("-100").compareTo(drawdown.getDailyPnl()))
    }

    @Test
    fun `short stock unrealized pnl is positive when price falls`(): Unit =
        runBlocking {
            stubClosedOnly()
            whenever(positionRepo.findOpenByAccount(anyOrNull())).thenReturn(
                listOf(
                    Position(
                        ticker = "SBER",
                        direction = PositionDirection.SHORT,
                        quantity = 100,
                        entryPrice = BigDecimal("100"),
                        currentPrice = BigDecimal("97"),
                        status = PositionStatus.OPEN,
                    ),
                ),
            )
            val drawdown = service()

            val status = drawdown.computeStatus()

            // спеку SBER этот тест не задаёт → lotSize fallback 1: (100 - 97) × 100 × 1 = +300
            assertEquals(0, BigDecimal("300").compareTo(status.dailyPnlRub))
        }

    private suspend fun stubClosedOnly() {
        whenever(positionRepo.findClosedByAccountSince(anyOrNull(), any())).thenReturn(emptyList())
        whenever(positionRepo.findClosedAggregates(anyOrNull()))
            .thenReturn(PositionRepository.ClosedPositionAggregates(BigDecimal.ZERO, BigDecimal.ZERO))
    }

    private fun futuresPosition(variationMargin: BigDecimal): Position =
        Position(
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 2,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("102"),
            instrumentType = InstrumentType.FUTURES,
            variationMargin = variationMargin,
            status = PositionStatus.OPEN,
        )

    private fun closedLoss(closedAt: LocalDateTime): Position =
        Position(
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("100"),
            pnl = BigDecimal("-200"),
            status = PositionStatus.CLOSED,
            closedAt = closedAt,
        )

    private fun closeEvent(): PositionClosedEvent =
        PositionClosedEvent(
            positionId = 1L,
            ticker = "SBER",
            pnl = BigDecimal("-100"),
            reason = "STOP_LOSS",
            accountId = 7L,
        )
}
