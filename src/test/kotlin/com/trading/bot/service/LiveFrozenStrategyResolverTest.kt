package com.trading.bot.service

import com.trading.bot.backtest.FrozenStrategy
import com.trading.bot.client.OrderPurpose
import com.trading.bot.repository.PositionRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit-тесты [LiveFrozenStrategyResolver] — P1: build-identity interlock на
 * исполнительной границе. Approval, сохранённый под сборкой A, НЕ должен
 * легитимизировать исполнение из сборки B: резолвер сравнивает
 * [FrozenStrategy.gitCommitSha] с SHA текущего процесса ([BuildIdentity]).
 * Fail-closed: отсутствие build SHA у текущего процесса => [null] (DENY).
 *
 * P1-a: разделение по назначению ордера. Risk-INCREASING entry требует approved;
 * risk-reducing (close/SL/TP) разрешены при наличии открытой позиции.
 */
class LiveFrozenStrategyResolverTest {
    @Test
    fun `approval from build A plus runtime build B blocks order`() {
        val resolver = resolver(buildSha = "buildB", frozenSha = "buildA", approved = true)
        assertNull(resolver.resolveActive("SBER"))
    }

    @Test
    fun `matching build sha and approval returns frozen`() {
        val resolver = resolver(buildSha = "buildA", frozenSha = "buildA", approved = true)
        assertEquals(frozen("buildA"), resolver.resolveActive("SBER"))
    }

    @Test
    fun `unavailable current build sha is fail-closed`() {
        val resolver = resolver(buildSha = null, frozenSha = "buildA", approved = true)
        assertNull(resolver.resolveActive("SBER"))
    }

    @Test
    fun `not approved ticker returns null`() {
        val resolver = resolver(buildSha = "buildA", frozenSha = "buildA", approved = false)
        assertNull(resolver.resolveActive("SBER"))
    }

    @Test
    fun `no frozen strategy returns null`() {
        val store = mock<FrozenStrategyStore>()
        whenever(store.current(any())).thenReturn(null)
        val resolver =
            LiveFrozenStrategyResolver(
                store,
                mock(),
                mock(),
                buildIdentity("buildA"),
                mock(),
            )
        assertNull(resolver.resolveActive("SBER"))
    }

    // --- P1-a: entry vs reducing order interlock ---

    @Test
    fun `entry blocked when not approved`() {
        val resolver = resolver(buildSha = "buildA", frozenSha = "buildA", approved = false, posOpen = true)
        assertFalse(runBlocking { resolver.isOrderAllowed("SBER", OrderPurpose.ENTRY) })
    }

    @Test
    fun `entry blocked when build sha mismatch`() {
        val resolver = resolver(buildSha = "buildB", frozenSha = "buildA", approved = true, posOpen = true)
        assertFalse(runBlocking { resolver.isOrderAllowed("SBER", OrderPurpose.ENTRY) })
    }

    @Test
    fun `entry allowed when fully approved`() {
        val resolver = resolver(buildSha = "buildA", frozenSha = "buildA", approved = true, posOpen = false)
        assertTrue(runBlocking { resolver.isOrderAllowed("SBER", OrderPurpose.ENTRY) })
    }

    @Test
    fun `entry blocked even with open position when not approved`() {
        val resolver = resolver(buildSha = "buildA", frozenSha = "buildA", approved = false, posOpen = true)
        assertFalse(runBlocking { resolver.isOrderAllowed("SBER", OrderPurpose.ENTRY) })
    }

    @Test
    fun `reducing order allowed when approved`() {
        val resolver = resolver(buildSha = "buildA", frozenSha = "buildA", approved = true, posOpen = false)
        assertTrue(runBlocking { resolver.isOrderAllowed("SBER", OrderPurpose.CLOSE) })
    }

    @Test
    fun `reducing order allowed when open position exists without approval`() {
        val resolver = resolver(buildSha = "buildA", frozenSha = "buildA", approved = false, posOpen = true)
        assertTrue(runBlocking { resolver.isOrderAllowed("SBER", OrderPurpose.SL) })
    }

    @Test
    fun `reducing order allowed when approved and open position`() {
        val resolver = resolver(buildSha = "buildA", frozenSha = "buildA", approved = true, posOpen = true)
        assertTrue(runBlocking { resolver.isOrderAllowed("SBER", OrderPurpose.TP) })
    }

    @Test
    fun `reducing order blocked without approval and no open position`() {
        val resolver = resolver(buildSha = "buildA", frozenSha = "buildA", approved = false, posOpen = false)
        assertFalse(runBlocking { resolver.isOrderAllowed("SBER", OrderPurpose.CLOSE) })
    }

    @Test
    fun `reducing order blocked when position repo throws`() {
        val resolver = resolver(buildSha = "buildA", frozenSha = "buildA", approved = false, posException = true)
        assertFalse(runBlocking { resolver.isOrderAllowed("SBER", OrderPurpose.SL) })
    }

    @Test
    fun `reducing order blocked when no frozen and position repo fails`() {
        val resolver = resolver(buildSha = "buildA", frozenSha = null, approved = false, posException = true)
        assertFalse(runBlocking { resolver.isOrderAllowed("SBER", OrderPurpose.TP) })
    }

    private fun resolver(
        buildSha: String?,
        frozenSha: String?,
        approved: Boolean,
        posOpen: Boolean = false,
        posException: Boolean = false,
    ): LiveFrozenStrategyResolver {
        val store = mock<FrozenStrategyStore>()
        whenever(store.current(any())).thenReturn(frozen(frozenSha))
        val fingerprint = mock<LiveStrategyFingerprintProvider>()
        whenever(fingerprint.fingerprint(any())).thenReturn("fp")
        val approval = mock<DeploymentApprovalService>()
        whenever(approval.isLiveAllowed(any(), any())).thenReturn(approved)
        val positionRepo = mock<PositionRepository>()
        if (posException) {
            runBlocking { whenever(positionRepo.hasOpenPosition(any())).thenThrow(RuntimeException("DB unavailable")) }
        } else {
            runBlocking { whenever(positionRepo.hasOpenPosition(any())).thenReturn(posOpen) }
        }
        return LiveFrozenStrategyResolver(store, approval, fingerprint, buildIdentity(buildSha), positionRepo)
    }

    private fun buildIdentity(sha: String?): BuildIdentity {
        val identity = mock<BuildIdentity>()
        whenever(identity.gitCommitSha()).thenReturn(sha)
        return identity
    }

    private fun frozen(sha: String?): FrozenStrategy =
        FrozenStrategy(
            ticker = "SBER",
            strategyVersion = "live-v2",
            gitCommitSha = sha,
            slPercent = 2.0,
            tpPercent = 15.0,
            slPoints = null,
            tpPoints = null,
            confidenceThreshold = 0.6,
            leverage = 1.0,
            riskPerTradePercent = null,
            futuresMaxContractsPerPosition = null,
        )
}
