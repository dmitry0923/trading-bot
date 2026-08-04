--liquibase formatted sql
--changeset dmitry:002

ALTER TABLE agent_logs ADD COLUMN IF NOT EXISTS tokens_used INT;
ALTER TABLE agent_logs ADD COLUMN IF NOT EXISTS is_cached BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_logs ADD COLUMN IF NOT EXISTS override_reason VARCHAR(200);
