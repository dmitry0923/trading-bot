package com.trading.bot.controller

import com.trading.bot.config.MlConfig
import com.trading.bot.model.dto.MlScreeningCandidate
import com.trading.bot.model.dto.MlScreeningResult
import com.trading.bot.service.MlScreeningService
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

class MlScreeningControllerTest {
    private val config = MlConfig()
    private val mlScreeningService = Mockito.mock(MlScreeningService::class.java)
    private val controller = MlScreeningController(config, mlScreeningService)

    @BeforeEach
    fun reset() {
        Mockito.reset(mlScreeningService)
    }

    @Test
    fun `screen returns 404 when ml disabled`() {
        config.enabled = false

        val ex =
            assertThrows<ResponseStatusException> {
                runBlocking { controller.screen(listOf("SBER"), null) }
            }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        runBlocking {
            Mockito.verify(mlScreeningService, Mockito.never()).screen(any(), any())
        }
    }

    @Test
    fun `screen delegates to service when enabled`() {
        config.enabled = true
        val result =
            MlScreeningResult(
                mode = "OK",
                generatedAt = LocalDateTime.now(),
                topN = 1,
                candidates =
                    listOf(
                        MlScreeningCandidate(
                            ticker = "SBER",
                            direction = "LONG",
                            probability = 0.85,
                            inBlindSpotHour = 0,
                            hourOfDay = 14,
                        ),
                    ),
                skipped = emptyList(),
            )
        runBlocking {
            Mockito.`when`(mlScreeningService.screen(listOf("SBER"), 5)).thenReturn(result)
        }

        val response = runBlocking { controller.screen(listOf("SBER"), 5) }

        assertEquals("OK", response.mode)
        assertEquals(1, response.candidates.size)
        assertEquals("SBER", response.candidates[0].ticker)
        assertEquals(0.85, response.candidates[0].probability)
    }
}
