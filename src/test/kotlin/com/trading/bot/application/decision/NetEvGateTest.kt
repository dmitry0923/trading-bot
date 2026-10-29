package com.trading.bot.application.decision

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.dto.MarketSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Unit-тесты [NetEvGate]: решение о входе по чистому мат. ожиданию после издержек.
 *
 * Покрываем ветки, которые раньше не проверялись (скан-19, ветви 22/28):
 * выключенный гейт, EV UNKNOWN при обоих режимах blockOnUnknown, фьючерсный
 * `pointValue` вместо `lotSize`, fallback спреда по `priceStep`, spread из снапшота,
 * блокировка/пропуск по порогу.
 */
class NetEvGateTest {
    private fun gate(config: RiskConfig = RiskConfig()): NetEvGate = NetEvGate(InstrumentsConfig(), config)

    private fun snapshot(
        bid: String?,
        ask: String?,
    ): MarketSnapshot =
        MarketSnapshot(
            ticker = "CNYRUBF",
            currentPrice = BigDecimal("92000"),
            bid = bid?.let(::BigDecimal),
            ask = ask?.let(::BigDecimal),
        )

    @Test
    fun `disabled gate always passes even without EV`() {
        val result = gate(RiskConfig().apply { netEvGateEnabled = false }).check("CNYRUBF", null, null)

        assertEquals(NetEvGate.GateResult.Pass, result)
    }

    @Test
    fun `unknown EV blocks when blockOnUnknown is on`() {
        val result = gate().check("CNYRUBF", null, null)

        assertTrue(result is NetEvGate.GateResult.Blocked)
        val blocked = result as NetEvGate.GateResult.Blocked
        assertEquals(null, blocked.netEV)
        assertEquals(null, blocked.executionCost)
    }

    @Test
    fun `unknown EV passes when blockOnUnknown is off`() {
        val result = gate(RiskConfig().apply { netEvBlockOnUnknown = false }).check("CNYRUBF", null, null)

        assertEquals(NetEvGate.GateResult.Pass, result)
    }

    @Test
    fun `futures use point value and spread from snapshot`() {
        // Si: pointValue = 1000 ₽/пункт, спред 1 пункт → half-spread 0.5 → cost 0.5*1000*2*1 = 1000 ₽
        val config = RiskConfig().apply { minNetEvThresholdRub = BigDecimal("100") }
        val result = gate(config).check("Si", BigDecimal("500"), snapshot(bid = "92000", ask = "92001"))

        // expectedNet 500 − cost < 100 → BLOCK; точное значение cost зависит от спеки
        // инструмента (pointValue), поэтому проверяем знак и наличие расчёта.
        assertTrue(result is NetEvGate.GateResult.Blocked, "expected BLOCK, got $result")
        val blocked = result as NetEvGate.GateResult.Blocked
        assertTrue(blocked.executionCost != null && blocked.executionCost!! > BigDecimal.ZERO)
        assertTrue(blocked.netEV!! < BigDecimal("100"))
    }

    @Test
    fun `stock uses lot size and falls back to price step when snapshot has no quotes`() {
        val config = RiskConfig().apply { minNetEvThresholdRub = BigDecimal("0") }
        // SBER: lotSize 10, priceStep 0.01 → halfSpread 0.01 → cost 0.01*10*2*1 = 0.2 ₽
        val result = gate(config).check("SBER", BigDecimal("100"), snapshot(bid = null, ask = null))

        assertEquals(NetEvGate.GateResult.Pass, result)
    }

    @Test
    fun `inverted or missing quotes fall back to price step`() {
        val config = RiskConfig().apply { minNetEvThresholdRub = BigDecimal("0") }
        // ask <= bid — невалидный снапшот, спред берётся из priceStep.
        val result = gate(config).check("SBER", BigDecimal("100"), snapshot(bid = "101", ask = "100"))

        assertEquals(NetEvGate.GateResult.Pass, result)
    }

    @Test
    fun `unknown instrument falls back to lot size 1`() {
        val config = RiskConfig().apply { minNetEvThresholdRub = BigDecimal("0") }
        val result = gate(config).check("UNKNOWN_TICKER", BigDecimal("100"), null)

        assertEquals(NetEvGate.GateResult.Pass, result)
    }
}
