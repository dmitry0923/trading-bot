package com.trading.bot.model.entity

import java.time.LocalDateTime
import java.util.UUID

/**
 * Запись refresh-токена. Хранится только SHA-256 хеш ([tokenHash]),
 * сам токен никогда не персистится.
 *
 * Поля [revoked] и [replacedBy] используются для ротации:
 * при успешном refresh старый токен помечается revoked и получает
 * ссылку на новый (replacedBy). Повторное использование уже заменённого
 * токена трактуется как компрометация — вся сессия пользователя отзывается.
 */
data class RefreshToken(
    val id: UUID,
    val tokenHash: String,
    val username: String,
    val roles: String,
    val issuedAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val revoked: Boolean,
    val replacedBy: UUID?,
)
