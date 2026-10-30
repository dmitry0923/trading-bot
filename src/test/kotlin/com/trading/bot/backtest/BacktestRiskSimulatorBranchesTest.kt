package com.trading.bot.backtest

import com.trading.bot.application.decision.NetEvGate
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Непокрытые ветви [BacktestRiskSimulator] (скан-19: 62 из 190): Kelly-статистика из
 * истории сделок (Wilson, деградация до нуля), риск-кэп при недоступном ATR
 * (fail-open по `volatilityFailClosed`), NET EV по фактическим сделкам, exposure/
 * concentration с несколькими позициями и короткими входами, rolling 7d/30d лимиты,
 * выключенный shadow-режим, SHORT-P&L в `recordClose` и UNKNOWN-сектор.
 */
class BacktestRiskSimulatorBranchesTest {
    private val capital: BigDecimal = BigDecimal("100000")

    private fun at(
        dayOffset: Long,
        hour: Int = 10,
    ): LocalDateTime = LocalDate.now().plusDays(dayOffset).atTime(hour, 0)

    private fun riskConfig(): RiskConfig =
        RiskConfig().apply {
            maxOpenPositions = 5
            maxSectorExposure = 3
            riskPerTradePercent = 1.0
            portfolioRiskEnabled = false
            sectors = mapOf("TEST" to "TECH", "TEST2" to "TECH", "TEST3" to "TECH")
            kellyNoDataFraction = 1.0
        }

    private fun instruments(): InstrumentsConfig =
        InstrumentsConfig().apply {
            instruments =
                listOf("TEST", "TEST2", "TEST3").map { ticker ->
                    InstrumentsConfig.InstrumentSpec(
                        ticker = ticker,
                        type = "STOCK",
                        lotSize = 10,
                        priceStep = BigDecimal("0.01"),
                        priceStepCost = BigDecimal("0.1"),
                        go = BigDecimal.ZERO,
                        leverage = BigDecimal("1.0"),
                        baseAsset = "RUB",
                    )
                }
        }

    private fun sim(
        cfg: RiskConfig = riskConfig(),
        instruments: InstrumentsConfig = instruments(),
        netEvGate: NetEvGate? = null,
        initial: BigDecimal = capital,
    ): BacktestRiskSimulator =
        BacktestRiskSimulator(cfg, instruments, netEvGate).apply {
            initialize(initial)
        }

    private fun candle(
        time: LocalDateTime,
        open: String,
        high: String,
        low: String,
        close: String,
    ): Candle =
        Candle(
            ticker = "TEST",
            timeframe = "MINUTE_10",
            openPrice = BigDecimal(open),
            highPrice = BigDecimal(high),
            lowPrice = BigDecimal(low),
            closePrice = BigDecimal(close),
            volume = 10_000L,
            time = time,
        )

    private fun normalHistory(count: Int = 20): List<Candle> =
        (0 until count).map { i ->
            candle(at(-1, 9).plusMinutes(10L * i), "100.00", "100.50", "99.50", "100.00")
        }

    private fun entryCandle(): Candle = candle(at(0), "100.00", "100.50", "99.50", "100.00")

    private suspend fun check(
        sim: BacktestRiskSimulator,
        ticker: String = "TEST",
        signal: StrategyAction = StrategyAction.BUY,
        entryPrice: BigDecimal = BigDecimal("100.00"),
        cash: BigDecimal = capital,
        candle: Candle = entryCandle(),
        history: List<Candle> = normalHistory(),
        strength: Double? = null,
    ): BacktestRiskSimulator.GateResult =
        sim.checkEntry(
            ticker = ticker,
            signal = signal,
            entryPrice = entryPrice,
            cash = cash,
            candle = candle,
            history = history,
            signalStrength = strength,
        )

    /** Записывает закрытие сделки с заданным результатом (lotSize 10, qty 1). */
    private fun close(
        sim: BacktestRiskSimulator,
        exitPrice: String,
        quantity: Int = 1,
        direction: PositionDirection = PositionDirection.LONG,
        dayOffset: Long = 0,
        commission: String = "0",
        cash: BigDecimal = capital,
        entryPrice: String = "100",
        ticker: String = "TEST",
    ) {
        runBlocking {
            sim.recordClose(
                ticker = ticker,
                direction = direction,
                entryPrice = BigDecimal(entryPrice),
                exitPrice = BigDecimal(exitPrice),
                quantity = quantity,
                entryTime = at(dayOffset - 1),
                exitTime = at(dayOffset),
                closeReason = "TEST",
                commission = BigDecimal(commission),
                cash = cash,
            )
        }
    }

    // ============================================================ Kelly sizing

