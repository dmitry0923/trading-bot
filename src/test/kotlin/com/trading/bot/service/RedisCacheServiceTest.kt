package com.trading.bot.service

import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Strategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicReference

/**
 * Синхронный [RedisCacheService] (не reactive): стратегия с TTL 15 мин,
 * feedback с проверкой хеша статистики и fail-soft на ошибках Redis.
 */
class RedisCacheServiceTest {
    private val objectMapper = jacksonObjectMapper()
    private val ops = mock<ValueOperations<String, String>>()
    private val template = mock<StringRedisTemplate>()
    private val service = RedisCacheService(template, objectMapper)

    private fun stubOps() {
        whenever(template.opsForValue()).thenReturn(ops)
    }

    private fun strategy(ticker: String = "SBER"): Strategy =
        Strategy(
            ticker = ticker,
            action = StrategyAction.BUY,
            targetPrice = BigDecimal("100"),
            quantity = 1,
            signalStrength = 0.6,
            reasoning = "test",
            cycleId = "c1",
            validUntil = LocalDateTime.now().plusMinutes(5),
        )

    @Test
    fun `save writes the strategy with a fifteen minute ttl`() {
        stubOps()
        val captured = AtomicReference<String>()

        service.saveStrategy(strategy("SBER"))

        verify(ops).set(eq("strategy:SBER"), check { captured.set(it) }, eq(Duration.ofMinutes(15)))
        assertEquals("SBER", objectMapper.readValue(captured.get(), Strategy::class.java).ticker)
    }

    @Test
    fun `save swallows a redis failure`() {
        whenever(template.opsForValue()).thenThrow(IllegalStateException("redis down"))

        service.saveStrategy(strategy())
    }

    @Test
    fun `get returns the cached strategy or null`() {
        stubOps()
        whenever(ops.get("strategy:SBER")).thenReturn(objectMapper.writeValueAsString(strategy("SBER")))
        whenever(ops.get("strategy:GAZP")).thenReturn(null)

        assertEquals("SBER", service.getStrategy("SBER")?.ticker)
        assertNull(service.getStrategy("GAZP"))
    }

    @Test
    fun `get swallows unparsable payloads and redis errors`() {
        stubOps()
        whenever(ops.get("strategy:BAD")).thenReturn("{not-json")
        whenever(ops.get("strategy:ERR")).thenThrow(IllegalStateException("redis down"))

        assertNull(service.getStrategy("BAD"))
        assertNull(service.getStrategy("ERR"))
    }

    @Test
    fun `get all keeps only tickers present in the cache`() {
        stubOps()
        whenever(ops.get("strategy:SBER")).thenReturn(objectMapper.writeValueAsString(strategy("SBER")))
        whenever(ops.get("strategy:GAZP")).thenReturn(null)

        val all = service.getAllStrategies(listOf("SBER", "GAZP"))

        assertEquals(setOf("SBER"), all.keys)
    }

    @Test
    fun `feedback is returned only while the stats hash matches`() {
        stubOps()
        whenever(ops.get("feedback:SBER")).thenReturn("""{"ticker":"SBER","statsHash":"h1","feedbackJson":"{\"ok\":true}"}""")

        assertEquals("""{"ok":true}""", service.getFeedback("SBER", "h1"))
        assertNull(service.getFeedback("SBER", "h2"))
    }

    @Test
    fun `feedback returns null on a miss or redis error`() {
        stubOps()
        whenever(ops.get("feedback:SBER")).thenReturn(null)
        whenever(ops.get("feedback:ERR")).thenThrow(IllegalStateException("redis down"))

        assertNull(service.getFeedback("SBER", "h1"))
        assertNull(service.getFeedback("ERR", "h1"))
    }

    @Test
    fun `save feedback swallows a redis failure`() {
        whenever(template.opsForValue()).thenThrow(IllegalStateException("redis down"))

        service.saveFeedback("SBER", "{}", "h1")
    }
}
