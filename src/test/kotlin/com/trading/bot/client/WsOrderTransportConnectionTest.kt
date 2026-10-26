package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.service.LiveFrozenStrategyResolver
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Дополнительные ветки [WsOrderTransport], не покрытые [WsOrderTransportTest]:
 * жизненный цикл connection-loop (подписка `OrdersGetAndSubscribeV2` при каждом
 * open и переподключение после завершения потока), успешная доставка условной
 * заявки и пропуск несопоставимых сообщений без потери корреляции.
 */
class WsOrderTransportConnectionTest {
    private lateinit var alorConfig: AlorConfig
    private lateinit var tradingConfig: TradingConfig
    private lateinit var connection: FakeConnection
    private lateinit var factory: FakeSocketFactory
    private lateinit var scope: CoroutineScope
    private lateinit var transport: WsOrderTransport

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
        tradingConfig =
            TradingConfig().apply {
                mode = "LIVE"
                // WS-транспорт теперь проверяет LIVE-allowlist (второй барьер LIVE-guard).
                liveTickersAllowlist = listOf("SBER")
            }
        connection = FakeConnection()
        factory = FakeSocketFactory(connection)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        transport =
            WsOrderTransport(
                alorConfig,
                tradingConfig,
                SimpleMeterRegistry(),
                factory,
                scope,
                resolver(),
                tokenProvider(),
            )
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `connection loop subscribes on every open and reconnects after stream end`() =
        runBlocking {
            connection.closeOnSubscribe = true
            // Пересоздаём транспорт, чтобы флаг закрытия потока действовал с первого open.
            transport =
                WsOrderTransport(
                    alorConfig,
                    tradingConfig,
                    SimpleMeterRegistry(),
                    factory,
                    scope,
                    resolver(),
                    tokenProvider(),
                )
            val deadline = System.currentTimeMillis() + 5_000
            while (factory.openCount.get() < 2 && System.currentTimeMillis() < deadline) {
                delay(20)
            }

            assertTrue(factory.openCount.get() >= 2, "expected reconnect, opens=${factory.openCount.get()}")
            // Подписка отправляется уже после открытия соединения — ждём её, а не
            // проверяем сразу (иначе гонка: openCount уже 2, subscribe ещё 1).
            val subscribeDeadline = System.currentTimeMillis() + 5_000
            var subscribes = emptyList<String>()
            while (System.currentTimeMillis() < subscribeDeadline) {
                subscribes =
                    connection.sent.filter {
                        jacksonObjectMapper().readTree(it).path("opcode").asString() == "OrdersGetAndSubscribeV2"
                    }
                if (subscribes.size >= 2) break
                delay(20)
            }
            assertTrue(subscribes.size >= 2, "every open must send a subscribe command, got=${connection.sent}")
            val first = jacksonObjectMapper().readTree(subscribes.first())
            assertEquals("P1", first.path("portfolio").asString())
            assertEquals("MOEX", first.path("exchange").asString())
            assertEquals("tok", first.path("token").asString())
        }

    @Test
    fun `placeConditional returns orderNumber on confirmed event`() =
        runBlocking {
            val result = transport.placeConditional("take-profit", "SBER", "sell", 1, BigDecimal("310"), "idem-tp", "P1", OrderPurpose.TP)

            assertEquals("ord-tp-1", result)
            val sent = jacksonObjectMapper().readTree(connection.sent.last())
            assertEquals("AlorOrders", sent.path("opcode").asString())
            assertEquals("take-profit", sent.path("type").asString())
            assertEquals("310", sent.path("stopPrice").asString())
        }

    @Test
    fun `unrelated messages are skipped and the real acknowledgement still correlates`() =
        runBlocking {
            connection.inbound.send("""{"id":"someone-else","status":"working"}""")
            connection.inbound.send("""{"requestId":"other-guid","status":"working"}""")
            connection.inbound.send("""{"id":"idem-1","orderNumber":"ord-1","status":"working"}""")

            val result = transport.placeLimit("SBER", "buy", 1, BigDecimal("300"), "idem-1", "P1", OrderPurpose.ENTRY)

            assertEquals("ord-1", result)
        }

    @Test
    fun `ws commands and subscribe take token from token provider (not stale config)`(): Unit =
        runBlocking {
            // Регресс: транспорт читал alorConfig.token напрямую → после refresh
            // подписка/команды уходили с протухшим токеном (401, fail-closed).
            val provider = org.mockito.Mockito.mock(AlorTokenProvider::class.java) { _ -> "refreshed-token" }
            val tokenConnection = FakeConnection()
            val freshTransport =
                WsOrderTransport(
                    alorConfig,
                    tradingConfig,
                    SimpleMeterRegistry(),
                    FakeSocketFactory(tokenConnection),
                    scope,
                    resolver(),
                    provider,
                )

            val deadline = System.currentTimeMillis() + 5_000
            while (tokenConnection.sent.none { it.contains("OrdersGetAndSubscribeV2") } && System.currentTimeMillis() < deadline) {
                delay(20)
            }
            val subscribe =
                jacksonObjectMapper().readTree(tokenConnection.sent.first { it.contains("OrdersGetAndSubscribeV2") })
            assertEquals("refreshed-token", subscribe.path("token").asString())

            val result =
                freshTransport.placeLimit("SBER", "buy", 1, BigDecimal("300"), "idem-token", "P1", OrderPurpose.ENTRY)

            assertEquals("ord-1", result)
            val command = jacksonObjectMapper().readTree(tokenConnection.sent.last())
            assertEquals("AlorOrders", command.path("opcode").asString())
            assertEquals("refreshed-token", command.path("token").asString())
        }

    private fun resolver(): LiveFrozenStrategyResolver {
        val resolver = mock<LiveFrozenStrategyResolver>()
        runBlocking { whenever(resolver.isOrderAllowed(any(), any())).thenReturn(true) }
        return resolver
    }

    private fun tokenProvider(): AlorTokenProvider = AlorTokenProvider(alorConfig, tools.jackson.databind.ObjectMapper())

    private class FakeConnection : WsOrderSocketConnection {
        val inbound = Channel<String>(capacity = 100)
        val sent: MutableList<String> = CopyOnWriteArrayList()
        var closeOnSubscribe = false

        override val messages: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            sent += text
            val json =
                tools.jackson.module.kotlin
                    .jacksonObjectMapper()
                    .readTree(text)
            when (json.path("opcode").asString()) {
                // Имитируем завершение потока: connection-loop должен переподключиться.
                "OrdersGetAndSubscribeV2" -> {
                    if (closeOnSubscribe) inbound.close()
                }

                "AlorOrders" -> {
                    // Подтверждаем размещение автоматически (correlation по id/guid payload-а).
                    val id = json.path("id").asString()
                    val type = json.path("type").asString()
                    val orderNumber = if (type == "limit") "ord-1" else "ord-tp-1"
                    inbound.trySend("""{"id":"$id","orderNumber":"$orderNumber","status":"working"}""")
                }
            }
        }

        override suspend fun close() = Unit
    }

    private class FakeSocketFactory(
        private val connection: FakeConnection,
    ) : WsOrderSocketFactory {
        val openCount = AtomicInteger()

        override suspend fun open(): WsOrderSocketConnection {
            openCount.incrementAndGet()
            return connection
        }
    }
}
