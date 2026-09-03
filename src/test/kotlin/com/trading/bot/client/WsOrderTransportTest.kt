package com.trading.bot.client

import com.trading.bot.backtest.FrozenStrategy
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.repository.DeploymentApprovalRepository
import com.trading.bot.service.DeploymentApprovalService
import com.trading.bot.service.FrozenStrategyStore
import com.trading.bot.service.LiveStrategyFingerprintProvider
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit-тесты WS-транспорта ордеров ([WsOrderTransport]) на фейковом сокете:
 * команда/ответ коррелируются по idempotency key и guid; таймаут ответа →
 * [OrderDeliveryUncertainException]; недоступность канала ДО отправки →
 * [OrderTransportUnavailableException]; обрыв соединения → pending UNCERTAIN.
 */
class WsOrderTransportTest {
    private lateinit var alorConfig: AlorConfig
    private lateinit var tradingConfig: TradingConfig
    private lateinit var fakeConnection: FakeConnection
    private lateinit var fakeFactory: FakeSocketFactory
    private lateinit var transport: WsOrderTransport
    private lateinit var scope: CoroutineScope

    private class FakeConnection : WsOrderSocketConnection {
        val inbound = Channel<String>(capacity = 100)
        val sent = CopyOnWriteArrayList<String>()
        var failSends = false

        override val messages: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            if (failSends) throw java.io.IOException("socket closed")
            sent += text
        }

