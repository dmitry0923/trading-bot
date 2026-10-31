package com.trading.bot.service

import com.trading.bot.application.FuturesTradingBotService
import com.trading.bot.application.TradingGate
import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.model.CloseReason
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.BotSettings
import com.trading.bot.model.entity.Position
import com.trading.bot.model.entity.TradingHaltRecord
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [TradingControlService] — ручное управление торговлей: единый флаг, защита от
 * снятия EMERGENCY_STOP через UI, подсчёт ПОДТВЕРЖДЁННЫХ закрытий (а не кандидатов)
 * и плановое закрытие по времени с защитой от повторов.
 */
class TradingControlServiceTest {
    private val tradingGate = mock<TradingGate>()
    private val stockBot = mock<TradingBotService>()
    private val futuresBot = mock<FuturesTradingBotService>()
    private val settingsService = mock<SettingsService>()
    private val tradingHaltService = mock<TradingHaltService>()
    private val positionRepo = mock<PositionRepository>()
    private val meterRegistry = SimpleMeterRegistry()
    private val lockService = mock<DistributedLockService>()
    private val lockConfig = DistributedLockConfig()
    private val moscow = ZoneId.of("Europe/Moscow")

    private val service =
        TradingControlService(
            tradingGate,
            stockBot,
            futuresBot,
            settingsService,
            tradingHaltService,
            positionRepo,
            meterRegistry,
            lockService,
            lockConfig,
        )

    // ─── matcher-хелперы ─────────────────────────────────────────────

    private fun anySettings(): BotSettings {
        Mockito.any(BotSettings::class.java)
        return BotSettings()
    }

    private fun anyReasons(): Set<String> {
        Mockito.any<Set<String>>()
        return emptySet()
    }

    private fun anyReason(): CloseReason {
        Mockito.any(CloseReason::class.java)
        return CloseReason.FORCE_CLOSE
    }

    private fun anySettingsArg(): BotSettings {
        Mockito.any(BotSettings::class.java)
        return BotSettings()
    }

    private fun position(
        id: Long?,
        status: PositionStatus = PositionStatus.OPEN,
        pendingClose: Boolean = false,
        closeOrderId: String? = null,
    ): Position =
        Position(
            id = id,
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("100"),
            status = status,
            pendingClose = pendingClose,
            closeOrderId = closeOrderId,
        )

    private fun stubLockRunsBlock(onCompleted: () -> Unit = {}) {
        runBlocking {
            whenever(lockService.runExclusive(Mockito.anyString(), Mockito.anyLong(), Mockito.anyBoolean(), anyBlock()))
                .thenAnswer { invocation ->
                    val block = invocation.getArgument<suspend () -> Unit>(3)
                    runBlocking { block() }
                    onCompleted()
                    DistributedLockService.LockExecutionResult.COMPLETED
                }
        }
    }

    private fun anyBlock(): suspend () -> Unit {
        Mockito.any<suspend () -> Unit>()
        return {}
    }

    private fun stubSettings(
        forceCloseEnabled: Boolean = false,
        forceCloseTime: String = "",
        tradingEnabled: Boolean = true,
    ) {
        whenever(settingsService.getSettings()).thenReturn(
            BotSettings(
                forceCloseEnabled = forceCloseEnabled,
                forceCloseTime = forceCloseTime,
                tradingEnabled = tradingEnabled,
            ),
        )
        runBlocking {
            whenever(settingsService.updateSettings(anySettings())).thenAnswer { Unit }
            whenever(
                tradingHaltService.record(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    any(),
                ),
            ).thenAnswer { Unit }
            whenever(tradingHaltService.clear(anyReasons())).thenAnswer { Unit }
        }
    }

    // ─── единый флаг ────────────────────────────────────────────────

    @Test
    fun `isTradingEnabled delegates to trading gate`() {
        whenever(tradingGate.isTradingEnabled()).thenReturn(false)
        assertFalse(service.isTradingEnabled())

        whenever(tradingGate.isTradingEnabled()).thenReturn(true)
        assertTrue(service.isTradingEnabled())
    }

    @Test
    fun `enable is refused while emergency stop is active`(): Unit =
        runBlocking {
            stubSettings()
            whenever(tradingHaltService.last()).thenReturn(
                TradingHaltRecord(
                    reason = TradingHaltService.EMERGENCY_STOP_REASON,
                    source = "AUTO",
                    haltedAt = Instant.now(),
                ),
            )

            service.setTradingEnabled(true)

            verify(settingsService, never()).updateSettings(anySettingsArg())
            verify(tradingHaltService, never()).clear(anyReasons())
            assertEquals(
                1.0,
                meterRegistry
                    .counter("trading.control.enable_refused", "reason", TradingHaltService.EMERGENCY_STOP_REASON)
                    .count(),
                1e-9,
            )
        }

