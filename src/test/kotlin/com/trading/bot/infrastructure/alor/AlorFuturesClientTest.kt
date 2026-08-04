package com.trading.bot.infrastructure.alor

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.TradingConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Проверка AlorFuturesClient в SIMULATION режиме:
 * REST не вызывается, значения берутся из конфига (fallback).
 */
class AlorFuturesClientTest {

    private val instrumentsConfig = InstrumentsConfig()
    private val meterRegistry = SimpleMeterRegistry()

    private fun client(mode: String = "SIMULATION"): AlorFuturesClient {
        val tradingConfig = TradingConfig().apply { this.mode = mode }
        val alorConfig = AlorConfig()
        return AlorFuturesClient(alorConfig, tradingConfig, ObjectMapper(), instrumentsConfig, meterRegistry)
    }

    @Test
    fun `simulation returns config GO without REST call`() = runBlocking {
        val go = client().getFuturesGO("Si")

        assertEquals(0, BigDecimal("15000").compareTo(go))
    }

    @Test
    fun `simulation returns default portfolio money`() = runBlocking {
        val money = client().getPortfolioMoney()

        assertEquals(0, BigDecimal("50000").compareTo(money))
    }

    @Test
    fun `config fallback used for unknown ticker`() = runBlocking {
        val go = client().getFuturesGO("UNKNOWN")

        assertEquals(0, BigDecimal("15000").compareTo(go))
    }

    @Test
    fun `point value derived from price step and cost`() {
        val pointValue = instrumentsConfig.pointValue("Si")

        // 10.0 / 0.01 = 1000 ₽ на 1.0 цены
        assertEquals(0, BigDecimal("1000").compareTo(pointValue))
        assertTrue(pointValue.signum() > 0)
    }
}
