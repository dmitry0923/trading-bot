package com.trading.bot.domain.ml

import com.trading.bot.domain.ml.MlFeatureExtractor.Features
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MlFeatureVectorTest {
    @Test
    fun `numeric features follow train py order`() {
        val vector = vector()

        val numeric = vector.numericFeatures()

        assertEquals(MlFeatureVector.NUMERIC_COUNT, numeric.size)
        // Порядок NUMERIC_FEATURES из ml/train.py: rsi14, atr_percent, macd_hist_percent,
        // bb_percent_b, ema_slope_percent, volatility20_percent, ret_3, ret_10, ret_20,
        // cbr_rate, brent, usd_rub, strategy_confidence, in_blind_spot_hour, hour_of_day.
        val expected =
            floatArrayOf(
                65.0f,
                1.2f,
                0.5f,
                80.0f,
                1.0f,
                2.5f,
                1.0f,
                2.0f,
                4.0f,
                16.0f,
                75.0f,
                90.0f,
                0.85f,
                1.0f,
                14.0f,
            )
        assertTrue(expected.contentEquals(numeric), "numeric order mismatch: ${numeric.joinToString()}")
    }

    @Test
    fun `categorical features follow train py order`() {
        val vector = vector()

        val categorical = vector.categoricalFeatures()

        assertEquals(MlFeatureVector.CATEGORICAL_COUNT, categorical.size)
        // CATEGORICAL_FEATURES из ml/train.py: strategy_action, direction.
        assertTrue(arrayOf("BUY", "LONG").contentEquals(categorical))
    }

    @Test
    fun `missing strategy confidence maps to NaN for screening`() {
        val vector = vector(strategyConfidence = null, strategyAction = "")

        val numeric = vector.numericFeatures()

        assertTrue(numeric[12].isNaN())
        assertEquals("", vector.strategyAction)
    }

    @Test
    fun `from maps features macro and context into vector`() {
        val features =
            Features(
                rsi14 = 42.0,
                atrPercent = 1.5,
                macdHistogramPercent = -0.2,
                bbPercentB = 30.0,
                emaSlopePercent = 0.5,
                volatility20Percent = 1.8,
                return3 = 0.5,
                return10 = 1.0,
                return20 = 2.0,
            )

        val vector =
            MlFeatureVector.from(
                features = features,
                cbrRate = BigDecimal("17.5"),
                brentPrice = BigDecimal("80"),
                usdRub = BigDecimal("95"),
                inBlindSpotHour = true,
                hourOfDay = 9,
                strategyAction = "",
                strategyConfidence = null,
                direction = "SHORT",
            )

        assertEquals(42.0, vector.rsi14)
        assertEquals(17.5, vector.cbrRate, 1e-9)
        assertEquals(95.0, vector.usdRub, 1e-9)
        assertEquals(1, vector.inBlindSpotHour)
        assertEquals(9, vector.hourOfDay)
        assertEquals("", vector.strategyAction)
        assertEquals("SHORT", vector.direction)
        assertTrue(vector.numericFeatures()[12].isNaN())
    }

    private fun vector(
        strategyConfidence: Double? = 0.85,
        strategyAction: String = "BUY",
    ): MlFeatureVector =
        MlFeatureVector(
            rsi14 = 65.0,
            atrPercent = 1.2,
            macdHistogramPercent = 0.5,
            bbPercentB = 80.0,
            emaSlopePercent = 1.0,
            volatility20Percent = 2.5,
            return3 = 1.0,
            return10 = 2.0,
            return20 = 4.0,
            cbrRate = 16.0,
            brentPrice = 75.0,
            usdRub = 90.0,
            strategyConfidence = strategyConfidence,
            inBlindSpotHour = 1,
            hourOfDay = 14,
            strategyAction = strategyAction,
            direction = "LONG",
        )
}
