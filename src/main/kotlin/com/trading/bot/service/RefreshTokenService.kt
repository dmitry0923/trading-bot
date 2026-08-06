package com.trading.bot.service

import com.trading.bot.config.security.JwtProperties
import com.trading.bot.model.RefreshToken
import com.trading.bot.repository.RefreshTokenRepository
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

/**
 * Управление непрозрачными refresh-токенами.
 *
 * - Хранится только SHA-256 хеш токена;
 * - каждый refresh ротирует токен (старый помечается revoked + replacedBy);
 * - повторное использование уже ротированного токена = компрометация,
 *   вся сессия пользователя отзывается ([RefreshTokenRepository.revokeAllForUser]).
 */
@Service
class RefreshTokenService(
    private val repository: RefreshTokenRepository,
    private val properties: JwtProperties,
) {
    private val secureRandom = SecureRandom()

    suspend fun issue(
        username: String,
        roles: List<String>,
    ): String {
        val raw = generateToken()
        val now = LocalDateTime.now()
        val token =
            RefreshToken(
                id = UUID.randomUUID(),
                tokenHash = hash(raw),
                username = username,
                roles = roles.joinToString(","),
                issuedAt = now,
                expiresAt = now.plusDays(properties.refreshTtlDays),
                revoked = false,
                replacedBy = null,
            )
        repository.save(token)
        return raw
    }

    /**
     * Ротация refresh-токена. Возвращает новую пару username/roles и новый токен,
     * либо null если токен невалиден/истёк/отозван (или обнаружен reuse).
     */
    suspend fun rotate(raw: String): RotatedRefresh? {
        val existing = repository.findByTokenHash(hash(raw)) ?: return null
        if (existing.replacedBy != null) {
            repository.revokeAllForUser(existing.username)
            return null
        }
        if (existing.revoked) return null
        if (existing.expiresAt.isBefore(LocalDateTime.now())) {
            repository.revoke(existing.id)
            return null
        }
        val newRaw = generateToken()
        val now = LocalDateTime.now()
        val newToken =
            RefreshToken(
                id = UUID.randomUUID(),
                tokenHash = hash(newRaw),
                username = existing.username,
                roles = existing.roles,
                issuedAt = now,
                expiresAt = now.plusDays(properties.refreshTtlDays),
                revoked = false,
                replacedBy = null,
            )
        repository.save(newToken)
        repository.markRotated(existing.id, newToken.id)
        return RotatedRefresh(newRaw, existing.username, existing.roles.split(","))
    }

    suspend fun revoke(raw: String) {
        val existing = repository.findByTokenHash(hash(raw)) ?: return
        if (!existing.revoked) repository.revoke(existing.id)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(48)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    data class RotatedRefresh(
        val value: String,
        val username: String,
        val roles: List<String>,
    )
}
