package com.trading.bot.service

import com.trading.bot.model.CloseReason
import com.trading.bot.model.entity.TradingHaltRecord
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Instant

class EmergencyStopServiceTest {
    private val redisTemplate = Mockito.mock(StringRedisTemplate::class.java)

    @Suppress("UNCHECKED_CAST")
    private val redisOps =
        Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
    private val tradingHaltService = Mockito.mock(TradingHaltService::class.java)
    private val tradingControlService = Mockito.mock(TradingControlService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private fun service(): EmergencyStopService {
        Mockito.`when`(redisTemplate.opsForValue()).thenReturn(redisOps)
        return EmergencyStopService(
            redisTemplate = redisTemplate,
            tradingHaltService = tradingHaltService,
            tradingControlService = tradingControlService,
            meterRegistry = meterRegistry,
        )
    }

    @Test
    fun `stop sets local flag and persists halt`() {
        val s = service()

        runBlocking { s.stop("manual test", EmergencyStopSource.MANUAL) }

        assertTrue(s.isActive())
        assertEquals("manual test", s.lastReason())
        runBlocking {
            Mockito.verify(tradingHaltService).record(
                eq(EmergencyStopService.HALT_REASON),
                eq("MANUAL"),
                eq("manual test"),
                any(),
            )
        }
        Mockito.verify(redisOps).set(EmergencyStopService.REDIS_KEY, "true")
        assertEquals(1.0, meterRegistry.get("bot.emergency_stop").counter().count())
    }

    @Test
    fun `stop without liquidate closes nothing`() {
        val s = service()

        val closed = runBlocking { s.stop("manual", EmergencyStopSource.MANUAL, liquidate = false) }

        assertEquals(0, closed)
        runBlocking {
            Mockito.verify(tradingControlService, Mockito.never()).forceCloseNow(any())
        }
    }

    @Test
    fun `stop with liquidate closes all positions`() {
        val s = service()
        runBlocking { Mockito.`when`(tradingControlService.forceCloseNow(CloseReason.EMERGENCY_STOP)).thenReturn(2) }

        val closed = runBlocking { s.stop("manual", EmergencyStopSource.MANUAL, liquidate = true) }

        assertEquals(2, closed)
        runBlocking {
            Mockito.verify(tradingControlService).forceCloseNow(CloseReason.EMERGENCY_STOP)
        }
    }

    @Test
    fun `resume clears local flag, redis key and halt record`() {
        val s = service()
        runBlocking { s.stop("manual") }
        assertTrue(s.isActive())

        runBlocking { s.resume() }

        assertFalse(s.isActive())
        assertNull(s.lastReason())
        Mockito.verify(redisTemplate).delete(EmergencyStopService.REDIS_KEY)
        runBlocking { Mockito.verify(tradingHaltService).clear() }
        assertEquals(1.0, meterRegistry.get("bot.emergency_resume").counter().count())
    }

    @Test
    fun `init restores active state from persisted halt`() {
        val s = service()
        val halt =
            TradingHaltRecord(
                reason = "EMERGENCY_STOP",
                source = "MANUAL",
                detail = "ops call",
                haltedAt = Instant.parse("2026-01-01T10:00:00Z"),
            )
        runBlocking { Mockito.`when`(tradingHaltService.last()).thenReturn(halt) }

        s.init()

        assertTrue(s.isActive())
        assertEquals("ops call", s.lastReason())
        Mockito.verify(redisOps).set(EmergencyStopService.REDIS_KEY, "true")
    }

    @Test
    fun `init ignores non-emergency halt`() {
        val s = service()
        val halt =
            TradingHaltRecord(
                reason = "DAILY_LOSS_LIMIT",
                source = "RISK_SYSTEM",
                haltedAt = Instant.parse("2026-01-01T10:00:00Z"),
            )
        runBlocking { Mockito.`when`(tradingHaltService.last()).thenReturn(halt) }

        s.init()

        assertFalse(s.isActive())
    }
}
