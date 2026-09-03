package com.trading.bot.client

import com.trading.bot.backtest.FrozenStrategy
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.repository.DeploymentApprovalRepository
import com.trading.bot.repository.FrozenStrategyRepository
import com.trading.bot.service.BuildIdentity
import com.trading.bot.service.DeploymentApprovalService
import com.trading.bot.service.FrozenStrategyStore
import com.trading.bot.service.LiveStrategyFingerprintProvider
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal

/**
 * Unit-тесты REST-транспорта ([RestOrderTransport]) в не-LIVE режиме:
 * имитация исполнения (sim-*), отмена подтверждается. HTTP-ветки (4xx/reject,
 * UNCERTAIN) семантически идентичны прежним телам [AlorClient] и покрываются
 * интеграционными тестами исполнения. LIVE-interlock тесты — ниже.
 */
class RestOrderTransportTest {
    private val tradingConfig = TradingConfig().apply { mode = "SIMULATION" }
    private val alorConfig = AlorConfig().apply { portfolio = "P1" }
    private val transport =
        RestOrderTransport(
            tradingConfig,
            alorConfig,
            jacksonObjectMapper(),
            SimpleMeterRegistry(),
            AlorTokenProvider(alorConfig, jacksonObjectMapper()),
            RetryRegistry.ofDefaults(),
            RateLimiterRegistry.ofDefaults(),
            CircuitBreakerRegistry.ofDefaults(),
            mock(),
            mockFingerprints(),
            mockStore(),
        )

    @Test
    fun `placeLimit simulates order in non-live`() =
        runBlocking {
            val result = transport.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1")
            assertEquals("sim-SBER-idem-1", result)
        }

    @Test
    fun `placeConditional simulates order in non-live`() =
        runBlocking {
            val result = transport.placeConditional("stop", "SBER", "buy", 1, BigDecimal("250"), "idem-2", "P1")
            assertEquals("sim-stop-SBER-idem-2", result)
        }

    @Test
    fun `cancel is confirmed in non-live`() =
        runBlocking {
            val result = transport.cancel("ord-1", "idem-3", "P1")
            assertEquals(CancelResult.CONFIRMED, result)
        }

    @Test
    fun `live placeLimit is blocked for unapproved ticker`() =
        runBlocking {
            val approval = mock<DeploymentApprovalService>()
            whenever(approval.isLiveAllowed(any(), any())).thenReturn(false)
            val live = liveTransport(approval)
            assertNull(live.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1"))
        }

    @Test
    fun `live placeConditional is blocked for unapproved ticker`() =
        runBlocking {
            val approval = mock<DeploymentApprovalService>()
            whenever(approval.isLiveAllowed(any(), any())).thenReturn(false)
            val live = liveTransport(approval)
            assertNull(live.placeConditional("stop", "SBER", "buy", 1, BigDecimal("250"), "idem-2", "P1"))
        }

    @Test
    fun `live placeLimit is blocked when approval service not ready`() =
        runBlocking {
            val notReady = DeploymentApprovalService(mock<DeploymentApprovalRepository>())
            val live = liveTransport(notReady, mockFingerprints())
            assertNull(live.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1"))
        }

    @Test
    fun `live placeLimit is blocked when frozen strategy changed after approval (fingerprint mismatch)`() =
        runBlocking {
            val provider = LiveStrategyFingerprintProvider(BuildIdentity())
            val approval = DeploymentApprovalService(mock())
            approval.init()
            val frozen = frozenFor("SBER", "live-v2")
            val store = mock<FrozenStrategyStore>()
            whenever(store.current("SBER")).thenReturn(frozen)
            approval.approve("SBER", DeploymentApprovalService.LIVE_ALLOWED, 0.6, provider.fingerprint(frozen))

            // Замороженная стратегия изменилась (новая версия/параметры) => fingerprint
            // сменился => вход БЛОКИРОВАН (frozen-driven interlock, P1-аудит).
            whenever(store.current("SBER")).thenReturn(frozenFor("SBER", "live-v3"))
            val changed = liveTransport(approval, provider, store)
            assertNull(changed.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-2", "P1"))
        }

    private fun liveTransport(
        approval: DeploymentApprovalService,
        fingerprints: LiveStrategyFingerprintProvider,
        store: FrozenStrategyStore = mockStore(),
    ): RestOrderTransport {
        val liveConfig = TradingConfig().apply { mode = "LIVE" }
        return RestOrderTransport(
            liveConfig,
            alorConfig,
            jacksonObjectMapper(),
            SimpleMeterRegistry(),
            AlorTokenProvider(alorConfig, jacksonObjectMapper()),
            RetryRegistry.ofDefaults(),
            RateLimiterRegistry.ofDefaults(),
            CircuitBreakerRegistry.ofDefaults(),
            approval,
            fingerprints,
            store,
        )
    }

    private fun liveTransport(approval: DeploymentApprovalService) = liveTransport(approval, mockFingerprints())

    private fun mockFingerprints(): LiveStrategyFingerprintProvider = mock<LiveStrategyFingerprintProvider>()

    private fun mockStore(): FrozenStrategyStore = FrozenStrategyStore(mock<FrozenStrategyRepository>())

    private fun frozenFor(
        ticker: String,
        version: String,
    ): FrozenStrategy =
        FrozenStrategy(
            ticker = ticker,
            strategyVersion = version,
            gitCommitSha = null,
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
