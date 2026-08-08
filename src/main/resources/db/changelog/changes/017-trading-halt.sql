--liquibase formatted sql
--changeset dmitry:017

-- Последняя глобальная остановка торговли (одна строка, id = 1).
-- Причина останова персистится, чтобы пережить рестарт приложения.
CREATE TABLE IF NOT EXISTS trading_halt (
    id BIGSERIAL PRIMARY KEY,
    reason VARCHAR(50) NOT NULL,
    source VARCHAR(50) NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    halted_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
