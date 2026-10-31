package com.trading.bot.service

import com.trading.bot.client.MoexClient
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.Black76Calculator
import com.trading.bot.domain.risk.OptionKind
import com.trading.bot.domain.risk.OptionQuote
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

/**
 * [ImpliedVolatilityService] — кэш TTL, отключённый флаг, просроченные серии,
 * fallback выбора экспирации по открытому интересу, BID-премия и вырожденные
 * входы (нет underlying / экспирация сегодня / IV не решается).
 */
class ImpliedVolatilityServiceBranchesTest {
    private val moexClient = Mockito.mock(MoexClient::class.java)
    private val today = LocalDate.now()
    private val meterRegistry = SimpleMeterRegistry()

    private fun service(config: RiskConfig = RiskConfig()) = ImpliedVolatilityService(config, moexClient, meterRegistry)

    private fun quote(
        strike: Long,
        expiryOffsetDays: Long,
        openPosition: Long,
        last: BigDecimal? = null,
        bid: BigDecimal? = null,
        underlying: BigDecimal? = BigDecimal("83237"),
        kind: OptionKind = OptionKind.CALL,
    ) = OptionQuote(
        secid = "Si$strike-$expiryOffsetDays",
        assetCode = "Si",
        kind = kind,
        strike = BigDecimal(strike),
        lastTradeDate = today.plusDays(expiryOffsetDays),
        underlyingAsset = "SiU6",
        underlyingSettlePrice = underlying,
        last = last,
        bid = bid,
        openPosition = openPosition,
    )

    private fun premium(
        sigma: Double,
        expiryOffsetDays: Long,
    ): BigDecimal {
        val price =
            Black76Calculator.price(
                forward = 83_237.0,
                strike = 83_000.0,
                yearsToExpiry = expiryOffsetDays / 365.0,
                kind = OptionKind.CALL,
                sigma = sigma,
            )
        return BigDecimal(price)
    }

    @Test
    fun `second refresh within ttl does not hit the exchange again`(): Unit =
        runBlocking {
            whenever(moexClient.getFortsOptions()).thenReturn(
                listOf(quote(83_000, 30, 5_000, last = premium(0.20, 30))),
            )
            val s = service()

            s.refresh()
            s.refresh()

            Mockito.verify(moexClient, Mockito.times(1)).getFortsOptions()
        }

    @Test
    fun `disabled service never fetches options`(): Unit =
        runBlocking {
            val config = RiskConfig().apply { impliedVolatilityEnabled = false }
            val s = service(config)

            s.refresh()

            Mockito.verify(moexClient, Mockito.never()).getFortsOptions()
            assertNull(s.snapshot())
        }

    @Test
    fun `expired series are still considered when no future expiry exists`(): Unit =
        runBlocking {
            whenever(moexClient.getFortsOptions()).thenReturn(
                listOf(quote(83_000, -10, 5_000, last = premium(0.20, 30))),
            )
            val s = service()

            s.refresh()

            // fallback на полный список сработал, но отрицательный срок до экспирации не ценится
            Mockito.verify(moexClient).getFortsOptions()
            assertNull(s.snapshot())
        }

    @Test
    fun `expiry with non zero open interest is preferred over the earliest`(): Unit =
        runBlocking {
            whenever(moexClient.getFortsOptions()).thenReturn(
                listOf(
                    quote(83_000, 30, openPosition = 0),
                    quote(83_000, 90, openPosition = 5, last = premium(0.20, 90)),
                ),
            )
            val s = service()

            s.refresh()

            assertEquals(today.plusDays(90), s.snapshot()!!.expiry)
        }

    @Test
    fun `zero open interest everywhere falls back to the earliest expiry`(): Unit =
        runBlocking {
            whenever(moexClient.getFortsOptions()).thenReturn(
                listOf(
                    quote(83_000, 30, openPosition = 0, last = premium(0.20, 30)),
                    quote(83_000, 90, openPosition = 0, last = premium(0.20, 90)),
                ),
            )
            val s = service()

            s.refresh()

            assertEquals(today.plusDays(30), s.snapshot()!!.expiry)
        }

    @Test
    fun `quotes without a positive underlying give no snapshot`(): Unit =
        runBlocking {
            whenever(moexClient.getFortsOptions()).thenReturn(
                listOf(quote(83_000, 30, 5_000, last = premium(0.20, 30), underlying = null)),
            )
            val s = service()

            s.refresh()

            assertNull(s.snapshot())
        }

    @Test
    fun `bid is used when the last price is missing`(): Unit =
        runBlocking {
            whenever(moexClient.getFortsOptions()).thenReturn(
                listOf(quote(83_000, 30, 5_000, last = null, bid = premium(0.20, 30))),
            )
            val s = service()

            s.refresh()

            val snapshot = s.snapshot()
            assertNotNull(snapshot)
            assertTrue(snapshot!!.ivPercent in 18.0..22.0, "unexpected IV ${snapshot.ivPercent}")
        }

    @Test
    fun `expiry today is not priced`(): Unit =
        runBlocking {
            whenever(moexClient.getFortsOptions()).thenReturn(
                listOf(quote(83_000, 0, 5_000, last = BigDecimal("100"))),
            )
            val s = service()

            s.refresh()

            assertNull(s.snapshot())
        }
}
