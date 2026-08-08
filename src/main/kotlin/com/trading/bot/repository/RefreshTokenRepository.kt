package com.trading.bot.repository

import com.trading.bot.infrastructure.db.bindOrNull
import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.RefreshToken
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

@Repository
class RefreshTokenRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toRefreshToken(row: Row): RefreshToken =
        RefreshToken(
            id = row.require("id", UUID::class.java),
            tokenHash = row.require("token_hash", String::class.java),
            username = row.require("username", String::class.java),
            roles = row.require("roles", String::class.java),
            issuedAt = row.require("issued_at", LocalDateTime::class.java),
            expiresAt = row.require("expires_at", LocalDateTime::class.java),
            revoked = row.require("revoked", Boolean::class.java),
            replacedBy = row.get("replaced_by", UUID::class.java),
        )

    suspend fun save(token: RefreshToken) {
        databaseClient
            .sql(
                """
                INSERT INTO refresh_tokens (id, token_hash, username, roles, issued_at, expires_at, revoked, replaced_by)
                VALUES (:id, :tokenHash, :username, :roles, :issuedAt, :expiresAt, :revoked, :replacedBy)
                """.trimIndent(),
            ).bind("id", token.id)
            .bind("tokenHash", token.tokenHash)
            .bind("username", token.username)
            .bind("roles", token.roles)
            .bind("issuedAt", token.issuedAt)
            .bind("expiresAt", token.expiresAt)
            .bind("revoked", token.revoked)
            .bindOrNull("replacedBy", token.replacedBy)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun findByTokenHash(tokenHash: String): RefreshToken? =
        databaseClient
            .sql("SELECT * FROM refresh_tokens WHERE token_hash = :tokenHash")
            .bind("tokenHash", tokenHash)
            .map { row, _ -> toRefreshToken(row) }
            .one()
            .awaitSingleOrNull()

    suspend fun markRotated(
        id: UUID,
        replacedBy: UUID,
    ) {
        databaseClient
            .sql(
                "UPDATE refresh_tokens SET revoked = TRUE, replaced_by = :replacedBy WHERE id = :id",
            ).bind("replacedBy", replacedBy)
            .bind("id", id)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun revoke(id: UUID) {
        databaseClient
            .sql("UPDATE refresh_tokens SET revoked = TRUE WHERE id = :id")
            .bind("id", id)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun revokeAllForUser(username: String) {
        databaseClient
            .sql(
                "UPDATE refresh_tokens SET revoked = TRUE WHERE username = :username AND revoked = FALSE",
            ).bind("username", username)
            .then()
            .awaitSingleOrNull()
    }
}
