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
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Подразумеваемая волатильность Si: выбор ATM ближайшего ликвидного месяца
 * и инверсия Black-76 по премии.
 */
class ImpliedVolatilityServiceTest {
    private val moexClient = Mockito.mock(MoexClient::class.java)
    private val today = LocalDate.now()

    private fun service(config: RiskConfig = RiskConfig()): ImpliedVolatilityService =
        ImpliedVolatilityService(config, moexClient, SimpleMeterRegistry())

    private fun quote(
        strike: Long,
        expiryOffsetDays: Long,
        openPosition: Long,
        last: Long = 0,
        kind: OptionKind = OptionKind.CALL,
        underlying: Long = 83_237L,
    ) = OptionQuote(
        secid = "Si$strike",
        assetCode = "Si",
        kind = kind,
        strike = BigDecimal(strike),
        lastTradeDate = today.plusDays(expiryOffsetDays),
        underlyingAsset = "SiU6",
        underlyingSettlePrice = BigDecimal(underlying),
        last = BigDecimal(last).takeIf { it > BigDecimal.ZERO },
        bid = null,
        openPosition = openPosition,
    )

    /** Премия ATM-call при заданной волатильности (для реалистичного last). */
    private fun premiumFor(
        sigma: Double,
        expiryOffsetDays: Long,
        strike: Long = 83_000L,
    ): Long =
        Black76Calculator
            .price(
                forward = 83_237.0,
                strike = strike.toDouble(),
                yearsToExpiry = expiryOffsetDays / 365.0,
                kind = OptionKind.CALL,
                sigma = sigma,
            ).toLong()

    @Test
    fun `picks nearest liquid month and atm strike`() =
        runBlocking {
            // ближний месяц (30д) с малым OI (300+300=600 < порога 1000)
            val near =
                listOf(
                    quote(80_000, expiryOffsetDays = 30, openPosition = 300),
                    quote(83_000, expiryOffsetDays = 30, openPosition = 300),
                )
            // дальний ликвидный месяц (90д) с OI 6000
            val far =
                listOf(
                    quote(80_000, expiryOffsetDays = 90, openPosition = 3000),
                    quote(83_000, expiryOffsetDays = 90, openPosition = 3000, last = premiumFor(0.20, 90)),
                )
            Mockito.`when`(moexClient.getFortsOptions()).thenReturn(near + far)

            val s = service()
            s.refresh()

            val snap = s.snapshot()
            assertNotNull(snap)
            assertEquals(today.plusDays(90), snap!!.expiry)
            assertEquals(0, BigDecimal("83000").compareTo(snap.atmStrike))
            assertEquals(0, BigDecimal("83237").compareTo(snap.underlyingPrice))
            assertTrue(snap.ivPercent in 18.0..22.0, "unexpected IV ${snap.ivPercent}")
        }

    @Test
    fun `prefers nearest expiry when it is liquid enough`() =
        runBlocking {
            val near =
                listOf(
                    quote(83_000, expiryOffsetDays = 30, openPosition = 600, last = 1800),
                    quote(83_000, expiryOffsetDays = 30, openPosition = 600, kind = OptionKind.PUT),
                )
            Mockito.`when`(moexClient.getFortsOptions()).thenReturn(near)

            val s = service()
            s.refresh()

            val snap = s.snapshot()
            assertNotNull(snap)
            assertEquals(today.plusDays(30), snap!!.expiry)
        }

    @Test
    fun `no liquid quotes leaves iv null`() =
        runBlocking {
            // премия нулевая (last=0) -> IV посчитать нельзя
            Mockito.`when`(moexClient.getFortsOptions()).thenReturn(listOf(quote(83_000, 30, 5000, last = 0)))

            val s = service()
            s.refresh()

            assertNull(s.impliedVolatilityPercent())
        }

    @Test
    fun `empty options table leaves iv null`() =
        runBlocking {
            Mockito.`when`(moexClient.getFortsOptions()).thenReturn(emptyList())

            val s = service()
            s.refresh()

            assertNull(s.impliedVolatilityPercent())
        }

    @Test
    fun `result cached within ttl`() =
        runBlocking {
            Mockito.`when`(moexClient.getFortsOptions()).thenReturn(listOf(quote(83_000, 30, 5000, last = 1800)))

            val s = service()
            s.refresh()
            s.refresh()

            Mockito.verify(moexClient, Mockito.times(1)).getFortsOptions()
            Unit
        }
}
