--liquibase formatted sql
--changeset dmitry:024 splitStatements:false

-- Переименование сигнальной уверенности в силу сигнала (MR-009).
--
-- Цель: развести два разных понятия, которые оба назывались confidence:
--   * signalStrength — уверенность модели/стратега в направлении сделки
--     (сигнальная сторона, переименовывается здесь);
--   * adaptiveConfidence / confidenceAdjustment / confidenceSizing* —
--     риск-параметры (НЕ переименовываются).
--
-- Колонки:
--   strategies.confidence           NUMERIC(5,4) NOT NULL  -> signal_strength
--   agent_logs.confidence           NUMERIC(5,4)           -> signal_strength
--   experiment_decisions.confidence DOUBLE PRECISION       -> signal_strength
--
-- agent_logs партиционирована (PARTITION BY RANGE (created_at), миграция 019).
-- В PostgreSQL 12+ RENAME COLUMN на партиционированном родителе распространяется
-- на все партиции автоматически (metadata-only, без перезаписи данных), поэтому
-- явная обработка каждой партиции не требуется.

ALTER TABLE strategies RENAME COLUMN confidence TO signal_strength;
ALTER TABLE agent_logs RENAME COLUMN confidence TO signal_strength;
ALTER TABLE experiment_decisions RENAME COLUMN confidence TO signal_strength;
