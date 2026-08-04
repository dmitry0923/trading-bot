package com.trading.bot.infrastructure.db

import io.r2dbc.spi.Row
import org.springframework.r2dbc.core.DatabaseClient

/**
 * Хелперы для реактивных репозиториев на [DatabaseClient].
 *
 * - [bindOrNull] позволяет биндить nullable-значения: для null вызывается
 *   `bindNull`, который отправляет в БД NULL с корректным Java-типом.
 * - [Row.require] читает NOT NULL колонку и разворачивает nullable из
 *   `io.r2dbc.spi.Row.get` (при неожиданном NULL падает с IllegalStateException).
 */
@Suppress("ReifiedTypeParameterNoArgType")
internal inline fun <reified T : Any> DatabaseClient.GenericExecuteSpec.bindOrNull(
    name: String,
    value: T?
): DatabaseClient.GenericExecuteSpec =
    if (value == null) bindNull(name, T::class.java) else bind(name, value)

internal fun <T : Any> Row.require(name: String, type: Class<T>): T =
    get(name, type) ?: throw IllegalStateException("Column '$name' is NULL, but NOT NULL expected")
