package com.trading.bot.tuning

import com.trading.bot.backtest.StrategyParameters
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.YearMonth
import java.time.ZoneId

/**
 * Идемпотентность месячного тюнинга.
 *
 * Cron `0 5 9 1-7 * MON-FRI` в Spring — это «каждый будний день с 1 по 7 число»
 * (day-of-month и day-of-week соединяются по AND), т.е. до 5 запусков в месяц вместо
 * «первого рабочего дня». Повторный цикл в том же периоде падал на unique-индексе
 * `strategy_versions(version)` и повторно дёргал остановку торговли по ALERT —
 * поэтому цикл ограничен одним запуском на период (скан-6 P2).
 */
class MonthlyTuningSchedulerTest {
    private val service: MonthlyTuningService = mock()
    private val metricsProvider: TuningMetricsProvider = mock()

    private fun scheduler() = MonthlyTuningScheduler(service, metricsProvider)

    /** Число фактических вызовов `runTuningCycle` (без вызова suspend-метода в тесте). */
    private fun cycleCalls(): Int =
        Mockito
            .mockingDetails(service)
            .invocations
            .count { it.method.name == "runTuningCycle" }

    private fun stubCycle(period: String = MoscowPeriod.current()) {
        runBlocking {
            whenever(metricsProvider.collectCurrentMonthlyData())
                .thenReturn(
                    TuningMetricsProvider.MonthlyData(
                        metrics = PerformanceGateCheck.MonthlyMetrics(1.5, 5.0, 50.0, 1.2, 40),
                        currentParameters = StrategyParameters(),
                    ),
                )
            whenever(service.runTuningCycle(any(), any(), any()))
                .thenReturn(
                    MonthlyTuningService.TuningCycleResult(
                        period = period,
                        gateVerdict = PerformanceGateCheck.GateVerdict.PASS,
                        gateReasons = emptyList(),
                        optimizationSkipped = false,
                        requiresExtraValidation = false,
                        tradingStopped = false,
                        alertSent = false,
                        newVersion = null,
                    ),
                )
        }
    }

    private fun awaitCycleCalls(
        expected: Int,
        timeoutMs: Long = 5_000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cycleCalls() >= expected) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun `second scheduled run in the same period is skipped`() {
        stubCycle()
        val scheduler = scheduler()

        scheduler.runMonthlyTuning()
        assertTrue(awaitCycleCalls(1), "первый плановый цикл должен выполниться")

        scheduler.runMonthlyTuning()
        Thread.sleep(300)

        assertEquals(1, cycleCalls(), "повторный запуск в том же периоде пропускается")
    }

    @Test
    fun `manual run bypasses the period guard`() {
        stubCycle()
        val scheduler = scheduler()

        scheduler.runNow()
        assertTrue(awaitCycleCalls(1))
        scheduler.runNow()
        assertTrue(awaitCycleCalls(2), "force-запуск не блокируется периодом")

        assertEquals(2, cycleCalls())
    }

    @Test
    fun `current period is the Moscow business month`() {
        val period = scheduler().currentPeriod()

        assertTrue(Regex("""\d{4}-\d{2}""").matches(period), "period=$period")
        assertEquals(MoscowPeriod.current(), period)
    }

    private object MoscowPeriod {
        fun current(): String = YearMonth.now(ZoneId.of("Europe/Moscow")).toString()
    }
}
