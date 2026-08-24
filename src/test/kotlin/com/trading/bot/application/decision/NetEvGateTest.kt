package com.trading.bot.application.decision

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.dto.MarketSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * NET EV Gate (P0 audit): блокирует сделки с отрицательным или слишком низким
 * математическим ожиданием после учёта реальной стоимости исполнения.
 *
 * Формула:
 *   netEV = expectedNetPerLot − halfSpread × lotSize × 2 × adverseSelectionMultiplier
 *
 * Тесты проверяют:
 * - gate PASS при выключенной конфигурации
 * - gate PASS при недостатке статистики (null expectedNet)
 * - gate BLOCKED при отрицательном NET EV (ожидание убытка)
 * - gate PASS при положительном NET EV (ожидание прибыли)
 * - корректный расчёт spread cost из snapshot bid/ask
 * - fallback spread из priceStep при отсутствии bid/ask
 */
class NetEvGateTest {
    private val instrumentsConfig = InstrumentsConfig()
    private val riskConfig = RiskConfig()

    private fun gate(
        netEvBlockOnUnknown: Boolean = riskConfig.netEvBlockOnUnknown,
    ): NetEvGate {
        val config = RiskConfig().apply {
            netEvGateEnabled = riskConfig.netEvGateEnabled
            minNetEvThresholdRub = riskConfig.minNetEvThresholdRub
            netEvAdverseSelectionMultiplier = riskConfig.netEvAdverseSelectionMultiplier
            this.netEvBlockOnUnknown = netEvBlockOnUnknown
        }
        return NetEvGate(instrumentsConfig, config)
    }

    @Test
    fun `gate passes when disabled`() {
        val config = RiskConfig().apply { netEvGateEnabled = false }
        val result = NetEvGate(instrumentsConfig, config).check("SBER", BigDecimal("5"), null)
        assertTrue(result is NetEvGate.GateResult.Pass)
    }

    @Test
    fun `gate blocks when insufficient historical data and blockOnUnknown is true`() {
        val result = gate().check("SBER", null, null)
        assertTrue(result is NetEvGate.GateResult.Blocked)
    }

    @Test
    fun `gate passes when insufficient historical data and blockOnUnknown is false`() {
        val result = gate(netEvBlockOnUnknown = false).check("SBER", null, null)
        assertTrue(result is NetEvGate.GateResult.Pass)
    }

    @Test
    fun `gate blocks when expected net is below threshold after spread cost`() {
        // expectedNet = 5 RUB per lot; executionCost will exceed that → blocked
        val snapshot = MarketSnapshot(
            ticker = "SBER",
            currentPrice = BigDecimal("250"),
            bid = BigDecimal("249.90"),
            ask = BigDecimal("250.10"),
        )
        // halfSpread = (250.10 - 249.90) / 2 = 0.10
        // SBER lotSize = 10
        // executionCost = 0.10 × 10 × 2 × 1.5 = 3.00
        // netEV = 5 - 3 = 2.00 < 10 → BLOCKED
        val result = gate().check("SBER", BigDecimal("5"), snapshot)
        assertTrue(result is NetEvGate.GateResult.Blocked)
        val blocked = result as NetEvGate.GateResult.Blocked
        assertEquals(0, BigDecimal("2").compareTo(blocked.netEV!!.setScale(0, java.math.RoundingMode.HALF_UP)))
    }

    @Test
    fun `gate passes when expected net exceeds threshold after spread cost`() {
        // expectedNet = 50 RUB → much higher than execution cost
        val snapshot = MarketSnapshot(
            ticker = "LKOH",
            currentPrice = BigDecimal("7000"),
            bid = BigDecimal("6998"),
            ask = BigDecimal("7002"),
        )
        // halfSpread = (7002 - 6998) / 2 = 2.0
        // LKOH lotSize = 1
        // executionCost = 2.0 × 1 × 2 × 1.5 = 6.00
        // netEV = 50 - 6 = 44 > 10 → PASS
        val result = gate().check("LKOH", BigDecimal("50"), snapshot)
        assertTrue(result is NetEvGate.GateResult.Pass)
    }

    @Test
    fun `execution cost uses current half spread from bid ask`() {
        val snapshot = MarketSnapshot(
            ticker = "SBER",
            currentPrice = BigDecimal("250"),
            bid = BigDecimal("249"),
            ask = BigDecimal("251"),
        )
        // halfSpread = 1.0, lotSize = 10
        // executionCost = 1.0 × 10 × 2 × 1.5 = 30
        // netEV = 100 - 30 = 70 > 10 → PASS
        val result = gate().check("SBER", BigDecimal("100"), snapshot)
        assertTrue(result is NetEvGate.GateResult.Pass)
    }

    @Test
    fun `execution cost falls back to priceStep when no bid ask`() {
        // No bid/ask → fallback to SBER priceStep = 0.01
        // halfSpread = 0.01, lotSize = 10
        // executionCost = 0.01 × 10 × 2 × 1.5 = 0.30
        // netEV = 1 - 0.30 = 0.70 < 10 → BLOCKED
        val result = gate().check("SBER", BigDecimal("1"), null)
        assertTrue(result is NetEvGate.GateResult.Blocked)
    }

    @Test
    fun `wide spread can push positive expected net below threshold`() {
        // expectedNet = 12 RUB — just barely above threshold of 10
        // Wide spread: 1% of 250 = 2.5 per side
        val snapshot = MarketSnapshot(
            ticker = "SBER",
            currentPrice = BigDecimal("250"),
            bid = BigDecimal("247.50"),
            ask = BigDecimal("252.50"),
        )
        // halfSpread = 2.5, lotSize = 10
        // executionCost = 2.5 × 10 × 2 × 1.5 = 75
        // netEV = 12 - 75 = -63 → BLOCKED
        val result = gate().check("SBER", BigDecimal("12"), snapshot)
        assertTrue(result is NetEvGate.GateResult.Blocked)
    }

    @Test
    fun `blocked result contains expectedNet and executionCost`() {
        val snapshot = MarketSnapshot(
            ticker = "SBER",
            currentPrice = BigDecimal("250"),
            bid = BigDecimal("249.90"),
            ask = BigDecimal("250.10"),
        )
        val result = gate().check("SBER", BigDecimal("5"), snapshot)
        assertTrue(result is NetEvGate.GateResult.Blocked)
        val blocked = result as NetEvGate.GateResult.Blocked
        assertEquals(0, BigDecimal("5").compareTo(blocked.expectedNet!!))
        assertTrue(blocked.executionCost!! > BigDecimal.ZERO)
    }

    @Test
    fun `custom threshold changes gate behavior`() {
        val snapshot = MarketSnapshot(
            ticker = "SBER",
            currentPrice = BigDecimal("250"),
            bid = BigDecimal("249.90"),
            ask = BigDecimal("250.10"),
        )
        // With default threshold (10): netEV = 2 < 10 → BLOCKED
        val blocked = gate().check("SBER", BigDecimal("5"), snapshot)
        assertTrue(blocked is NetEvGate.GateResult.Blocked)

        // With lower threshold (1): netEV = 2 >= 1 → PASS
        val lowThresholdConfig = RiskConfig().apply {
            minNetEvThresholdRub = BigDecimal("1")
            netEvBlockOnUnknown = riskConfig.netEvBlockOnUnknown
        }
        val pass = NetEvGate(instrumentsConfig, lowThresholdConfig)
            .check("SBER", BigDecimal("5"), snapshot)
        assertTrue(pass is NetEvGate.GateResult.Pass)
    }
}
