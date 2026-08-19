package com.trading.bot.application

import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsStream
import com.trading.bot.config.TradingConfig
import com.trading.bot.event.TradingHaltedEvent
import com.trading.bot.model.entity.BotSettings
import com.trading.bot.model.entity.TradingHaltRecord
import com.trading.bot.service.AdaptiveRiskService
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.DrawdownProtectionService
import com.trading.bot.service.RiskManagementService
import com.trading.bot.service.SettingsService
import com.trading.bot.service.TradingHaltService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import java.math.BigDecimal
import java.time.Instant

class TradingGateTest {
    private val settingsService = Mockito.mock(SettingsService::class.java)
    private val tradingConfig =
        TradingConfig().apply {
            tickers = listOf("SBER", "Si")
        }
    private val tradingHoursGuard = Mockito.mock(TradingHoursGuard::class.java)
    private val drawdownProtection = Mockito.mock(DrawdownProtectionService::class.java)
    private val riskManagement = Mockito.mock(RiskManagementService::class.java)
    private val adaptiveRisk = Mockito.mock(AdaptiveRiskService::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val marketDataGate = Mockito.mock(MarketDataGate::class.java)
    private val tradingHaltService = Mockito.mock(TradingHaltService::class.java)
    private val webSocketManager = Mockito.mock(WebSocketManager::class.java)

    private fun gate(
        manualEnabled: Boolean = true,
        halt: TradingHaltRecord? = null,
        drawdownBlocked: Boolean = false,
        hoursAllowed: Boolean = true,
        freshData: Boolean = true,
    ): TradingGate {
        Mockito.`when`(settingsService.getSettings()).thenReturn(BotSettings().copy(tradingEnabled = manualEnabled))
        Mockito.`when`(tradingHaltService.last()).thenReturn(halt)
        Mockito.`when`(drawdownProtection.isEntryBlocked()).thenReturn(drawdownBlocked)
        Mockito.`when`(drawdownProtection.entryBlockReason()).thenReturn("DAILY_LOSS: -12000 RUB <= -10000 RUB")
        Mockito.`when`(tradingHoursGuard.isTradingAllowed()).thenReturn(hoursAllowed)
        Mockito.`when`(marketDataGate.isPriceDataFresh(Mockito.anyString())).thenReturn(freshData)
        Mockito
            .`when`(candleCache.getRecentCandles(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(emptyList())
        Mockito
            .`when`(candleCache.calculateAtr(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(null)
        Mockito.`when`(webSocketManager.isConnected(any<WsStream>())).thenReturn(true)
        runBlocking {
            Mockito.`when`(adaptiveRisk.shouldPauseTrading(Mockito.anyString())).thenReturn(false)
        }
        return TradingGate(
            settingsService = settingsService,
            tradingConfig = tradingConfig,
            tradingHoursGuard = tradingHoursGuard,
            drawdownProtection = drawdownProtection,
            riskManagement = riskManagement,
            adaptiveRisk = adaptiveRisk,
            candleCache = candleCache,
            marketDataGate = marketDataGate,
            tradingHaltService = tradingHaltService,
            webSocketManager = webSocketManager,
        )
    }

    @Test
    fun `manual disable is surfaced as MANUAL_DISABLE`() {
        val g = gate(manualEnabled = false)
        val status = runBlocking { g.getStatus() }

        assertFalse(status.enabled)
        assertEquals(TradingBlockReason.MANUAL_DISABLE, status.reason)
        assertEquals(TradingBlockSource.MANUAL, status.source)
        assertFalse(g.isTradingEnabled())
    }

    @Test
    fun `persisted halt reason is surfaced`() {
        val halt =
            TradingHaltRecord(
                reason = "STATE_DESYNC",
                source = "STATE_RECONCILIATION",
                haltedAt = Instant.parse("2026-01-01T10:00:00Z"),
            )
        val g = gate(halt = halt)
        val status = runBlocking { g.getStatus() }

        assertFalse(status.enabled)
        assertEquals(TradingBlockReason.STATE_DESYNC, status.reason)
        assertEquals(TradingBlockSource.STATE_RECONCILIATION, status.source)
    }

    @Test
    fun `manual emergency stop blocks globally with MANUAL source`() {
        val halt =
            TradingHaltRecord(
                reason = "EMERGENCY_STOP",
                source = "MANUAL",
                detail = "ops call",
                haltedAt = Instant.parse("2026-01-01T10:00:00Z"),
            )
        val g = gate(halt = halt)
        val status = runBlocking { g.getStatus() }

        assertFalse(status.enabled)
        assertEquals(TradingBlockReason.EMERGENCY_STOP, status.reason)
        assertEquals(TradingBlockSource.MANUAL, status.source)
        assertEquals("ops call", status.detail)
        assertFalse(g.isTradingEnabled())
    }

    @Test
    fun `automatic emergency stop blocks globally with RISK_SYSTEM source`() {
        val halt =
            TradingHaltRecord(
                reason = "EMERGENCY_STOP",
                source = "AUTO",
                detail = "hourly loss exceeded",
                haltedAt = Instant.parse("2026-01-01T10:00:00Z"),
            )
        val g = gate(halt = halt)
        val status = runBlocking { g.getStatus() }

        assertFalse(status.enabled)
        assertEquals(TradingBlockReason.EMERGENCY_STOP, status.reason)
        assertEquals(TradingBlockSource.RISK_SYSTEM, status.source)
        assertFalse(g.isTradingEnabled())
    }

    @Test
    fun `drawdown protection blocks globally`() {
        val g = gate(drawdownBlocked = true)
        val status = runBlocking { g.getStatus() }

        assertFalse(status.enabled)
        assertEquals(TradingBlockReason.DRAWDOWN_PROTECTION, status.reason)
        assertEquals(TradingBlockSource.RISK_SYSTEM, status.source)
        assertTrue(status.detail!!.contains("DAILY_LOSS"))
        assertFalse(g.isTradingEnabled())
    }

    @Test
    fun `outside trading hours blocks globally`() {
        val g = gate(hoursAllowed = false)
        val status = runBlocking { g.getStatus() }

        assertFalse(status.enabled)
        assertEquals(TradingBlockReason.OUTSIDE_HOURS, status.reason)
        assertEquals(TradingBlockSource.TRADING_HOURS, status.source)
    }

    @Test
    fun `per-ticker blocks do not disable globally but are listed`() {
        val g = gate(freshData = false)
        val status = runBlocking { g.getStatus() }

        assertTrue(status.enabled)
        assertTrue(status.blocks.any { it.reason == TradingBlockReason.STALE_DATA && it.ticker != null })
        assertTrue(g.isTradingEnabled())
    }

    @Test
    fun `manual disable has priority over persisted halt`() {
        val halt =
            TradingHaltRecord(
                reason = "DAILY_LOSS_LIMIT",
                source = "RISK_SYSTEM",
                haltedAt = Instant.parse("2026-01-01T10:00:00Z"),
            )
        val g = gate(manualEnabled = false, halt = halt)
        val status = runBlocking { g.getStatus() }

        assertEquals(TradingBlockReason.MANUAL_DISABLE, status.reason)
        assertTrue(status.blocks.first().reason == TradingBlockReason.MANUAL_DISABLE)
    }

    @Test
    fun `halt event records reason and source through halt service`() {
        val g = gate()
        g.onTradingHalted(TradingHaltedEvent(reason = "DAILY_LOSS_LIMIT", timestamp = Instant.parse("2026-01-01T10:00:00Z")))

        runBlocking {
            verify(tradingHaltService).record(
                eq("DAILY_LOSS_LIMIT"),
                eq("RISK_SYSTEM"),
                eq(""),
                eq(Instant.parse("2026-01-01T10:00:00Z")),
            )
        }
    }

    @Test
    fun `stale data blocks entry via isTradingEnabled when global`() {
        // Глобальные блоки (часы) выключены — ручной флаг единственный источник отключения.
        val g = gate(manualEnabled = false, hoursAllowed = false)
        assertFalse(g.isTradingEnabled())
    }

    @Test
    fun `unknown halt reason blocks trading fail-closed as STATE_DESYNC`() {
        val halt = TradingHaltRecord(reason = "SOMETHING_NEW", source = "RISK_SYSTEM", haltedAt = Instant.now())
        val g = gate(halt = halt)
        val status = runBlocking { g.getStatus() }

        assertFalse(g.isTradingEnabled())
        assertTrue(status.blocks.any { it.reason == TradingBlockReason.STATE_DESYNC })
    }

    @Test
    fun `volatility block is per-ticker`() {
        val g = gate()
        val candle =
            com.trading.bot.model.entity.Candle(
                ticker = "SBER",
                timeframe = "MINUTE_10",
                openPrice = BigDecimal("290"),
                highPrice = BigDecimal("291"),
                lowPrice = BigDecimal("289"),
                closePrice = BigDecimal("290.5"),
                volume = 100L,
                time = java.time.LocalDateTime.now(),
            )
        Mockito
            .`when`(candleCache.getRecentCandles(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(listOf(candle))
        Mockito
            .`when`(candleCache.calculateAtr(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(BigDecimal("20"))
        Mockito.`when`(riskManagement.isVolatilityTooHigh(any(), any())).thenReturn(true)
        val status = runBlocking { g.getStatus() }

        assertTrue(status.enabled)
        assertTrue(status.blocks.any { it.reason == TradingBlockReason.VOLATILITY && it.ticker == "SBER" })
    }

    @Test
    fun `adaptive pause block is per-ticker`() {
        val g = gate()
        runBlocking {
            Mockito.`when`(adaptiveRisk.shouldPauseTrading(eq("SBER"))).thenReturn(true)
        }
        val status = runBlocking { g.getStatus() }

        assertTrue(status.enabled)
        assertTrue(status.blocks.any { it.reason == TradingBlockReason.TICKER_PAUSED && it.ticker == "SBER" })
    }
}
