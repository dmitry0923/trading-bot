--liquibase formatted sql
--changeset dmitry:034 splitStatements:false

-- P0/P1-аудит funding (per-clearing): маркер «P&L посчитан без авторитетного
-- funding» (FUNDING_UNKNOWN) — LIVE: MOEX недоступен минимум на один пережитый
-- позицией клиринг. Не подменяется CONFIG-значением (никакого «тихого 0.5»),
-- делается пометка на позиции, чтобы P&L такой сделки не трактовался как точный.
--
-- ALTER на партиционированной таблице каскадится на партиции (PostgreSQL).

ALTER TABLE positions ADD COLUMN IF NOT EXISTS funding_unknown BOOLEAN NOT NULL DEFAULT FALSE;