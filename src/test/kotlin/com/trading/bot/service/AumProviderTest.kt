package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.anyOrNull
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Fail-closed AUM (P1): [AumProvider.currentAumChecked] / [latestAumResult] должны
 * возвращать [AumProvider.AumResult.Unavailable] в LIVE при недоступности реального
 * баланса, а не подменять его конфигурационным депозитом. В SIMULATION — конфиг-fallback.
 */
class AumProviderTest {
    private val alor = Mockito.mock(AlorFuturesClient::class.java)
    private val riskConfig = RiskConfig()
    private val tradingConfig = TradingConfig()
    private val tradingAccountService = Mockito.mock(TradingAccountService::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private lateinit var aumProvider: AumProvider

    @BeforeEach
    fun setUp() {
        tradingConfig.mode = "LIVE"
        aumProvider = AumProvider(alor, riskConfig, tradingConfig, tradingAccountService, meterRegistry)
        runBlocking {
            Mockito.`when`(tradingAccountService.portfolioOf(anyOrNull())).thenReturn("P1")
        }
    }

    private fun stubMoney(money: BigDecimal?) {
        runBlocking {
            Mockito.`when`(alor.getPortfolioMoney(Mockito.anyString())).thenReturn(money)
        }
    }

    private fun stubOverride(fallback: Long?) {
        runBlocking {
            Mockito
                .`when`(tradingAccountService.aumRubOverrideFor(anyOrNull()))
                .thenReturn(fallback?.let { BigDecimal(it) })
        }
    }

    @Test
    fun `currentAumChecked returns Available in LIVE when balance fetched`() {
        stubMoney(BigDecimal("123456"))
        stubOverride(null)

        val result = runBlocking { aumProvider.currentAumChecked(7L) }

        val value = (result as AumProvider.AumResult.Available).value
        assertEquals(0, BigDecimal("123456").compareTo(value))
    }

    @Test
    fun `currentAumChecked returns Unavailable in LIVE when balance is null`() {
        stubMoney(null)
        stubOverride(null)

        val result = runBlocking { aumProvider.currentAumChecked(7L) }

        assertTrue(result is AumProvider.AumResult.Unavailable)
    }

    @Test
    fun `currentAumChecked returns Unavailable in LIVE when balance is zero`() {
        stubMoney(BigDecimal.ZERO)
        stubOverride(null)

        val result = runBlocking { aumProvider.currentAumChecked(7L) }

        assertTrue(result is AumProvider.AumResult.Unavailable)
    }

    @Test
    fun `currentAumChecked returns Unavailable in LIVE when fetch throws`() {
        stubOverride(null)
        runBlocking {
            Mockito.`when`(alor.getPortfolioMoney(Mockito.anyString())).thenThrow(RuntimeException("api down"))
        }

        val result = runBlocking { aumProvider.currentAumChecked(7L) }

        assertTrue(result is AumProvider.AumResult.Unavailable)
    }

    @Test
    fun `currentAumChecked uses config fallback in SIMULATION when balance unavailable`() {
        tradingConfig.mode = "SIMULATION"
        stubMoney(null)
        stubOverride(null)

        val result = runBlocking { aumProvider.currentAumChecked(7L) }

        val value = (result as AumProvider.AumResult.Available).value
        assertEquals(0, riskConfig.maxPositionRub.compareTo(value))
    }

    @Test
    fun `currentAumChecked returns override even in LIVE`() {
        stubOverride(777_000L)

        val result = runBlocking { aumProvider.currentAumChecked(7L) }

        val value = (result as AumProvider.AumResult.Available).value
        assertEquals(0, BigDecimal("777000").compareTo(value))
    }

    @Test
    fun `latestAumResult returns Unavailable in LIVE without cached value`() {
        stubOverride(null)

        val result = aumProvider.latestAumResult(7L)

        assertTrue(result is AumProvider.AumResult.Unavailable)
    }

    @Test
    fun `latestAumResult returns config fallback in SIMULATION without cached value`() {
        tradingConfig.mode = "SIMULATION"
        stubOverride(null)

        val result = aumProvider.latestAumResult(7L)

        val value = (result as AumProvider.AumResult.Available).value
        assertEquals(0, riskConfig.maxPositionRub.compareTo(value))
    }

    @Test
    fun `latestAumResult returns cached value after successful fetch`() {
        stubMoney(BigDecimal("200000"))
        stubOverride(null)
        runBlocking { aumProvider.currentAumChecked(7L) }

        val result = aumProvider.latestAumResult(7L)

        val value = (result as AumProvider.AumResult.Available).value
        assertEquals(0, BigDecimal("200000").compareTo(value))
    }

    @Test
    fun `latestAumResult ignores unconfirmed seed in LIVE`() {
        // currentAumChecked при failed-фетче оставил только сид (updatedAt=0) —
        // он НЕ является подтверждённым AUM → в LIVE по-прежнему Unavailable.
        stubMoney(null)
        stubOverride(null)
        runBlocking { aumProvider.currentAumChecked(7L) }

        val result = aumProvider.latestAumResult(7L)

        assertTrue(result is AumProvider.AumResult.Unavailable)
    }

    @Test
    fun `latestAumResult treats stale cache beyond max age as Unavailable in LIVE`() {
        stubMoney(BigDecimal("200000"))
        stubOverride(null)
        runBlocking { aumProvider.currentAumChecked(7L) }
        backdateCache(7L, ageMs = 6 * 60_000L)

        val result = aumProvider.latestAumResult(7L)

        assertTrue(result is AumProvider.AumResult.Unavailable)
    }

    @Test
    fun `failed fetch is negative-cached so consecutive calls hit the provider once`(): Unit =
        runBlocking {
            // Регрессия retry storm: без штампа времени неудачной попытки каждый
            // последующий вызов (дашборд, входной конвейер) снова шёл бы в Alor.
            var calls = 0
            Mockito
                .`when`(alor.getPortfolioMoney(Mockito.anyString()))
                .thenAnswer { _ ->
                    calls++
                    throw RuntimeException("api down")
                }
            stubOverride(null)

            val first = aumProvider.currentAumChecked(7L)
            val second = aumProvider.currentAumChecked(7L)
            val third = aumProvider.currentAum(7L)

            assertTrue(first is AumProvider.AumResult.Unavailable)
            assertTrue(second is AumProvider.AumResult.Unavailable, "negative cache must stay fail-closed")
            assertEquals(0, riskConfig.maxPositionRub.compareTo(third))
            assertEquals(1, calls, "provider must be called exactly once inside the negative-TTL window")
        }

    @Test
    fun `null balance is negative-cached on the fail-open path too`(): Unit =
        runBlocking {
            var calls = 0
            Mockito
                .`when`(alor.getPortfolioMoney(Mockito.anyString()))
                .thenAnswer { _ ->
                    calls++
                    null as BigDecimal?
                }
            stubOverride(null)

            val first = aumProvider.currentAum(7L)
            val second = aumProvider.currentAum(7L)

            assertEquals(0, riskConfig.maxPositionRub.compareTo(first))
            assertEquals(0, riskConfig.maxPositionRub.compareTo(second))
            assertEquals(1, calls, "null balance must not cause a provider call on every render")
        }

    @Test
    fun `negative cache expires and the provider is retried`(): Unit =
        runBlocking {
            var calls = 0
            Mockito
                .`when`(alor.getPortfolioMoney(Mockito.anyString()))
                .thenAnswer { _ ->
                    calls++
                    throw RuntimeException("api down")
                }
            stubOverride(null)

            aumProvider.currentAumChecked(7L)
            backdateAttempt(7L, ageMs = 11_000L)
            aumProvider.currentAumChecked(7L)

            assertEquals(2, calls, "after the negative TTL the provider must be retried")
        }

    /** Отодвигает время последней попытки обращения к провайдеру. */
    private fun backdateAttempt(
        accountId: Long?,
        ageMs: Long,
    ) {
        val cache = cacheMap()
        val entry = cache[accountId ?: -1L] ?: return
        setEntryField(entry, "lastAttemptAt", System.currentTimeMillis() - ageMs)
    }

    private fun cacheMap(): ConcurrentHashMap<Long, Any> {
        @Suppress("UNCHECKED_CAST")
        return AumProvider::class.java
            .getDeclaredField("cache")
            .apply { isAccessible = true }
            .get(aumProvider) as ConcurrentHashMap<Long, Any>
    }

    private fun setEntryField(
        entry: Any,
        name: String,
        value: Long,
    ) {
        entry.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(entry, value)
        }
    }

    /** Устаревает кэш-запись на [ageMs] миллисекунд (симуляция зависшего API). */
    private fun backdateCache(
        accountId: Long?,
        ageMs: Long,
    ) {
        val entry = cacheMap()[accountId ?: -1L] ?: return
        setEntryField(entry, "updatedAt", System.currentTimeMillis() - ageMs)
        setEntryField(entry, "lastAttemptAt", System.currentTimeMillis() - ageMs)
    }
}
