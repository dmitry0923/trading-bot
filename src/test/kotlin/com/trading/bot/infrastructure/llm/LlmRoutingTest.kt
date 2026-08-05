package com.trading.bot.infrastructure.llm

import com.trading.bot.config.LlmConfig
import com.trading.bot.config.LlmProvider
import com.trading.bot.model.BotSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Гибкое подключение LLM: переключение провайдера (RouterAI / Kimi / DeepSeek / Qwen)
 * через настройки, резолв базового URL и модели по умолчанию.
 */
class LlmRoutingTest {
    @Test
    fun `endpointFor returns provider specific base url and model`() {
        val config = LlmConfig()

        assertEquals("https://routerai.ru/api/v1" to "auto", config.endpointFor(LlmProvider.ROUTER_AI))
        assertEquals("https://api.moonshot.cn/v1" to "kimi-k3", config.endpointFor(LlmProvider.KIMI))
        assertEquals("https://api.deepseek.com/v1" to "deepseek-chat", config.endpointFor(LlmProvider.DEEPSEEK))
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1" to "qwen-plus", config.endpointFor(LlmProvider.QWEN))
    }

    @Test
    fun `bot settings parses provider from string`() {
        assertEquals(LlmProvider.DEEPSEEK, BotSettings(llmProvider = "DEEPSEEK").llmProvider())
        assertEquals(LlmProvider.KIMI, BotSettings(llmProvider = "KIMI").llmProvider())
        assertEquals(LlmProvider.QWEN, BotSettings(llmProvider = "QWEN").llmProvider())
        assertEquals(LlmProvider.ROUTER_AI, BotSettings(llmProvider = "ROUTER_AI").llmProvider())
    }

    @Test
    fun `unknown provider resolves to null and falls back to config`() {
        assertNull(BotSettings(llmProvider = "GPT-5").llmProvider())
    }

    @Test
    fun `empty provider resolves to null and falls back to config default`() {
        assertNull(BotSettings(llmProvider = "").llmProvider())
    }
}
