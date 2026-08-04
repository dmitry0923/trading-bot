package com.trading.bot.infrastructure.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptRegistryTest {

    @Test
    fun `loads all prompt files from classpath`() {
        val registry = PromptRegistry()
        registry.load()

        val names = registry.availableNames()
        assertTrue("technical-analysis" in names, "actual: $names")
        assertTrue("strategy" in names)
        assertTrue("contrarian" in names)
        assertTrue("arbitrator" in names)
        assertTrue("fundamental-analysis" in names)
        assertTrue("performance-feedback" in names)
    }

    @Test
    fun `getTemplate returns default version`() {
        val registry = PromptRegistry()
        registry.load()
        val template = registry.getTemplate("technical-analysis")
        assertEquals("technical-analysis", template.name)
        assertEquals("default", template.version)
        assertNotNull(template.system)
        assertNotNull(template.userTemplate)
    }

    @Test
    fun `getTemplate supports versioning`() {
        val registry = PromptRegistry()
        registry.load()
        val conservative = registry.getTemplate("strategy", PromptRegistry.CONSERVATIVE_VERSION)
        assertEquals("conservative", conservative.version)
    }

    @Test
    fun `missing version falls back to default`() {
        val registry = PromptRegistry()
        registry.load()
        val template = registry.getTemplate("strategy", "non-existent-version")
        assertEquals("default", template.version)
    }

    @Test
    fun `rendered user prompt contains substituted variables`() {
        val registry = PromptRegistry()
        registry.load()
        val template = registry.getTemplate("technical-analysis")
        val rendered = template.renderUser(
            mapOf(
                "ticker" to "SBER",
                "currentPrice" to "280.50",
                "rsi" to "62",
                "atr" to "1.5",
                "macdHistogram" to "0.3",
                "bbLower" to "275",
                "bbMiddle" to "280",
                "bbUpper" to "285",
                "trend" to "UP",
                "volume" to "100000",
                "timeframe" to "MINUTE_10"
            )
        )
        assertTrue(rendered.contains("SBER"))
        assertTrue(rendered.contains("280.50"))
        assertTrue(rendered.contains("62"))
    }

    @Test
    fun `unknown template throws`() {
        val registry = PromptRegistry()
        registry.load()
        try {
            registry.getTemplate("does-not-exist")
            throw AssertionError("Expected NoSuchElementException")
        } catch (e: NoSuchElementException) {
            assertTrue(e.message!!.contains("does-not-exist"))
        }
    }
}
