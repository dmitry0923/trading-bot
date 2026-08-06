--liquibase formatted sql
--changeset dmitry:011

-- Хранение refresh-токенов для JWT-аутентификации.
-- Хранится только SHA-256 хеш токена, никогда сам токен.
-- rotate / rotation reuse detection: при повторном использовании скомпрометированного
-- токена вся сессия отзывается (все токены пользователя помечаются revoked).
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL,
    username VARCHAR(100) NOT NULL,
    roles VARCHAR(200) NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    replaced_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_username ON refresh_tokens(username);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires ON refresh_tokens(expires_at);
