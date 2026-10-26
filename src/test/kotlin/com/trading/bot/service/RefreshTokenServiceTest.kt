package com.trading.bot.service

import com.trading.bot.config.security.JwtProperties
import com.trading.bot.model.entity.RefreshToken
import com.trading.bot.repository.RefreshTokenRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.util.UUID

/**
 * Ротация refresh-токена: победитель ровно один.
 *
 * Без атомарного claim два параллельных `POST /auth/refresh` с одним токеном оба
 * проходили проверки и выпускали по валидному refresh-токену, а ветка reuse-detection
 * не срабатывала никогда (скан-4 P2).
 */
class RefreshTokenServiceTest {
    private val repository: RefreshTokenRepository = mock()
    private val properties = JwtProperties().apply { secret = "x".repeat(32) }
    private val service = RefreshTokenService(repository, properties)

    private fun activeToken() =
        RefreshToken(
            id = UUID.randomUUID(),
            tokenHash = "hash",
            username = "trader",
            roles = "ADMIN",
            issuedAt = LocalDateTime.now().minusHours(1),
            expiresAt = LocalDateTime.now().plusDays(1),
            revoked = false,
            replacedBy = null,
        )

    @Test
    fun `rotate issues a new token when the claim succeeds`(): Unit =
        runBlocking {
            val existing = activeToken()
            whenever(repository.findByTokenHash(any())).thenReturn(existing)
            whenever(repository.markRotated(any(), any())).thenReturn(true)

            val rotated = service.rotate("raw-refresh")

            assertNotNull(rotated)
            assertEquals("trader", rotated!!.username)
            assertEquals(listOf("ADMIN"), rotated.roles)
            verify(repository).save(any())
        }

    @Test
    fun `concurrent rotation loser revokes the whole session`(): Unit =
        runBlocking {
            // Проигравший гонку не должен выпускать второй валидный токен: признак reuse.
            val existing = activeToken()
            whenever(repository.findByTokenHash(any())).thenReturn(existing)
            whenever(repository.markRotated(any(), any())).thenReturn(false)

            val rotated = service.rotate("raw-refresh")

            assertNull(rotated, "claim не захвачен ⇒ новый токен не выпускается")
            verify(repository, never()).save(any())
            verify(repository).revokeAllForUser("trader")
        }

    @Test
    fun `already rotated token is treated as reuse and revokes the session`(): Unit =
        runBlocking {
            val existing = activeToken().copy(revoked = true, replacedBy = UUID.randomUUID())
            whenever(repository.findByTokenHash(any())).thenReturn(existing)

            assertNull(service.rotate("raw-refresh"))
            verify(repository).revokeAllForUser("trader")
            verify(repository, never()).markRotated(any(), any())
        }

    @Test
    fun `expired refresh token is revoked and not rotated`(): Unit =
        runBlocking {
            val existing = activeToken().copy(expiresAt = LocalDateTime.now().minusMinutes(1))
            whenever(repository.findByTokenHash(any())).thenReturn(existing)

            assertNull(service.rotate("raw-refresh"))
            verify(repository).revoke(existing.id)
            verify(repository, never()).markRotated(any(), any())
        }

    @Test
    fun `unknown token is not rotated`(): Unit =
        runBlocking {
            whenever(repository.findByTokenHash(any())).thenReturn(null)

            assertNull(service.rotate("raw-refresh"))
            verify(repository, never()).markRotated(any(), any())
        }
}
