package com.trading.bot.application.decision

import com.trading.bot.application.funding.FundingSnapshotService
import com.trading.bot.config.TradingConfig
import com.trading.bot.model.PositionDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Funding Veto Gate (research, дефолт off; docs/16): вето на вход по стороне, когда
 * per-clearing funding (руб/контракт/клиринг) дорог для удержания позиции.
 *   - LONG блокируется при funding > +порога (лонг платит);
 *   - SHORT блокируется при funding < −порога (шорт платит).
 * Отсутствие авторитетной ставки при включённом фильтре → BLOCK (fail-closed).
 */
class FundingVetoGateTest {
    private fun gate(
        enabled: Boolean = true,
        longThreshold: Double = 2.0,
        shortThreshold: Double = 2.0,
        blockOnUnknown: Boolean = true,
        latestForVeto: BigDecimal? = BigDecimal("2.79"),
    ): FundingVetoGate {
        val tradingConfig =
            TradingConfig().apply {
                fundingVetoEnabled = enabled
                fundingVetoLongThresholdRub = longThreshold
                fundingVetoShortThresholdRub = shortThreshold
                fundingVetoBlockOnUnknown = blockOnUnknown
            }
        val snapshotService = Mockito.mock(FundingSnapshotService::class.java)
        Mockito.`when`(snapshotService.latestForVeto("CNYRUBF")).thenReturn(latestForVeto)
        return FundingVetoGate(snapshotService, tradingConfig)
    }

    @Test
    fun `passes when disabled`() {
        assertTrue(gate(enabled = false).check("CNYRUBF", PositionDirection.LONG) is FundingVetoGate.VetoResult.Pass)
        assertTrue(gate(enabled = false).check("CNYRUBF", PositionDirection.SHORT) is FundingVetoGate.VetoResult.Pass)
    }

    @Test
    fun `blocks LONG when funding adds up for holding long`() {
        val result = gate().check("CNYRUBF", PositionDirection.LONG)
        assertTrue(result is FundingVetoGate.VetoResult.Blocked)
    }

    @Test
    fun `passes LONG when funding within threshold`() {
        val result = gate(latestForVeto = BigDecimal("1.0")).check("CNYRUBF", PositionDirection.LONG)
        assertTrue(result is FundingVetoGate.VetoResult.Pass)
    }

    @Test
    fun `blocks SHORT when funding strongly negative`() {
        val result = gate(latestForVeto = BigDecimal("-2.5")).check("CNYRUBF", PositionDirection.SHORT)
        assertTrue(result is FundingVetoGate.VetoResult.Blocked)
    }

    @Test
    fun `passes SHORT when negative funding within threshold`() {
        val result = gate(latestForVeto = BigDecimal("-1.5")).check("CNYRUBF", PositionDirection.SHORT)
        assertTrue(result is FundingVetoGate.VetoResult.Pass)
    }

    @Test
    fun `passes SHORT on positive funding and LONG on negative funding`() {
        assertTrue(gate(latestForVeto = BigDecimal("2.5")).check("CNYRUBF", PositionDirection.SHORT) is FundingVetoGate.VetoResult.Pass)
        assertTrue(gate(latestForVeto = BigDecimal("-2.5")).check("CNYRUBF", PositionDirection.LONG) is FundingVetoGate.VetoResult.Pass)
    }

    @Test
    fun `blocks on unknown funding when blockOnUnknown true`() {
        val result = gate(latestForVeto = null).check("CNYRUBF", PositionDirection.LONG)
        assertTrue(result is FundingVetoGate.VetoResult.Blocked)
        assertNull((result as FundingVetoGate.VetoResult.Blocked).fundingRub)
    }

    @Test
    fun `passes on unknown funding when blockOnUnknown false`() {
        val result = gate(latestForVeto = null, blockOnUnknown = false).check("CNYRUBF", PositionDirection.LONG)
        assertTrue(result is FundingVetoGate.VetoResult.Pass)
    }

    @Test
    fun `blocked carries funding value`() {
        val result = gate(latestForVeto = BigDecimal("3.5")).check("CNYRUBF", PositionDirection.LONG)
        assertTrue(result is FundingVetoGate.VetoResult.Blocked)
        val blocked = result as FundingVetoGate.VetoResult.Blocked
        assertEquals(0, BigDecimal("3.5").compareTo(blocked.fundingRub))
    }

    @Test
    fun `custom long threshold scales gate`() {
        // Ставка 2.5: порог по умолчанию 2.0 → BLOCK; порог 3.0 → PASS.
        assertTrue(gate(latestForVeto = BigDecimal("2.5")).check("CNYRUBF", PositionDirection.LONG) is FundingVetoGate.VetoResult.Blocked)
        val highThreshold = gate(longThreshold = 3.0, latestForVeto = BigDecimal("2.5"))
        assertTrue(highThreshold.check("CNYRUBF", PositionDirection.LONG) is FundingVetoGate.VetoResult.Pass)
    }
}
