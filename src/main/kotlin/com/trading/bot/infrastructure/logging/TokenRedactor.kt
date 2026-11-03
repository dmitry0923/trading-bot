package com.trading.bot.infrastructure.logging

/**
 * Маскирование секретов в тексте логов (P2-1 аудита).
 *
 * Alor требует передавать refresh-токен в query (`oauth.alor.ru/refresh?token=…`)
 * и access-токен в URL WebSocket (`wss://…?token=…`) — это протокол, убрать
 * токен из URL нельзя. Но exception-сообщения WebClient/Netty включают полный
 * URI, поэтому перед логированием токен обязан быть заменён на `***`.
 *
 * Маскируются:
 * - query-параметры `token`, `access_token`, `refresh_token`, `api_key`;
 * - заголовок `Bearer <token>` (если попал в текст).
 */
object TokenRedactor {
    private const val MASK = "***"

    private val QUERY_SECRET =
        Regex(
            "([?&](?:token|access_token|refresh_token|api[_-]?key)=)[^&\\s\"']+",
            RegexOption.IGNORE_CASE,
        )

    private val BEARER = Regex("(Bearer\\s+)[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE)

    fun redact(text: String?): String? {
        if (text == null) return null
        return text
            .replace(QUERY_SECRET, "$1$MASK")
            .replace(BEARER, "$1$MASK")
    }

    /** Безопасное описание ошибки для лога: класс + очищенное сообщение. */
    fun describe(error: Throwable): String = "${error::class.simpleName}: ${redact(error.message) ?: "no message"}"
}
