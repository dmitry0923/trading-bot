package com.trading.bot.config

/**
 * Поддерживаемые LLM-провайдеры.
 *
 * По умолчанию бот работает через агрегатор RouterAI ([ROUTER_AI]) — единая точка
 * доступа к десяткам моделей по одному ключу. При необходимости можно переключиться
 * на прямые API Kimi (Moonshot), DeepSeek или Qwen (DashScope) — все они OpenAI-совместимы.
 */
enum class LlmProvider(
    val displayName: String,
) {
    ROUTER_AI("RouterAI (агрегатор)"),
    KIMI("Kimi (Moonshot)"),
    DEEPSEEK("DeepSeek"),
    QWEN("Qwen (Alibaba DashScope)"),
}
