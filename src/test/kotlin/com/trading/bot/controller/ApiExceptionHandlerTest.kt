package com.trading.bot.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler

/**
 * Контракт ошибок API: ошибка входных данных — 4xx, а не 500.
 *
 * Регресс на реальный дефект: `require(...)`/`requireSpec(...)` в сервисах бросают
 * `IllegalArgumentException`, и без advice они доходили до клиента как
 * `500 Internal Server Error` (docs/28 §3.1 №34/№38). Проверяем и то, что реальный
 * сбой (`IllegalStateException`) не маскируется под клиентскую ошибку.
 */
class ApiExceptionHandlerTest {
    private val handler = ApiExceptionHandler()

    @Test
    fun `illegal argument becomes 400 with the message`() {
        val response = handler.handleIllegalArgument(IllegalArgumentException("Unknown instrument: NOSUCHTICKER"))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Unknown instrument: NOSUCHTICKER", response.body?.get("error"))
    }

    @Test
    fun `illegal argument without message still returns a body`() {
        val response = handler.handleIllegalArgument(IllegalArgumentException())

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals(HttpStatus.BAD_REQUEST.reasonPhrase, response.body?.get("error"))
    }

    @Test
    fun `only invalid input types are mapped so real failures keep 500`() {
        val mapped =
            ApiExceptionHandler::class.java.methods
                .mapNotNull { it.getAnnotation(ExceptionHandler::class.java) }
                .flatMap { it.value.map { type -> type.java.name } }
                .toSet()

        assertEquals(
            setOf(IllegalArgumentException::class.java.name, java.time.DateTimeException::class.java.name),
            mapped,
            "IllegalStateException и прочие не должны превращаться в 4xx",
        )
    }

    @Test
    fun `malformed date time becomes 400 instead of 500`() {
        val response = handler.handleDateTime(java.time.format.DateTimeParseException("bad time", "18-45", 0))

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body!!["error"]!!.toString().contains("bad time", ignoreCase = true))
    }
}
