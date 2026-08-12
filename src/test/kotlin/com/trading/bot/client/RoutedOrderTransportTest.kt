package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Unit-тесты маршрутизации доставки ордеров ([RoutedOrderTransport]):
 * WS primary, REST fallback ТОЛЬКО при [OrderTransportUnavailableException]
 * (команда не ушла), [OrderDeliveryUncertainException] НЕ перехватывается
 * (риск double execution), REST для не-дефолтных портфелей и выключенного WS.
 */
class RoutedOrderTransportTest {
    private val alorConfig = AlorConfig()
    private val tradingConfig = TradingConfig()
    private val wsOrderTransport = Mockito.mock(WsOrderTransport::class.java)
    private val restOrderTransport = Mockito.mock(RestOrderTransport::class.java)
    private lateinit var router: RoutedOrderTransport
    private val price = BigDecimal("250")

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

    @BeforeEach
    fun setUp() {
        alorConfig.wsOrdersEnabled = true
        alorConfig.portfolio = "P1"
        tradingConfig.mode = "LIVE"
        router =
            RoutedOrderTransport(
                alorConfig,
                tradingConfig,
                SimpleMeterRegistry(),
                wsOrderTransport,
                restOrderTransport,
            )
    }

    @Test
    fun `uses WS when enabled default portfolio and live`() =
        runBlocking {
            Mockito.`when`(wsOrderTransport.placeLimit("SBER", "buy", 1, price, "idem-1", "P1")).thenReturn("ws-order")
            val result = router.placeLimit("SBER", "buy", 1, price, "idem-1", "P1")
            assertEquals("ws-order", result)
            Mockito.verify(restOrderTransport, Mockito.never()).placeLimit(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyInt(),
                Mockito.any(BigDecimal::class.java),
                Mockito.anyString(),
                Mockito.anyString(),
            )
        }

    @Test
    fun `falls back to REST when WS unavailable before send`() =
        runBlocking {
            Mockito
                .`when`(wsOrderTransport.placeLimit("SBER", "buy", 1, price, "idem-1", "P1"))
                .thenThrow(OrderTransportUnavailableException("WS order channel not connected"))
            Mockito.`when`(restOrderTransport.placeLimit("SBER", "buy", 1, price, "idem-1", "P1")).thenReturn("rest-order")
            val result = router.placeLimit("SBER", "buy", 1, price, "idem-1", "P1")
            assertEquals("rest-order", result)
        }

    @Test
    fun `does not fall back when delivery is uncertain`() =
        runBlocking {
            Mockito
                .`when`(wsOrderTransport.placeLimit("SBER", "buy", 1, price, "idem-1", "P1"))
                .thenThrow(OrderDeliveryUncertainException("timeout"))
            assertFailsWith<OrderDeliveryUncertainException> {
                router.placeLimit("SBER", "buy", 1, price, "idem-1", "P1")
            }
            Mockito.verify(restOrderTransport, Mockito.never()).placeLimit(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyInt(),
                Mockito.any(BigDecimal::class.java),
                Mockito.anyString(),
                Mockito.anyString(),
            )
        }

    @Test
    fun `uses REST for non-default portfolio`() =
        runBlocking {
            Mockito.`when`(restOrderTransport.placeLimit("SBER", "buy", 1, price, "idem-1", "P2")).thenReturn("rest-order")
            val result = router.placeLimit("SBER", "buy", 1, price, "idem-1", "P2")
            assertEquals("rest-order", result)
            Mockito.verify(wsOrderTransport, Mockito.never()).placeLimit(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyInt(),
                Mockito.any(BigDecimal::class.java),
                Mockito.anyString(),
                Mockito.anyString(),
            )
        }

    @Test
    fun `uses REST when WS disabled`() =
        runBlocking {
            alorConfig.wsOrdersEnabled = false
            Mockito.`when`(restOrderTransport.placeLimit("SBER", "buy", 1, price, "idem-1", "P1")).thenReturn("rest-order")
            val result = router.placeLimit("SBER", "buy", 1, price, "idem-1", "P1")
            assertEquals("rest-order", result)
            Mockito.verify(wsOrderTransport, Mockito.never()).placeLimit(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyInt(),
                Mockito.any(BigDecimal::class.java),
                Mockito.anyString(),
                Mockito.anyString(),
            )
        }

    @Test
    fun `routes conditional to WS primary`() =
        runBlocking {
            Mockito
                .`when`(wsOrderTransport.placeConditional("stop", "SBER", "sell", 1, price, "idem-2", "P1"))
                .thenReturn("ws-stop")
            val result = router.placeConditional("stop", "SBER", "sell", 1, price, "idem-2", "P1")
            assertEquals("ws-stop", result)
        }

    @Test
    fun `routes cancel to WS primary`() =
        runBlocking {
            Mockito.`when`(wsOrderTransport.cancel("ord-1", "idem-c", "P1")).thenReturn(CancelResult.CONFIRMED)
            val result = router.cancel("ord-1", "idem-c", "P1")
            assertEquals(CancelResult.CONFIRMED, result)
        }
}
