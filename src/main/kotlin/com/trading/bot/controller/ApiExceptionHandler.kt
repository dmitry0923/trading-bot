package com.trading.bot.controller

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Единый контракт ошибок входных данных: `IllegalArgumentException` → **400**, а не 500.
 *
 * До этого класса ошибка пользователя (неизвестный тикер юниверса, пустой список
 * инструментов, `holdoutFraction` вне `(0,1)`, пустой ряд свечей) доходила до клиента
 * как `500 Internal Server Error` — неотличимо от сбоя сервера. Найдено при написании
 * тестов (docs/28 §3.1 №34 и №38).
 *
 * `ResponseStatusException` не перехватывается: у него уже есть собственный статус.
 * `IllegalStateException` намеренно НЕ маппится — она используется и внутри фреймворка,
 * и подмена реального 500 на 4xx скрыла бы настоящую поломку.
 */
@RestControllerAdvice
class ApiExceptionHandler {
    private val logger = KotlinLogging.logger {}

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<Map<String, Any?>> {
        val message = ex.message ?: HttpStatus.BAD_REQUEST.reasonPhrase
        logger.warn { "API 400 (invalid input): $message" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to message))
    }
}
