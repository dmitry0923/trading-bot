--liquibase formatted sql
--changeset dmitry:008

-- Персистентные настройки бота: JSON-блоб по ключу "global".
-- Все настройки управляются через UI (GET/POST /api/v1/settings).
CREATE TABLE IF NOT EXISTS bot_settings (
    id BIGSERIAL PRIMARY KEY,
    settings_key VARCHAR(50) NOT NULL,
    settings_value TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_bot_settings_key UNIQUE (settings_key)
);
