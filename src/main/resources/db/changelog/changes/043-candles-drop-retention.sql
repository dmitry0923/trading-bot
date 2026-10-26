--liquibase formatted sql
--changeset dmitry:043 splitStatements:false

-- Снятие retention-политики с `candles` (скан-6 P1).
--
-- Миграция 012 ставила `add_retention_policy('candles', INTERVAL '90 days')`, и её никто
-- не снимал: на чистой БД (новый деплой/CI/docker-compose) фоновый job TimescaleDB
-- удалял свечи старше 90 дней. `/backtest?days=730` и `/validate` при этом молча
-- возвращали ~90 дней истории — прогон выглядел «посчитанным», но на огрызке данных.
--
-- `microstructure_snapshots` (миграция 037) retention сохраняет: это forward-наблюдения,
-- глубокая история там не нужна.
--
-- Идемпотентно: `if_exists => true` не падает, если политики уже нет.
SELECT remove_retention_policy('candles', if_exists => true);

-- Ставим маркер в лог миграций, чтобы факт снятия был виден в выводе Liquibase.
DO $$
BEGIN
    RAISE NOTICE 'candles retention policy removed: backtest/WFA history is no longer truncated';
END $$;
