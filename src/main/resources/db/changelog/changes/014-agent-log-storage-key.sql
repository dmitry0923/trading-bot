--liquibase formatted sql
--changeset dmitry:014

-- Хранилище LLM-трейсов (S3/MinIO): agent_logs хранит только ссылку storage_key
-- на полный JSON-трейс (промпты + ответ) в объектном хранилище.
-- raw_output при этом остаётся как краткий сниппет (сырой ответ агента).
ALTER TABLE agent_logs ADD COLUMN IF NOT EXISTS storage_key VARCHAR(255);
