--liquibase formatted sql
--changeset dmitry:023

-- Исторические макро-снапшоты (roadmap v2.4, раздел 13.11.2).
--
-- macro_snapshots — периодические слепки макро-контекста (ставка ЦБ, Brent,
-- USD/RUB), собираемые фоном (MacroSnapshotCollector). Используются экспортом
-- ML-датасета вместо «текущего» контекста: для обучающей строки берётся последний
-- снапшот с captured_at <= openedAt позиции — без lookahead-утечки (это была
-- задокументированная проблема шага 13.11.1: макро брались снапшотом на момент
-- экспорта, т.е. будущим относительно входа).

CREATE TABLE IF NOT EXISTS macro_snapshots (
    id BIGSERIAL PRIMARY KEY,
    captured_at TIMESTAMP NOT NULL,
    cbr_rate NUMERIC(10, 4) NOT NULL,
    brent_price NUMERIC(10, 4) NOT NULL,
    usd_rub NUMERIC(12, 4) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_macro_snapshots_captured_at ON macro_snapshots(captured_at);
