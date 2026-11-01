package com.trading.bot.domain.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnlineLogisticRegressionTest {
    @Test
    fun `learns linear separable direction`() {
        val model = OnlineLogisticRegression(featureCount = 2, learningRate = 0.1, l2 = 0.0001)
        repeat(2000) {
            model.update(doubleArrayOf(-1.0, 1.0), 1.0)
            model.update(doubleArrayOf(1.0, -1.0), 0.0)
            model.update(doubleArrayOf(-2.0, 2.0), 1.0)
            model.update(doubleArrayOf(2.0, -2.0), 0.0)
        }
        assertTrue(model.predict(doubleArrayOf(-1.0, 1.0)) > 0.9)
        assertTrue(model.predict(doubleArrayOf(1.0, -1.0)) < 0.1)
        assertTrue(model.trainedSamples > 0)
    }

    @Test
    fun `reset zeroes weights deterministically`() {
        val model = OnlineLogisticRegression(featureCount = 2, learningRate = 0.1, l2 = 0.0)
        model.update(doubleArrayOf(1.0, 2.0), 1.0)
        assertEquals(1, model.trainedSamples)
        model.reset()
        assertEquals(0, model.trainedSamples)
        assertEquals(0.5, model.predict(doubleArrayOf(1.0, 2.0)))
    }

    @Test
    fun `invalid label and a mismatched feature vector are rejected`() {
        val model = OnlineLogisticRegression(featureCount = 2, learningRate = 0.1, l2 = 0.0)

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            model.update(doubleArrayOf(1.0, 1.0), 2.0)
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            model.update(doubleArrayOf(1.0), 1.0)
        }
    }

    @Test
    fun `weight norm starts at zero and grows after a sample`() {
        val model = OnlineLogisticRegression(featureCount = 2, learningRate = 0.1, l2 = 0.0)

        assertEquals(0.0, model.weightNorm())
        model.update(doubleArrayOf(1.0, 0.0), 1.0)
        assertTrue(model.weightNorm() > 0.0)
    }

    @Test
    fun `requires feature count match`() {
        val model = OnlineLogisticRegression(featureCount = 2, learningRate = 0.1, l2 = 0.0)
        val thrown =
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                model.predict(doubleArrayOf(1.0))
            }
        assertTrue(thrown.message!!.contains("2 features"))
    }
}