    @Test
    fun `kelly history path sizes the entry and allows it`() {
        val sim = sim()
        repeat(20) { close(sim, "101") }

        val result = runBlocking { check(sim, strength = 0.9) }

        assertTrue(result.allowed, "история прибыльных сделок должна давать положительный Kelly: $result")
        assertTrue(result.kellySizeLots > 0)
    }

    @Test
    fun `kelly collapses to zero on a losing history`() {
        val cfg = riskConfig().apply { shadowModeEnabled = false }
        val sim = sim(cfg)
        repeat(20) { close(sim, "99") }

        val result = runBlocking { check(sim) }

        assertEquals("ZERO_RISK_SIZE", result.reason)
        assertEquals(0, result.kellySizeLots)
    }

    @Test
    fun `missing atr is fail open when volatility guard is disabled`() {
        val cfg = riskConfig().apply { volatilityFailClosed = false }
        val sim = sim(cfg)

        val result = runBlocking { check(sim, history = emptyList()) }

        assertTrue(result.allowed, "ATR недоступен, но fail-closed выключен: $result")
    }

    @Test
    fun `zero sl distance cannot be risk capped and blocks the entry`() {
        val cfg =
            riskConfig().apply {
                volatilityFailClosed = false
                atrSlMultiplier = BigDecimal.ZERO
                defaultStopLossPercent = BigDecimal.ZERO
            }
        val sim = sim(cfg)

        val result = runBlocking { check(sim, history = emptyList()) }

        assertEquals("ZERO_RISK_SIZE", result.reason, "нулевой риск на лот — размер не определяется")
    }

    // ============================================================== NET EV gate

    @Test
    fun `net ev gate blocks on unknown statistics`() {
        val netEvConfig =
            riskConfig().apply {
                netEvGateEnabled = true
                netEvBlockOnUnknown = true
            }
        val sim = sim(netEvConfig, netEvGate = NetEvGate(instruments(), netEvConfig))

        val result = runBlocking { check(sim) }

        assertEquals("NET_EV_TOO_LOW", result.reason)
        val blocked = result.netEvResult as NetEvGate.GateResult.Blocked
        assertNull(blocked.netEV, "статистики нет — EV неизвестен")
    }

    @Test
    fun `net ev gate aggregates statistics from recorded trades`() {
        val netEvConfig =
            riskConfig().apply {
                netEvGateEnabled = true
                minNetEvThresholdRub = BigDecimal("-100000")
            }
        val sim = sim(netEvConfig, netEvGate = NetEvGate(instruments(), netEvConfig))
        repeat(20) { close(sim, "101") }

        val result = runBlocking { check(sim) }

        assertTrue(result.allowed, "положительный EV от истории должен проходить: $result")
        assertTrue(result.netEvResult is NetEvGate.GateResult.Pass)
    }

    @Test
    fun `unknown instrument uses the default price step in the synthetic snapshot`() {
        val netEvConfig =
            riskConfig().apply {
                netEvGateEnabled = true
                netEvBlockOnUnknown = true
            }
        val sim = sim(netEvConfig, netEvGate = NetEvGate(instruments(), netEvConfig))

        val result = runBlocking { check(sim, ticker = "UNKNOWN") }

        assertEquals("NET_EV_TOO_LOW", result.reason, "спеки нет — lotSize 1, priceStep 0.01")
    }

    // ======================================================== exposure/concentration

    @Test
    fun `net exposure limit blocks long and short entries`() {
        val cfg = riskConfig().apply { maxNetExposurePercent = 1.0 }
        val sim = sim(cfg)

        assertEquals("NET_EXPOSURE", runBlocking { check(sim) }.reason)
        assertEquals("NET_EXPOSURE", runBlocking { check(sim, signal = StrategyAction.SELL) }.reason)
    }

    @Test
    fun `directional concentration blocks when every position is on one side`() {
        val cfg =
            riskConfig().apply {
                portfolioRiskEnabled = true
                maxDirectionalConcentrationPercent = 60.0
            }
        val sim = sim(cfg)
        sim.recordOpen("TEST", PositionDirection.LONG, 1, BigDecimal("1000"), 10, at(0))
        sim.recordOpen("TEST2", PositionDirection.SHORT, 1, BigDecimal("1000"), 10, at(0))

        // Знак учитывается только у кандидата (упрощение стенда): SELL-кандидат
        // разбавляет нетто, LONG — делает концентрацию 100%.
        val balanced = runBlocking { check(sim, ticker = "TEST3", signal = StrategyAction.SELL) }
        assertTrue(balanced.allowed, "SELL-кандидат разбавляет нетто ($balanced)")

        // Третья сторона LONG ломает баланс → концентрация 100%.
        assertEquals(
            "DIRECTIONAL_CONCENTRATION",
            runBlocking { check(sim, ticker = "TEST3") }.reason,
        )
    }

