package com.trading.bot.service

import com.trading.bot.backtest.FrozenStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
            )
        assertNull(resolver.resolveActive("SBER"))
    }

    private fun resolver(
        buildSha: String?,
        frozenSha: String?,
        approved: Boolean,
    ): LiveFrozenStrategyResolver {
        val store = mock<FrozenStrategyStore>()
        whenever(store.current(any())).thenReturn(frozen(frozenSha))
        val fingerprint = mock<LiveStrategyFingerprintProvider>()
        whenever(fingerprint.fingerprint(any())).thenReturn("fp")
        val approval = mock<DeploymentApprovalService>()
        whenever(approval.isLiveAllowed(any(), any())).thenReturn(approved)
        return LiveFrozenStrategyResolver(store, approval, fingerprint, buildIdentity(buildSha))
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
