package com.trading.bot.tuning

import com.trading.bot.backtest.StrategyParameters
import com.trading.bot.service.TradingControlService
import com.trading.bot.tuning.PerformanceGateCheck.GateVerdict
import com.trading.bot.tuning.PerformanceGateCheck.MonthlyMetrics
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.argumentCaptor
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.YearMonth

/**
 * Unit-тесты [MonthlyTuningService]: маршрутизация по вердикту gate check
 * (PASS/WARN → оптимизация, ALERT → автостоп), пропуск оптимизации при ALERT,
 * версионирование параметров и метрик в БД, поведение без JavaMailSender.
 *
 * Ветка отправки email не тестируется: `JavaMailSender` тянет `jakarta.mail`,
 * которого нет на тестовом classpath (Mockito не может замокать интерфейс) —
 * см. отчёт о покрытии.
 */
class MonthlyTuningServiceTest {
    private val versionRepository = Mockito.mock(StrategyVersionRepository::class.java)
    private val objectMapper = jacksonObjectMapper()

    private val parameters =
        StrategyParameters(
            slPercent = 0.02,
            tpPercent = 0.04,
            slPoints = 150,
            tpPoints = 300,
        )

    private val period = YearMonth.of(2026, 10)

    @BeforeEach
    fun stubSave() {
        runBlocking {
            Mockito.`when`(versionRepository.save(anyVersion())).thenAnswer { invocation ->
                invocation.getArgument<StrategyVersion>(0).copy(id = 42)
            }
        }
    }

    @Test
    fun `passing gate runs optimizer and persists version`() {
        val service = service()

        val result = runBlocking { service.runTuningCycle(healthyMetrics(), parameters, period) }

        assertEquals("2026-10", result.period)
        assertEquals(GateVerdict.PASS, result.gateVerdict)
        assertFalse(result.optimizationSkipped)
        assertFalse(result.requiresExtraValidation)
        assertFalse(result.tradingStopped)
        assertFalse(result.alertSent, "mail sender is not configured → no alert email")
        assertEquals(42L, result.newVersion?.id)

        val saved = captureSavedVersion()
        assertEquals("2026-10", saved.version)
        assertEquals("PASS", saved.gateStatus)
        assertTrue(saved.parameters.contains("\"slPoints\":120"), "optimizer output must be persisted: ${saved.parameters}")
        assertTrue(saved.metrics.contains("\"tradesCount\":30"), "monthly metrics must be persisted: ${saved.metrics}")
        assertNotNull(saved.notes)
        assertTrue(saved.notes!!.contains("Gate reasons"), "${saved.notes}")
        assertFalse(saved.rolledBack)
    }

    @Test
    fun `alert stops trading skips optimization and persists version`() {
        val service = service()

        val result =
            runBlocking {
                service.runTuningCycle(healthyMetrics().copy(profitFactor = 0.5), parameters, period)
            }

        assertEquals(GateVerdict.ALERT, result.gateVerdict)
        assertTrue(result.tradingStopped, "ALERT must stop trading")
        assertFalse(result.alertSent, "no mail sender → email is not sent")
        // Контракт: отсутствие транспорта — явный статус, а не «тихий false».
        assertEquals("NOT_CONFIGURED", result.alertEmailStatus)
        assertTrue(result.optimizationSkipped, "optimization must be skipped on ALERT")
        assertTrue(result.gateReasons.any { it.startsWith("ALERT:") }, "${result.gateReasons}")

        val saved = captureSavedVersion()
        assertEquals("ALERT", saved.gateStatus)
        assertTrue(saved.notes!!.contains("Пропуск из-за ALERT gate check"), "${saved.notes}")
        // Оптимизатор не вызывался: параметры остались прежними.
        assertTrue(saved.parameters.contains("\"slPoints\":150"), "${saved.parameters}")
    }

    @Test
    fun `alert really stops trading through control service`() {
        // Раньше ALERT лишь возвращал флаг tradingStopped и слал письмо:
        // бот продолжал открывать позиции (скан-10 P1).
        val control = Mockito.mock(TradingControlService::class.java)
        val service = service(control)

        val result =
            runBlocking {
                service.runTuningCycle(healthyMetrics().copy(profitFactor = 0.5), parameters, period)
            }

        assertEquals(GateVerdict.ALERT, result.gateVerdict)
        runBlocking { Mockito.verify(control).setTradingEnabled(false) }
    }

    @Test
    fun `healthy gate leaves trading enabled`() {
        val control = Mockito.mock(TradingControlService::class.java)
        val service = service(control)

        runBlocking { service.runTuningCycle(healthyMetrics(), parameters, period) }

        runBlocking { Mockito.verify(control, Mockito.never()).setTradingEnabled(false) }
    }

    @Test
    fun `warn verdict still tunes parameters`() {
        val service = service()

        val result =
            runBlocking {
                service.runTuningCycle(healthyMetrics().copy(profitFactor = 1.1), parameters, period)
            }

        assertEquals(GateVerdict.WARN, result.gateVerdict)
        assertFalse(result.optimizationSkipped)
        assertFalse(result.tradingStopped)

        val saved = captureSavedVersion()
        assertEquals("WARN", saved.gateStatus)
        // WARN-зона: сужение SL и расширение TP (как в ParameterOptimizer).
        assertTrue(saved.parameters.contains("\"slPoints\":120"), "${saved.parameters}")
        assertTrue(saved.parameters.contains("\"tpPoints\":360"), "${saved.parameters}")
    }

    private fun service(control: TradingControlService? = null): MonthlyTuningService =
        MonthlyTuningService(
            performanceGateCheck = PerformanceGateCheck(),
            parameterOptimizer = ParameterOptimizer(),
            strategyVersionRepository = versionRepository,
            mailSender = null,
            objectMapper = objectMapper,
            tradingControlService = control,
        )

    private fun captureSavedVersion(): StrategyVersion {
        val captor = argumentCaptor<StrategyVersion>()
        runBlocking { Mockito.verify(versionRepository).save(captor.capture()) }
        return captor.lastValue
    }

    private fun healthyMetrics(): MonthlyMetrics =
        MonthlyMetrics(
            profitFactor = 2.0,
            maxDrawdownPct = 5.0,
            winRatePct = 60.0,
            sharpe = 1.0,
            tradesCount = 30,
        )

    /**
     * Helper-паттерн Mockito для Kotlin non-null параметра (AGENTS.md):
     * `any(StrategyVersion::class.java)` регистрирует матчер и возвращает dummy.
     */
    private fun anyVersion(): StrategyVersion {
        Mockito.any(StrategyVersion::class.java)
        return StrategyVersion(
            version = "0000-00",
            parameters = "{}",
            gateStatus = "PASS",
            metrics = "{}",
        )
    }
}
