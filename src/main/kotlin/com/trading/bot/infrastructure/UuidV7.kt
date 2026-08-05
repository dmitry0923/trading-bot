package com.trading.bot.infrastructure

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * Генератор UUIDv7 (RFC 9562).
 *
 * UUIDv7 — time-ordered UUID: первые 48 бит — Unix timestamp в миллисекундах,
 * далее 4 бита версии (0111 = 7), 12 случайных бит, 2 бита варианта (10) и 62 случайных бита.
 * Сохраняет хронологический порядок генерации, что улучшает локальность индексов БД.
 */
object UuidV7 {
    private val random = SecureRandom()

    fun uuid(): UUID {
        val millis = Instant.now().toEpochMilli()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        bytes[0] = (millis ushr 40).toByte()
        bytes[1] = (millis ushr 32).toByte()
        bytes[2] = (millis ushr 24).toByte()
        bytes[3] = (millis ushr 16).toByte()
        bytes[4] = (millis ushr 8).toByte()
        bytes[5] = millis.toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    fun uuidString(): String = uuid().toString()
}
