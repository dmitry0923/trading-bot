package com.trading.bot.controller

import com.trading.bot.config.MlConfig
import com.trading.bot.model.dto.MlDatasetExport
import com.trading.bot.model.dto.MlDatasetRow
import com.trading.bot.service.MlDatasetService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDateTime

class MlDatasetControllerTest {
    private val config = MlConfig()
    private val mlDatasetService = Mockito.mock(MlDatasetService::class.java)
    private val controller = MlDatasetController(config, mlDatasetService)

    @BeforeEach
    fun reset() {
        Mockito.reset(mlDatasetService)
    }

    @Test
    fun `dataset returns 404 when ml disabled`() {
        config.enabled = false
        val ex = assertThrows<ResponseStatusException> { runBlocking { controller.dataset(null, null, null) } }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        runBlocking {
            Mockito.verify(mlDatasetService, Mockito.never()).export(any(), any(), any())
        }
    }

    @Test
    fun `stats returns 404 when ml disabled`() {
        config.enabled = false
        val ex = assertThrows<ResponseStatusException> { runBlocking { controller.stats(null, null) } }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `dataset returns csv with header and rows`() {
        config.enabled = true
        val export =
            MlDatasetExport(
                mode = "OK",
                positionsCount = 1,
                rows = listOf(row()),
                skippedInsufficientData = 0,
                generatedAt = LocalDateTime.now(),
            )
        runBlocking {
            Mockito.`when`(mlDatasetService.export(null, null, null)).thenReturn(export)
        }

        val response = runBlocking { controller.dataset(null, null, null) }

        assertEquals("text/csv", response.headers.contentType?.toString())
        assertTrue(response.headers.getFirst("Content-Disposition")!!.contains("ml_dataset.csv"))
        val csv = response.body!!
        assertTrue(csv.startsWith("position_id,ticker,direction"))
        assertTrue(csv.contains("1,SBER,LONG"))
    }

    @Test
    fun `dataset returns header only when no rows`() {
        config.enabled = true
        val export =
            MlDatasetExport(
                mode = "OK",
                positionsCount = 0,
                rows = emptyList(),
                skippedInsufficientData = 0,
                generatedAt = LocalDateTime.now(),
            )
        runBlocking {
            Mockito.`when`(mlDatasetService.export(null, "SBER", 10)).thenReturn(export)
        }

        val response = runBlocking { controller.dataset(null, "SBER", 10) }

        assertEquals(
            "position_id,ticker,direction,opened_at,closed_at,duration_min,entry_price,exit_price,pnl_rub,pnl_percent,close_reason,win,hour_of_day,rsi14,atr_percent,macd_hist_percent,bb_percent_b,ema_slope_percent,volatility20_percent,ret_3,ret_10,ret_20,cbr_rate,brent,usd_rub,macro_source,strategy_action,strategy_confidence,in_blind_spot_hour",
            response.body,
        )
    }

    @Test
    fun `stats delegates to service`() {
        config.enabled = true
        runBlocking {
            Mockito.`when`(mlDatasetService.stats(null, null)).thenReturn(mapOf("mode" to "OK", "positionsCount" to 5))
        }

        val stats = runBlocking { controller.stats(null, null) }

        assertEquals("OK", stats["mode"])
        assertEquals(5, stats["positionsCount"])
    }

    private fun row(): MlDatasetRow =
        MlDatasetRow(
            positionId = 1L,
            ticker = "SBER",
            direction = "LONG",
            openedAt = LocalDateTime.of(2026, 2, 1, 14, 0),
            closedAt = LocalDateTime.of(2026, 2, 1, 17, 0),
            durationMinutes = 180,
            entryPrice = BigDecimal("100.0"),
            exitPrice = BigDecimal("105.0"),
            pnlRub = BigDecimal("500.0"),
            pnlPercent = 5.0,
            closeReason = "TAKE_PROFIT",
            win = 1,
            hourOfDay = 14,
            rsi14 = 65.0,
            atrPercent = 1.2,
            macdHistogramPercent = 0.5,
            bbPercentB = 80.0,
            emaSlopePercent = 1.0,
            volatility20Percent = 2.5,
            return3 = 1.0,
            return10 = 2.0,
            return20 = 4.0,
            cbrRate = BigDecimal("16"),
            brentPrice = BigDecimal("75"),
            usdRub = BigDecimal("90"),
            macroSource = "SNAPSHOT",
            strategyAction = "BUY",
            strategyConfidence = 0.85,
            inBlindSpotHour = 1,
        )
}
