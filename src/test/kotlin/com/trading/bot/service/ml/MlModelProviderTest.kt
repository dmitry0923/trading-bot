package com.trading.bot.service.ml

import com.trading.bot.config.MlConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class MlModelProviderTest {
    @Test
    fun `returns noop when ml disabled`() {
        val config = MlConfig().apply { enabled = false }

        val model = MlModelProvider(config).load()

        assertFalse(model.available)
        assertEquals("ml.enabled=false", model.unavailableReason)
    }

    @Test
    fun `returns noop when model file missing`() {
        val missing = Files.createTempDirectory("ml-provider").resolve("missing.cbm")
        val config =
            MlConfig().apply {
                enabled = true
                model.path = missing.toString()
            }

        val model = MlModelProvider(config).load()

        assertFalse(model.available)
        assertTrue(model.unavailableReason!!.contains(missing.toString()))
    }
}
