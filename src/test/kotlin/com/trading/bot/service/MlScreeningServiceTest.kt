package com.trading.bot.service

import com.trading.bot.config.MlConfig
import com.trading.bot.model.entity.BlindSpotEntity
import com.trading.bot.model.entity.Candle
import com.trading.bot.model.entity.MacroSnapshot
import com.trading.bot.repository.BlindSpotRepository
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.MacroSnapshotRepository
import com.trading.bot.service.ml.MlModel
import com.trading.bot.service.ml.MlModelProvider
import com.trading.bot.service.ml.NoopMlModel
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

class MlScreeningServiceTest {
    private val config = MlConfig()
    private val modelProvider = Mockito.mock(MlModelProvider::class.java)
    private val candleRepository = Mockito.mock(CandleRepository::class.java)
    private val blindSpotRepository = Mockito.mock(BlindSpotRepository::class.java)
    private val macroSnapshotRepository = Mockito.mock(MacroSnapshotRepository::class.java)
    private val macroContextService = Mockito.mock(MacroContextService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val service =
        MlScreeningService(
            config,
            modelProvider,
            candleRepository,
            blindSpotRepository,
            macroSnapshotRepository,
            macroContextService,
            meterRegistry,
        )

    @BeforeEach
    fun reset() {
        Mockito.reset(modelProvider, candleRepository, blindSpotRepository, macroSnapshotRepository, macroContextService)
    }

    @Test
    fun `screen ranks tickers by best direction and respects topN`() {
        config.enabled = true
        val now = LocalDateTime.now()
        val model = RecordingModel(longRsiProb = 0.85, shortRsiProb = 0.2, lowRsiLong = 0.15, lowRsiShort = 0.6)
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(model)
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any<String>(), any<String>(), any(), any()))
                .thenReturn(risingCandles(60, now))
                .thenReturn(fallingCandles(60, now))
            Mockito.`when`(blindSpotRepository.findByIsActiveTrue()).thenReturn(emptyList())
            Mockito.`when`(macroSnapshotRepository.findBetween(any(), any())).thenReturn(emptyList())
            Mockito
                .`when`(
                    macroContextService.fetch(),
                ).thenReturn(MacroContextService.MacroContext(BigDecimal("16"), BigDecimal("75"), BigDecimal("90")))
        }

        val result = runBlocking { service.screen(listOf("SBER", "GAZP"), topN = 1) }

        assertEquals("OK", result.mode)
        assertEquals(1, result.topN)
        assertEquals(1, result.candidates.size)
        // SBER (растущий тренд, rsi высокий) предпочтителен LONG, GAZP (нисходящий) — SHORT.
        assertEquals("SBER", result.candidates[0].ticker)
        assertEquals("LONG", result.candidates[0].direction)
        assertEquals(0.85, result.candidates[0].probability)
        // Каждый тикер прогнался в обоих направлениях.
        assertEquals(4, model.calls.size)
    }

    @Test
    fun `screen returns both candidates when topN exceeds count`() {
        config.enabled = true
        val now = LocalDateTime.now()
        val model = RecordingModel(longRsiProb = 0.85, shortRsiProb = 0.2, lowRsiLong = 0.15, lowRsiShort = 0.6)
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(model)
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any<String>(), any<String>(), any(), any()))
                .thenReturn(risingCandles(60, now))
                .thenReturn(fallingCandles(60, now))
            Mockito.`when`(blindSpotRepository.findByIsActiveTrue()).thenReturn(emptyList())
            Mockito.`when`(macroSnapshotRepository.findBetween(any(), any())).thenReturn(emptyList())
            Mockito
                .`when`(
                    macroContextService.fetch(),
                ).thenReturn(MacroContextService.MacroContext(BigDecimal("16"), BigDecimal("75"), BigDecimal("90")))
        }

        val result = runBlocking { service.screen(listOf("SBER", "GAZP"), topN = 5) }

        assertEquals(2, result.candidates.size)
        assertTrue(result.candidates[0].probability >= result.candidates[1].probability)
        assertEquals("SHORT", result.candidates[1].direction)
    }

    @Test
    fun `screen skips tickers with insufficient candle history`() {
        config.enabled = true
        val now = LocalDateTime.now()
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(RecordingModel(0.7, 0.3, 0.4, 0.6))
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any<String>(), any<String>(), any(), any()))
                .thenReturn(risingCandles(60, now))
                .thenReturn(fallingCandles(5, now))
            Mockito.`when`(blindSpotRepository.findByIsActiveTrue()).thenReturn(emptyList())
            Mockito.`when`(macroSnapshotRepository.findBetween(any(), any())).thenReturn(emptyList())
            Mockito
                .`when`(
                    macroContextService.fetch(),
                ).thenReturn(MacroContextService.MacroContext(BigDecimal("16"), BigDecimal("75"), BigDecimal("90")))
        }

        val result = runBlocking { service.screen(listOf("SBER", "GAZP"), topN = null) }

        assertEquals(1, result.candidates.size)
        assertEquals("SBER", result.candidates[0].ticker)
        assertEquals(listOf("GAZP"), result.skipped)
    }

    @Test
    fun `screen uses latest macro snapshot at or before now and sets blind spot flag`() {
        config.enabled = true
        val now = LocalDateTime.now()
        val model = RecordingModel(0.7, 0.3, 0.4, 0.6)
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(model)
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any<String>(), any<String>(), any(), any()))
                .thenReturn(risingCandles(60, now))
            Mockito
                .`when`(blindSpotRepository.findByIsActiveTrue())
                .thenReturn(
                    listOf(
                        BlindSpotEntity(
                            ticker = "SBER",
                            conditionPattern = "Entry at hour ${now.hour} for SBER",
                            lossRate = 0.7,
                            occurrenceCount = 3,
                            recommendation = "avoid",
                        ),
                    ),
                )
            Mockito
                .`when`(macroSnapshotRepository.findBetween(any(), any()))
                .thenReturn(
                    listOf(
                        MacroSnapshot(
                            capturedAt = now.minusMinutes(30),
                            cbrRate = BigDecimal("17.5"),
                            brentPrice = BigDecimal("80"),
                            usdRub = BigDecimal("95"),
                        ),
                    ),
                )
        }

        val result = runBlocking { service.screen(listOf("SBER"), topN = null) }

        assertEquals(1, result.candidates.size)
        assertEquals(1, result.candidates[0].inBlindSpotHour)
        assertEquals(now.hour, result.candidates[0].hourOfDay)
        // cbr_rate на позиции 9 числовых признаков — взят из снапшота, а не из фолбэка.
        assertEquals(17.5f, model.calls.first()[9])
        assertEquals(now.hour.toFloat(), model.calls.first()[14])
        runBlocking {
            Mockito.verify(macroContextService, Mockito.never()).fetch()
        }
    }

    @Test
    fun `screen falls back to current macro context when no snapshot`() {
        config.enabled = true
        val now = LocalDateTime.now()
        val model = RecordingModel(0.7, 0.3, 0.4, 0.6)
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(model)
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any<String>(), any<String>(), any(), any()))
                .thenReturn(risingCandles(60, now))
            Mockito.`when`(blindSpotRepository.findByIsActiveTrue()).thenReturn(emptyList())
            Mockito.`when`(macroSnapshotRepository.findBetween(any(), any())).thenReturn(emptyList())
            Mockito
                .`when`(
                    macroContextService.fetch(),
                ).thenReturn(MacroContextService.MacroContext(BigDecimal("16"), BigDecimal("75"), BigDecimal("90")))
        }

        val result = runBlocking { service.screen(listOf("SBER"), topN = null) }

        assertEquals(1, result.candidates.size)
        assertEquals(0, result.candidates[0].inBlindSpotHour)
        assertEquals(16.0f, model.calls.first()[9])
        runBlocking {
            Mockito.verify(macroContextService, Mockito.times(1)).fetch()
        }
    }

    @Test
    fun `screen returns 503 when model unavailable`() {
        config.enabled = true
        val now = LocalDateTime.now()
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(NoopMlModel("test outage"))
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any<String>(), any<String>(), any(), any()))
                .thenReturn(risingCandles(60, now))
            Mockito.`when`(blindSpotRepository.findByIsActiveTrue()).thenReturn(emptyList())
            Mockito.`when`(macroSnapshotRepository.findBetween(any(), any())).thenReturn(emptyList())
        }

        val ex =
            assertThrows<ResponseStatusException> {
                runBlocking { service.screen(listOf("SBER"), topN = null) }
            }

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.statusCode)
    }

    @Test
    fun `screen returns 400 when no tickers`() {
        config.enabled = true
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(RecordingModel(0.7, 0.3, 0.4, 0.6))
        }

        val ex =
            assertThrows<ResponseStatusException> {
                runBlocking { service.screen(emptyList(), topN = null) }
            }

        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        runBlocking {
            Mockito.verify(candleRepository, Mockito.never()).findByTickerAndTimeframeAndTimeBetween(any(), any(), any(), any())
        }
    }

    @Test
    fun `screen uses config topN by default`() {
        config.enabled = true
        config.screening.topN = 1
        val now = LocalDateTime.now()
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(RecordingModel(0.7, 0.3, 0.4, 0.6))
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any<String>(), any<String>(), any(), any()))
                .thenReturn(risingCandles(60, now))
            Mockito.`when`(blindSpotRepository.findByIsActiveTrue()).thenReturn(emptyList())
            Mockito.`when`(macroSnapshotRepository.findBetween(any(), any())).thenReturn(emptyList())
            Mockito
                .`when`(
                    macroContextService.fetch(),
                ).thenReturn(MacroContextService.MacroContext(BigDecimal("16"), BigDecimal("75"), BigDecimal("90")))
        }

        val result = runBlocking { service.screen(listOf("SBER", "GAZP"), topN = null) }

        assertEquals(1, result.candidates.size)
        assertNull(result.skipped.singleOrNull())
    }

    /** Модель, зависящая от rsi14 (numeric[0]) и направления (categorical[1]). */
    private class RecordingModel(
        private val longRsiProb: Double,
        private val shortRsiProb: Double,
        private val lowRsiLong: Double,
        private val lowRsiShort: Double,
    ) : MlModel {
        override val available: Boolean = true
        override val unavailableReason: String? = null
        val calls = mutableListOf<FloatArray>()

        override fun probability(
            numeric: FloatArray,
            categorical: Array<String>,
        ): Double {
            calls += numeric.copyOf()
            val long = categorical[1] == "LONG"
            return if (numeric[0] > 60.0f) {
                if (long) longRsiProb else shortRsiProb
            } else {
                if (long) lowRsiLong else lowRsiShort
            }
        }
    }

    private fun risingCandles(
        count: Int,
        endAt: LocalDateTime,
    ): List<Candle> = candles(count, endAt, rising = true)

    private fun fallingCandles(
        count: Int,
        endAt: LocalDateTime,
    ): List<Candle> = candles(count, endAt, rising = false)

    private fun candles(
        count: Int,
        endAt: LocalDateTime,
        rising: Boolean,
    ): List<Candle> =
        (0 until count).map { i ->
            val close = 100.0 + if (rising) 0.5 * i else -0.5 * i
            Candle(
                ticker = "SBER",
                timeframe = "MINUTE_10",
                openPrice = BigDecimal(close - 0.4),
                highPrice = BigDecimal(close + 0.5),
                lowPrice = BigDecimal(close - 0.5),
                closePrice = BigDecimal(close),
                volume = 1000L,
                time = endAt.minusMinutes(10L * (count - 1 - i)),
            )
        }
}