    @Test
    fun `enable persists the flag and clears non-emergency halts`(): Unit =
        runBlocking {
            stubSettings()
            whenever(tradingHaltService.last()).thenReturn(
                TradingHaltRecord(reason = "DAILY_LOSS_LIMIT", source = "AUTO", haltedAt = Instant.now()),
            )
            var persisted: BotSettings? = null
            var keptReasons: Set<String>? = null
            whenever(settingsService.updateSettings(anySettings())).thenAnswer { invocation ->
                persisted = invocation.getArgument(0)
                Unit
            }
            whenever(tradingHaltService.clear(anyReasons())).thenAnswer { invocation ->
                keptReasons = invocation.getArgument(0)
                Unit
            }

            service.setTradingEnabled(true)

            assertEquals(true, persisted?.tradingEnabled)
            assertEquals(setOf(TradingHaltService.EMERGENCY_STOP_REASON), keptReasons)
            assertEquals(1.0, meterRegistry.counter("trading.control.toggle", "enabled", "true").count(), 1e-9)
        }

    @Test
    fun `disable persists the flag and records a manual halt`(): Unit =
        runBlocking {
            stubSettings()
            var recorded: TradingHaltRecord? = null
            whenever(
                tradingHaltService.record(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    any(),
                ),
            ).thenAnswer { invocation ->
                recorded =
                    TradingHaltRecord(
                        reason = invocation.getArgument(0),
                        source = invocation.getArgument(1),
                        detail = invocation.getArgument(2),
                        haltedAt = invocation.getArgument(3),
                    )
                Unit
            }
            var persisted: BotSettings? = null
            whenever(settingsService.updateSettings(anySettings())).thenAnswer { invocation ->
                persisted = invocation.getArgument(0)
                Unit
            }

            service.setTradingEnabled(false)

            assertEquals(false, persisted?.tradingEnabled)
            assertEquals("MANUAL_DISABLE", recorded?.reason)
            assertEquals("MANUAL", recorded?.source)
            verify(tradingHaltService, never()).clear(anyReasons())
            assertEquals(1.0, meterRegistry.counter("trading.control.toggle", "enabled", "false").count(), 1e-9)
        }

    // ─── принудительное закрытие ────────────────────────────────────

