package com.trading.bot.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.BotSettings
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * R2DBC-хранилище настроек бота (таблица bot_settings).
 *
 * Настройки сериализуются JSON-блобом в одну строку (ключ "global") —
 * простой вариант, при котором UI видит все настройки единым объектом.
 */
@Repository
class SettingsRepository(
    private val databaseClient: DatabaseClient,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private const val GLOBAL_KEY = "global"
    }

    suspend fun loadSettings(): BotSettings? {
        val value =
            databaseClient
                .sql("SELECT settings_value FROM bot_settings WHERE settings_key = :key")
                .bind("key", GLOBAL_KEY)
                .map { row, _ -> row.require("settings_value", String::class.java) }
                .one()
                .awaitSingleOrNull()
                ?: return null
        return runCatching { objectMapper.readValue(value, BotSettings::class.java) }.getOrNull()
    }

    suspend fun saveSettings(settings: BotSettings) {
        val json = objectMapper.writeValueAsString(settings)
        databaseClient
            .sql(
                """
                INSERT INTO bot_settings (settings_key, settings_value, updated_at)
                VALUES (:key, :value, :updatedAt)
                ON CONFLICT (settings_key) DO UPDATE
                    SET settings_value = EXCLUDED.settings_value, updated_at = EXCLUDED.updated_at
                """.trimIndent(),
            ).bind("key", GLOBAL_KEY)
            .bind("value", json)
            .bind("updatedAt", LocalDateTime.now())
            .then()
            .awaitSingleOrNull()
    }
}
