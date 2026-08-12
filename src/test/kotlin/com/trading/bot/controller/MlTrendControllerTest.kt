package com.trading.bot.controller

import com.trading.bot.config.MlConfig
import com.trading.bot.model.dto.MlTrendCandidate
import com.trading.bot.model.dto.MlTrendResult
import com.trading.bot.service.MlTrendForecastService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

class MlTrendControllerTest {
    private val config = MlConfig()
    private val mlTrendForecastService = Mockito.mock(MlTrendForecastService::class.java)
    private val controller = MlTrendController(config, mlTrendForecastService)

    @BeforeEach
    fun reset() {
        Mockito.reset(mlTrendForecastService)
    }

    @Test
    fun `trend returns 404 when ml disabled`() {
        config.enabled = false

        val ex =
            assertThrows<ResponseStatusException> {
                runBlocking { controller.trend(listOf("SBER"), null) }
            }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        runBlocking {
            Mockito.verify(mlTrendForecastService, Mockito.never()).forecast(any(), any())
        }
    }

    @Test
    fun `trend delegates to service when enabled`() {
        config.enabled = true
        val result =
            MlTrendResult(
                mode = "OK",
                generatedAt = LocalDateTime.now(),
                horizonBars = 6,
                topN = 1,
                candidates =
                    listOf(
                        MlTrendCandidate(
                            ticker = "SBER",
                            direction = "LONG",
                            probability = 0.85,
                            trendScore = 0.91,
                            inBlindSpotHour = 0,
                            hourOfDay = 14,
                        ),
                    ),
                skipped = emptyList(),
            )
        runBlocking {
            Mockito.`when`(mlTrendForecastService.forecast(listOf("SBER"), 5)).thenReturn(result)
        }

        val response = runBlocking { controller.trend(listOf("SBER"), 5) }

        assertEquals("OK", response.mode)
        assertEquals(1, response.candidates.size)
        assertEquals("SBER", response.candidates[0].ticker)
        assertEquals(0.91, response.candidates[0].trendScore)
    }
}