    @Test
    fun `force close counts only confirmed closes and reports rejected ones`(): Unit =
        runBlocking {
            val openBefore =
                listOf(
                    position(1L),
                    position(2L),
                    position(3L),
                    position(4L),
                    position(null),
                )
            whenever(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(openBefore)
            whenever(stockBot.forceCloseAll(anyReason())).thenReturn(2)
            whenever(futuresBot.forceCloseAll(anyReason())).thenReturn(1)
            whenever(positionRepo.findById(1L)).thenReturn(position(1L, status = PositionStatus.CLOSED))
            whenever(positionRepo.findById(2L)).thenReturn(position(2L, pendingClose = true))
            whenever(positionRepo.findById(3L)).thenReturn(position(3L, closeOrderId = "close-3"))
            whenever(positionRepo.findById(4L)).thenReturn(position(4L))

            val closed = service.forceCloseNow()

            // подтверждены 1..3 (закрыта / в закрытии / есть close-ордер), 4 осталась открытой
            assertEquals(3, closed)
            assertEquals(1.0, meterRegistry.counter("trading.control.force_close", "reason", "FORCE_CLOSE").count(), 1e-9)
            assertEquals(
                0.0,
                meterRegistry.counter("trading.control.force_close_failed", "reason", "FORCE_CLOSE").count(),
                1e-9,
            )
        }

    @Test
    fun `force close reports candidates that stayed open`(): Unit =
        runBlocking {
            whenever(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(listOf(position(1L)))
            whenever(stockBot.forceCloseAll(anyReason())).thenReturn(1)
            whenever(futuresBot.forceCloseAll(anyReason())).thenReturn(0)
            whenever(positionRepo.findById(1L)).thenReturn(position(1L))

            assertEquals(0, service.forceCloseNow())
            assertEquals(
                1.0,
                meterRegistry.counter("trading.control.force_close_failed", "reason", "FORCE_CLOSE").count(),
                1e-9,
            )
        }

    @Test
    fun `force close never reports more closes than candidates`(): Unit =
        runBlocking {
            whenever(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(listOf(position(1L)))
            whenever(stockBot.forceCloseAll(anyReason())).thenReturn(1)
            whenever(futuresBot.forceCloseAll(anyReason())).thenReturn(0)
            whenever(positionRepo.findById(1L)).thenReturn(position(1L, status = PositionStatus.CLOSED))

            assertEquals(1, service.forceCloseNow())
            assertEquals(0.0, meterRegistry.counter("trading.control.force_close_failed").count(), 1e-9)
        }

    @Test
    fun `force close tolerates an unreadable position row`(): Unit =
        runBlocking {
            whenever(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(listOf(position(1L), position(2L)))
            whenever(stockBot.forceCloseAll(anyReason())).thenReturn(1)
            whenever(futuresBot.forceCloseAll(anyReason())).thenReturn(0)
            whenever(positionRepo.findById(1L)).thenThrow(IllegalStateException("db down"))
            whenever(positionRepo.findById(2L)).thenReturn(position(2L, status = PositionStatus.CLOSED))

            // строку 1 не удалось перечитать — считаем только подтверждённую 2
            assertEquals(1, service.forceCloseNow())
        }

    // ─── плановое закрытие ──────────────────────────────────────────

    @Test
    fun `scheduled close does nothing when disabled or time is blank`(): Unit =
        runBlocking {
            stubSettings(forceCloseEnabled = false, forceCloseTime = "00:00")
            service.scheduledForceClose()

            stubSettings(forceCloseEnabled = true, forceCloseTime = "  ")
            service.scheduledForceClose()

            verify(positionRepo, never()).findByStatus(any())
        }

    @Test
    fun `scheduled close ignores an unparsable time`(): Unit =
        runBlocking {
            stubSettings(forceCloseEnabled = true, forceCloseTime = "not-a-time")

            service.scheduledForceClose()

            verify(positionRepo, never()).findByStatus(any())
        }

    @Test
    fun `scheduled close waits until the configured time`(): Unit =
        runBlocking {
            val now = LocalTime.now(moscow)
            val target = now.plusMinutes(5)
            assumeTrue(target.isAfter(now), "test would cross midnight")
            stubSettings(
                forceCloseEnabled = true,
                forceCloseTime = target.format(DateTimeFormatter.ofPattern("HH:mm")),
            )

            service.scheduledForceClose()

            verify(positionRepo, never()).findByStatus(any())
        }

    @Test
    fun `scheduled close runs inside the distributed lock and only once per day`(): Unit =
        runBlocking {
            stubSettings(forceCloseEnabled = true, forceCloseTime = "00:00")
            val completed = CountDownLatch(1)
            stubLockRunsBlock { completed.countDown() }
            val attempts = AtomicInteger()
            whenever(positionRepo.findByStatus(PositionStatus.OPEN)).thenAnswer {
                attempts.incrementAndGet()
                emptyList<Position>()
            }
            whenever(stockBot.forceCloseAll(anyReason())).thenReturn(0)
            whenever(futuresBot.forceCloseAll(anyReason())).thenReturn(0)

            service.scheduledForceClose()
            assertTrue(completed.await(5, TimeUnit.SECONDS), "scheduled close must complete")

            // даём корутине выставить lastForceCloseDate после завершения блока
            Thread.sleep(100)
            service.scheduledForceClose()
            Thread.sleep(100)

            assertEquals(1, attempts.get(), "a second tick in the same day must not close again")
            assertEquals(
                1.0,
                meterRegistry.counter("trading.control.force_close", "reason", "FORCE_CLOSE_SCHEDULED").count(),
                1e-9,
            )
        }

    @Test
    fun `scheduled close retries after a failure because the day is not marked`(): Unit =
        runBlocking {
            stubSettings(forceCloseEnabled = true, forceCloseTime = "00:00")
            stubLockRunsBlock()
            whenever(stockBot.forceCloseAll(anyReason())).thenReturn(0)
            whenever(futuresBot.forceCloseAll(anyReason())).thenReturn(0)
            val attempts = AtomicInteger()
            val firstAttempt = CountDownLatch(1)
            val secondAttempt = CountDownLatch(1)
            whenever(positionRepo.findByStatus(PositionStatus.OPEN)).thenAnswer {
                when (attempts.incrementAndGet()) {
                    1 -> {
                        firstAttempt.countDown()
                        throw IllegalStateException("db down")
                    }

                    else -> {
                        secondAttempt.countDown()
                        emptyList<Position>()
                    }
                }
            }

            service.scheduledForceClose()
            assertTrue(firstAttempt.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)

            service.scheduledForceClose()
            assertTrue(secondAttempt.await(5, TimeUnit.SECONDS), "failed day must be retried on the next tick")
            assertEquals(2, attempts.get())
        }

    @Test
    fun `scheduled close does not start after shutdown`(): Unit =
        runBlocking {
            stubSettings(forceCloseEnabled = true, forceCloseTime = "00:00")
            service.close()

            service.scheduledForceClose()

            verify(positionRepo, never()).findByStatus(any())
        }

    @Test
    fun `force close passes the reason through to both trading services`(): Unit =
        runBlocking {
            whenever(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(emptyList())
            val stockReasons = mutableListOf<CloseReason>()
            val futuresReasons = mutableListOf<CloseReason>()
            whenever(stockBot.forceCloseAll(anyReason())).thenAnswer { invocation ->
                stockReasons += invocation.getArgument<CloseReason>(0)
                0
            }
            whenever(futuresBot.forceCloseAll(anyReason())).thenAnswer { invocation ->
                futuresReasons += invocation.getArgument<CloseReason>(0)
                0
            }

            service.forceCloseNow(CloseReason.FORCE_CLOSE_SCHEDULED)

            assertEquals(listOf(CloseReason.FORCE_CLOSE_SCHEDULED), stockReasons)
            assertEquals(listOf(CloseReason.FORCE_CLOSE_SCHEDULED), futuresReasons)
        }

    @Test
    fun `force close with no repository rows is a no-op`(): Unit =
        runBlocking {
            whenever(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(emptyList())
            whenever(stockBot.forceCloseAll(anyReason())).thenReturn(0)
            whenever(futuresBot.forceCloseAll(anyReason())).thenReturn(0)

            assertEquals(0, service.forceCloseNow())
        }
}
