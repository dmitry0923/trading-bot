package com.trading.bot.client

import com.trading.bot.backtest.FrozenStrategy
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.service.LiveFrozenStrategyResolver
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
 *
 * P1-a: Risk-reducing ордера (close/SL/TP) проходят interlock при наличии
 * открытой позиции, даже если тикер revoked / build SHA сменился.
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
                resolver(frozenFor("SBER", "live-v2")),
            )
    }

    private fun resolver(
        frozen: FrozenStrategy? = null,
        reducingAllowed: Boolean = false,
    ): LiveFrozenStrategyResolver {
        val r = mock<LiveFrozenStrategyResolver>()
        whenever(r.resolveActive(any())).thenReturn(frozen)
        runBlocking {
            whenever(r.isOrderAllowed(any(), any())).thenAnswer { invocation ->
                val purpose = invocation.getArgument<OrderPurpose>(1)
                when (purpose) {
                    OrderPurpose.ENTRY -> frozen != null
                    else -> reducingAllowed
                }
            }
        }
        return r
    }

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
            val blocked =
                WsOrderTransport(
                    alorConfig,
                    tradingConfig,
                    SimpleMeterRegistry(),
                    fakeFactory,
                    scope,
                    resolver(null),
                )
            assertNull(blocked.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1"))
            assertEquals(0, fakeConnection.sent.count { it.contains("idem-1") })
        }

    @Test
    fun `placeConditional is blocked for unapproved ticker in live`() =
        runBlocking {
            val blocked =
                WsOrderTransport(
                    alorConfig,
                    tradingConfig,
                    SimpleMeterRegistry(),
                    fakeFactory,
                    scope,
                    resolver(null),
                )
            assertNull(blocked.placeConditional("stop", "SBER", "buy", 1, BigDecimal("250"), "idem-2", "P1"))
            assertEquals(0, fakeConnection.sent.count { it.contains("idem-2") })
        }

    @Test
    fun `placeLimit is blocked when approval service not ready`() =
        runBlocking {
            val blocked =
                WsOrderTransport(
                    alorConfig,
                    tradingConfig,
                    SimpleMeterRegistry(),
                    fakeFactory,
                    scope,
                    resolver(null),
                )
            assertNull(blocked.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-1", "P1"))
            assertEquals(0, fakeConnection.sent.count { it.contains("idem-1") })
        }

    @Test
    fun `cancel remains available without approval`() =
        runBlocking {
            val isolatedConnection = FakeConnection()
            val transport2 =
                WsOrderTransport(
                    alorConfig,
                    tradingConfig,
                    SimpleMeterRegistry(),
                    FakeSocketFactory(isolatedConnection),
                    scope,
                    resolver(null),
                )
            awaitFakeSubscribe(isolatedConnection)
            val result = async { transport2.cancel("ord-1", "idem-c", "P1") }
            awaitCommandContainingOn(isolatedConnection, "idem-c")
            isolatedConnection.inbound.send("""{"orderNumber":"ord-1","status":"cancelled"}""")
            assertEquals(CancelResult.CONFIRMED, result.await())
        }

    @Test
    fun `close placeLimit passes interlock when open position exists after revoke`() =
        runBlocking {
            // P1-a: тикер revoked (frozen = null -> entry denied), но риск-reducing
            // закрытие разрешено, т.к. для тикера есть открытая позиция.
            val isolatedConnection = FakeConnection()
            val closeTransport =
                WsOrderTransport(
                    alorConfig,
                    tradingConfig,
                    SimpleMeterRegistry(),
                    FakeSocketFactory(isolatedConnection),
                    scope,
                    resolver(frozen = null, reducingAllowed = true),
                )
            awaitFakeSubscribe(isolatedConnection)
            val result = async {
                closeTransport.placeLimit("SBER", "sell", 1, BigDecimal("250"), "idem-c1", "P1", OrderPurpose.CLOSE)
            }
            awaitCommandContainingOn(isolatedConnection, "idem-c1")
            isolatedConnection.inbound.send("""{"id":"idem-c1","orderNumber":"99991"}""")
            assertEquals("99991", result.await())
        }

    @Test
    fun `close placeLimit blocked after revoke without open position`() =
        runBlocking {
            val blocked =
                WsOrderTransport(
                    alorConfig,
                    tradingConfig,
                    SimpleMeterRegistry(),
                    fakeFactory,
                    scope,
                    resolver(frozen = null, reducingAllowed = false),
                )
            assertNull(
                blocked.placeLimit("SBER", "sell", 1, BigDecimal("250"), "idem-c2", "P1", OrderPurpose.CLOSE),
            )
            assertEquals(0, fakeConnection.sent.count { it.contains("idem-c2") })
        }

    @Test
    fun `entry placeLimit still blocked after revoke even with open position`() =
        runBlocking {
            // P1-a: наличие открытой позиции НЕ легитимирует новый entry.
            val blocked =
                WsOrderTransport(
                    alorConfig,
                    tradingConfig,
                    SimpleMeterRegistry(),
                    fakeFactory,
                    scope,
                    resolver(frozen = null, reducingAllowed = true),
                )
            assertNull(
                blocked.placeLimit("SBER", "buy", 1, BigDecimal("250"), "idem-e1", "P1", OrderPurpose.ENTRY),
            )
            assertEquals(0, fakeConnection.sent.count { it.contains("idem-e1") })
        }

    @Test
    fun `tp placeConditional blocked after revoke without open position`() =
        runBlocking {
            val blocked =
                WsOrderTransport(
                    alorConfig,
                    tradingConfig,
                    SimpleMeterRegistry(),
                    fakeFactory,
                    scope,
                    resolver(frozen = null, reducingAllowed = false),
                )
            assertNull(
                blocked.placeConditional("take-profit", "SBER", "sell", 1, BigDecimal("280"), "idem-tp1", "P1", OrderPurpose.TP),
            )
            assertEquals(0, fakeConnection.sent.count { it.contains("idem-tp1") })
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