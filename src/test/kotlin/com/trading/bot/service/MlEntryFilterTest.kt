package com.trading.bot.service

import com.trading.bot.config.MlConfig
import com.trading.bot.domain.ml.MlFeatureVector
import com.trading.bot.domain.signal.Signal
import com.trading.bot.model.StrategyAction
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
import org.mockito.Mockito
import org.mockito.kotlin.any
import java.math.BigDecimal
import java.time.LocalDateTime

class MlEntryFilterTest {
    private val config = MlConfig()
    private val modelProvider = Mockito.mock(MlModelProvider::class.java)
    private val featureResolver = Mockito.mock(MlFeatureResolver::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val filter = MlEntryFilter(config, modelProvider, featureResolver, meterRegistry)

    @BeforeEach
    fun reset() {
        Mockito.reset(modelProvider, featureResolver)
    }

    @Test
    fun `allows when ml disabled without touching model or resolver`() {
        config.enabled = false
        config.filter.enabled = true

        assertNull(runBlocking { filter.shouldBlock(signal()) })

        runBlocking {
            Mockito.verify(featureResolver, Mockito.never()).resolve(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `allows when filter disabled even if ml enabled`() {
        config.enabled = true
        config.filter.enabled = false

        assertNull(runBlocking { filter.shouldBlock(signal()) })

        runBlocking {
            Mockito.verify(featureResolver, Mockito.never()).resolve(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `allows non trading action`() {
        config.enabled = true
        config.filter.enabled = true
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(RecordingModel(0.9))
        }

        assertNull(runBlocking { filter.shouldBlock(signal(StrategyAction.HOLD)) })

        runBlocking {
            Mockito.verify(featureResolver, Mockito.never()).resolve(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `core method default respects enabled flags`() {
        config.enabled = false
        config.filter.enabled = false

        val reason = runBlocking { filter.shouldBlock("SBER", StrategyAction.BUY, 0.8, LocalDateTime.now()) }

        assertNull(reason)
        runBlocking {
            Mockito.verify(featureResolver, Mockito.never()).resolve(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `backtest override runs filter even when global filter disabled`() {
        config.enabled = false
        config.filter.enabled = false
        val at = LocalDateTime.of(2026, 8, 12, 14, 0)
        val model = RecordingModel(0.9)
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(model)
            Mockito.`when`(featureResolver.resolve(any(), any(), any(), any(), any())).thenReturn(vector())
        }

        val reason = runBlocking { filter.shouldBlock("SBER", StrategyAction.BUY, 0.8, at, requireEnabled = false) }

        assertNull(reason)
        runBlocking {
            Mockito.verify(featureResolver).resolve("SBER", at, "BUY", 0.8, "LONG")
        }
    }

    @Test
    fun `blocks fail closed when model unavailable`() {
        config.enabled = true
        config.filter.enabled = true
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(NoopMlModel("file not found"))
        }

        val reason = runBlocking { filter.shouldBlock(signal()) }

        assertTrue(reason != null)
        assertTrue(reason!!.contains("file not found"))
        assertTrue(filterResultCount("FAIL_CLOSED") > 0)
    }

    @Test
    fun `blocks fail closed when features cannot be resolved`() {
        config.enabled = true
        config.filter.enabled = true
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(RecordingModel(0.9))
            Mockito.`when`(featureResolver.resolve(any(), any(), any(), any(), any())).thenReturn(null)
        }

        val reason = runBlocking { filter.shouldBlock(signal()) }

        assertTrue(reason != null)
        assertTrue(reason!!.contains("insufficient candle data"))
    }

    @Test
    fun `blocks when probability below threshold and passes above`() {
        config.enabled = true
        config.filter.enabled = true
        config.filter.threshold = 0.5
        runBlocking {
            Mockito.`when`(featureResolver.resolve(any(), any(), any(), any(), any())).thenReturn(vector())
        }

        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(RecordingModel(0.4))
            val rejected = filter.shouldBlock(signal())
            assertTrue(rejected != null)
            assertTrue(rejected!!.contains("below threshold 0.5"))

            Mockito.`when`(modelProvider.model).thenReturn(RecordingModel(0.5))
            assertNull(filter.shouldBlock(signal()))
        }

        assertEquals(1.0, filterResultCount("REJECT"))
        assertEquals(1.0, filterResultCount("PASS"))
    }

    @Test
    fun `uses signal action signalStrength and direction in prediction`() {
        config.enabled = true
        config.filter.enabled = true
        val model = RecordingModel(0.9)
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(model)
            Mockito.`when`(featureResolver.resolve(any(), any(), any(), any(), any())).thenReturn(vector())
        }

        runBlocking { filter.shouldBlock(signal()) }

        assertEquals(1, model.calls.size)
        // categorical[0] = strategy_action, categorical[1] = direction.
        assertEquals("BUY", model.calls.first().second[0])
        assertEquals("LONG", model.calls.first().second[1])
        // numeric[12] = strategy_signal_strength.
        assertEquals(0.8f, model.calls.first().first[12])
    }

    @Test
    fun `trend gate blocks when trend score below min`() {
        config.enabled = true
        config.filter.enabled = true
        config.filter.trendGateEnabled = true
        config.filter.trendMinScore = 0.8
        // Вектор с нейтральными индикаторами: trendScore = 0.6 * 0.9 + 0.4 * 0.5 = 0.74 < 0.8.
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(RecordingModel(0.9))
            Mockito.`when`(featureResolver.resolve(any(), any(), any(), any(), any())).thenReturn(vector())
        }

        val reason = runBlocking { filter.shouldBlock(signal()) }

        assertTrue(reason != null)
        assertTrue(reason!!.contains("trend score"))
        assertEquals(1.0, filterResultCount("REJECT"))
    }

    @Test
    fun `trend gate passes when trend score above min`() {
        config.enabled = true
        config.filter.enabled = true
        config.filter.trendGateEnabled = true
        config.filter.trendMinScore = 0.7
        // trendScore = 0.74 >= 0.7.
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(RecordingModel(0.9))
            Mockito.`when`(featureResolver.resolve(any(), any(), any(), any(), any())).thenReturn(vector())
        }

        assertNull(runBlocking { filter.shouldBlock(signal()) })
        assertEquals(1.0, filterResultCount("PASS"))
    }

    @Test
    fun `trend gate is not applied when disabled`() {
        config.enabled = true
        config.filter.enabled = true
        config.filter.trendGateEnabled = false
        config.filter.trendMinScore = 0.9
        // Вероятность 0.5 проходит порог 0.5, гейт выключен — PASS даже при низком trendScore (0.5).
        runBlocking {
            Mockito.`when`(modelProvider.model).thenReturn(RecordingModel(0.5))
            Mockito.`when`(featureResolver.resolve(any(), any(), any(), any(), any())).thenReturn(vector())
        }

        assertNull(runBlocking { filter.shouldBlock(signal()) })
        assertEquals(1.0, filterResultCount("PASS"))
    }

    private fun signal(action: StrategyAction = StrategyAction.BUY): Signal =
        Signal(
            ticker = "SBER",
            action = action,
            targetPrice = BigDecimal("100"),
            signalStrength = 0.8,
            reasoning = "test",
            timeframe = "MINUTE_10",
            cycleId = "cycle-1",
        )

    private fun vector(): MlFeatureVector =
        MlFeatureVector(
            rsi14 = 50.0,
            atrPercent = 1.0,
            macdHistogramPercent = 0.0,
            bbPercentB = 50.0,
            emaSlopePercent = 0.0,
            volatility20Percent = 1.0,
            return3 = 0.0,
            return10 = 0.0,
            return20 = 0.0,
            cbrRate = 16.0,
            brentPrice = 75.0,
            usdRub = 90.0,
            strategySignalStrength = 0.8,
            inBlindSpotHour = 0,
            hourOfDay = 14,
            strategyAction = "BUY",
            direction = "LONG",
        )

    private fun filterResultCount(result: String): Double =
        meterRegistry
            .counter(
                "ml.entry.filter",
                io.micrometer.core.instrument.Tags
                    .of("ticker", "SBER", "result", result),
            ).count()

    private class RecordingModel(
        private val probability: Double,
    ) : MlModel {
        override val available: Boolean = true
        override val unavailableReason: String? = null
        val calls = mutableListOf<Pair<FloatArray, Array<String>>>()

        override fun probability(
            numeric: FloatArray,
            categorical: Array<String>,
        ): Double {
            calls += numeric to categorical
            return probability
        }
    }
}