    @Test
    fun `unknown tickers land in the UNKNOWN sector bucket`() {
        val cfg = riskConfig().apply { maxSectorExposure = 2 }
        val sim = sim(cfg)
        sim.recordOpen("OTHER", PositionDirection.LONG, 1, BigDecimal("100"), 10, at(0))
        sim.recordOpen("ANOTHER", PositionDirection.LONG, 1, BigDecimal("100"), 10, at(0))

        val result = runBlocking { check(sim, ticker = "TEST") }

        assertFalse(
            result.reason == "SECTOR_EXPOSURE",
            "TEST в секторе TECH — UNKNOWN-лимит его не касается ($result)",
        )
        assertEquals(
            "SECTOR_EXPOSURE",
            runBlocking { check(sim, ticker = "UNKNOWN") }.reason,
            "два UNKNOWN-тикера уже исчерпали сектор",
        )
    }

    // ========================================================= drawdown/rolling

    @Test
    fun `drawdown from peak blocks further entries`() {
        val sim = sim()
        // Закрытие на предыдущем дне: дневной P&L сбрасывается, equity падает вдвое.
        close(sim, "50", quantity = 10, dayOffset = -1, cash = BigDecimal("50000"))

        val result = runBlocking { check(sim) }

        assertEquals("DRAWDOWN_PROTECTION", result.reason)
        assertEquals(50.0, sim.currentDrawdownPercent(), 1e-9)
    }

    @Test
    fun `rolling seven day loss limit blocks`() {
        val cfg = riskConfig().apply { shadowModeEnabled = false }
        val sim = sim(cfg)
        close(sim, "50", quantity = 10, dayOffset = -1)
        close(sim, "50", quantity = 10, dayOffset = -2)

        assertEquals("DRAWDOWN_PROTECTION", runBlocking { check(sim) }.reason)
    }

    @Test
    fun `rolling thirty day loss limit blocks older losses`() {
        val cfg = riskConfig().apply { shadowModeEnabled = false }
        val sim = sim(cfg)
        close(sim, "50", quantity = 10, dayOffset = -10)
        close(sim, "50", quantity = 10, dayOffset = -15)
        close(sim, "50", quantity = 10, dayOffset = -20)

        assertEquals("DRAWDOWN_PROTECTION", runBlocking { check(sim) }.reason)
    }

    @Test
    fun `consecutive losses do not block when shadow mode is disabled`() {
        val cfg = riskConfig().apply { shadowModeEnabled = false }
        val sim = sim(cfg)
        close(sim, "99", dayOffset = -1)
        close(sim, "99", dayOffset = -2)
        close(sim, "99", dayOffset = -3)

        val result = runBlocking { check(sim) }
        assertFalse(
            result.reason == "DRAWDOWN_PROTECTION",
            "shadow-режим выключен — серия убытков не блокирует ($result)",
        )
        assertTrue(sim.maxConsecutiveLosses() >= 3)
    }

    @Test
    fun `drawdown gates are inert without an equity baseline`() {
        val sim = sim(initial = BigDecimal.ZERO)

        assertEquals(0.0, sim.currentDrawdownPercent(), 1e-9)
        assertTrue(runBlocking { check(sim) }.allowed, "peak equity 0 — drawdown не определён")
    }

    @Test
    fun `non positive cash is treated as a reached daily limit`() {
        val sim = sim()

        assertEquals("DAILY_LIMIT", runBlocking { check(sim, cash = BigDecimal.ZERO) }.reason)
    }

    @Test
    fun `absolute daily loss cap blocks before the percent limit`() {
        val cfg = riskConfig().apply { maxDailyLossRub = BigDecimal("100") }
        val sim = sim(cfg)
        close(sim, "99", quantity = 10)

        assertEquals("DAILY_LIMIT", runBlocking { check(sim) }.reason)
    }

    // ================================================================ misc edges

    @Test
    fun `non positive previous close skips the gap gate`() {
        val history = normalHistory().toMutableList()
        history[history.size - 2] = candle(at(-1, 9), "0", "0", "0", "0")

        val result = runBlocking { check(sim(), history = history) }

        assertFalse(result.reason == "DEGENERATE_GAP", "prevClose 0 — гэп не определён, гейт пропускается")
    }

    @Test
    fun `short trades are accounted with the short pnl sign`() {
        val sim = sim()
        close(sim, "99", direction = PositionDirection.SHORT, commission = "0.5")

        // (100 − 99) × lotSize 10 × qty 1 − комиссия 0.5 = 9.5
        assertEquals(0, BigDecimal("9.5").compareTo(sim.currentDailyPnl()))
        assertEquals(1, sim.closedTradeCount())
    }
}