        override suspend fun close() = Unit
    }

    private class FakeSocketFactory(
        val connection: FakeConnection,
    ) : WsOrderSocketFactory {
        override suspend fun open(): WsOrderSocketConnection = connection
    }

    @BeforeEach
    fun setUp() {
        alorConfig =
            AlorConfig().apply {
                wsOrdersEnabled = true
                wsOrderTimeoutMs = 5_000
                portfolio = "P1"
                exchange = "MOEX"
                token = "tok"
            }
        tradingConfig = TradingConfig().apply { mode = "LIVE" }
        fakeConnection = FakeConnection()
        fakeFactory = FakeSocketFactory(fakeConnection)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        transport =
            WsOrderTransport(
                alorConfig,
                tradingConfig,
                SimpleMeterRegistry(),
                fakeFactory,
                scope,
                approvedService(),
                fingerprintProvider(),
                frozenStrategyStore(),
            )
    }

    private fun approvedService(): DeploymentApprovalService {
        val approval = mock<DeploymentApprovalService>()
        whenever(approval.isLiveAllowed(any(), any())).thenReturn(true)
        return approval
    }

    private fun fingerprintProvider(): LiveStrategyFingerprintProvider {
        val fp = mock<LiveStrategyFingerprintProvider>()
        whenever(fp.fingerprint(any())).thenReturn("fp")
        return fp
    }

    private fun frozenStrategyStore(): FrozenStrategyStore {
        val store = mock<FrozenStrategyStore>()
        whenever(store.current(any())).thenReturn(
            FrozenStrategy(
                ticker = "SBER",
                strategyVersion = "live-v2",
                gitCommitSha = null,
                slPercent = 2.0,
                tpPercent = 15.0,
                slPoints = null,
                tpPoints = null,
                confidenceThreshold = 0.6,
                leverage = 1.0,
                riskPerTradePercent = null,
                futuresMaxContractsPerPosition = null,
            ),
        )
        return store
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private suspend inline fun <reified T : Throwable> assertFailsWith(block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail<T>("Expected ${T::class.simpleName} but no exception was thrown")
        error("unreachable")
    }

    private suspend fun awaitSubscribe() {
        withTimeout(5_000) {
            while (fakeConnection.sent.isEmpty()) delay(10)
        }
    }

    private suspend fun awaitCommandContaining(text: String) {
        withTimeout(5_000) {
            while (fakeConnection.sent.none { it.contains(text) }) delay(10)
        }
    }

    @Test
    fun `placeLimit returns orderNumber on confirmed event`() =
        runBlocking {
            awaitSubscribe()
            val result = async { transport.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1") }
            awaitCommandContaining("idem-1")
            fakeConnection.inbound.send("""{"id":"idem-1","orderNumber":"12345"}""")
            assertEquals("12345", result.await())
        }

    @Test
    fun `placeLimit returns null on rejected event`() =
        runBlocking {
            awaitSubscribe()
            val result = async { transport.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1") }
            awaitCommandContaining("idem-1")
            fakeConnection.inbound.send("""{"id":"idem-1","status":"rejected","error":"not enough money"}""")
            assertNull(result.await())
        }

    @Test
    fun `placeLimit throws OrderDeliveryUncertainException on timeout`() =
        runBlocking {
            alorConfig.wsOrderTimeoutMs = 50
            awaitSubscribe()
            assertFailsWith<OrderDeliveryUncertainException> {
                transport.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1")
            }
        }

    @Test
    fun `placeLimit throws OrderDeliveryUncertainException on send failure`() =
        runBlocking {
            awaitSubscribe()
            fakeConnection.failSends = true
            assertFailsWith<OrderDeliveryUncertainException> {
                transport.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1")
            }
        }

    @Test
    fun `placeLimit throws OrderTransportUnavailableException when disabled`() =
        runBlocking {
            alorConfig.wsOrdersEnabled = false
            assertFailsWith<OrderTransportUnavailableException> {
                transport.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1")
            }
        }

    @Test
    fun `placeLimit throws OrderTransportUnavailableException for non-default portfolio`() =
        runBlocking {
            awaitSubscribe()
            assertFailsWith<OrderTransportUnavailableException> {
                transport.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P2")
            }
        }

    @Test
    fun `placeLimit throws OrderTransportUnavailableException when not live`() =
        runBlocking {
            tradingConfig.mode = "SIMULATION"
            assertFailsWith<OrderTransportUnavailableException> {
                transport.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1")
            }
        }

    @Test
    fun `cancel returns CONFIRMED on cancelled event`() =
        runBlocking {
            awaitSubscribe()
            val result = async { transport.cancel("ord-1", "idem-c", "P1") }
            awaitCommandContaining("idem-c")
            fakeConnection.inbound.send("""{"orderNumber":"ord-1","status":"cancelled"}""")
            assertEquals(CancelResult.CONFIRMED, result.await())
        }

    @Test
    fun `cancel returns UNCERTAIN on timeout`() =
        runBlocking {
            alorConfig.wsOrderTimeoutMs = 50
            awaitSubscribe()
            val result = transport.cancel("ord-1", "idem-c", "P1")
            assertEquals(CancelResult.UNCERTAIN, result)
        }

    @Test
    fun `disconnect fails pending place as OrderDeliveryUncertainException`() =
        runBlocking {
            awaitSubscribe()
            val place = async { transport.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1") }
            awaitCommandContaining("idem-1")
            fakeConnection.inbound.close()
            assertFailsWith<OrderDeliveryUncertainException> {
                place.await()
            }
        }

    @Test
    fun `placeLimit is blocked for unapproved ticker in live`() =
        runBlocking {
            val approval = mock<DeploymentApprovalService>()
            whenever(approval.isLiveAllowed(any(), any())).thenReturn(false)
            val blocked =
                WsOrderTransport(
                    alorConfig,
                    tradingConfig,
                    SimpleMeterRegistry(),
                    fakeFactory,
                    scope,
                    approval,
                    fingerprintProvider(),
                    frozenStrategyStore(),
                )
            assertNull(blocked.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1"))
            assertEquals(0, fakeConnection.sent.count { it.contains("idem-1") })
        }

    @Test
    fun `placeConditional is blocked for unapproved ticker in live`() =
        runBlocking {
            val approval = mock<DeploymentApprovalService>()
            whenever(approval.isLiveAllowed(any(), any())).thenReturn(false)
            val blocked =
                WsOrderTransport(
                    alorConfig,
                    tradingConfig,
                    SimpleMeterRegistry(),
                    fakeFactory,
                    scope,
                    approval,
                    fingerprintProvider(),
                    frozenStrategyStore(),
                )
            assertNull(blocked.placeConditional("stop", "SBER", "buy", 1, BigDecimal("250"), "idem-2", "P1"))
            assertEquals(0, fakeConnection.sent.count { it.contains("idem-2") })
        }

    @Test
    fun `placeLimit is blocked when approval service not ready`() =
        runBlocking {
            val notReady = DeploymentApprovalService(mock<DeploymentApprovalRepository>())
            val blocked =
                WsOrderTransport(
                    alorConfig,
                    tradingConfig,
                    SimpleMeterRegistry(),
                    fakeFactory,
                    scope,
                    notReady,
                    fingerprintProvider(),
                    frozenStrategyStore(),
                )
            assertNull(blocked.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1"))
            assertEquals(0, fakeConnection.sent.count { it.contains("idem-1") })
        }

    @Test
    fun `cancel remains available without approval`() =
        runBlocking {
            val approval = mock<DeploymentApprovalService>()
            whenever(approval.isLiveAllowed(any(), any())).thenReturn(false)
            val isolatedConnection = FakeConnection()
            val transport2 =
                WsOrderTransport(
                    alorConfig,
                    tradingConfig,
                    SimpleMeterRegistry(),
                    FakeSocketFactory(isolatedConnection),
                    scope,
                    approval,
                    fingerprintProvider(),
                    frozenStrategyStore(),
                )
            awaitFakeSubscribe(isolatedConnection)
            val result = async { transport2.cancel("ord-1", "idem-c", "P1") }
            awaitCommandContainingOn(isolatedConnection, "idem-c")
            isolatedConnection.inbound.send("""{"orderNumber":"ord-1","status":"cancelled"}""")
            assertEquals(CancelResult.CONFIRMED, result.await())
        }

    private suspend fun awaitFakeSubscribe(connection: FakeConnection) {
        withTimeout(5_000) {
            while (connection.sent.isEmpty()) delay(10)
        }
    }

    private suspend fun awaitCommandContainingOn(
        connection: FakeConnection,
        text: String,
    ) {
        withTimeout(5_000) {
            while (connection.sent.none { it.contains(text) }) delay(10)
        }
    }
}
