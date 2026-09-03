--liquibase formatted sql
--changeset dmitry:033

-- Per-ticker frozen strategy for LIVE execution (P1 audit).
-- Единый источник правды параметров одобренной стратегии (SL/TP/points/leverage/
-- risk/max contracts/confidence + strategy version + build identity): записывается
-- из DeploymentGate при LIVE_ALLOWED, читается live-execution-слоем и участвует в
-- fingerprint. Удаляется при revoke.
CREATE TABLE IF NOT EXISTS frozen_strategy (
    ticker VARCHAR(32) PRIMARY KEY,
    sl_percent DOUBLE PRECISION,
    tp_percent DOUBLE PRECISION,
    sl_points INTEGER,
    tp_points INTEGER,
    confidence_threshold DOUBLE PRECISION,
    leverage DOUBLE PRECISION NOT NULL,
    risk_per_trade_percent DOUBLE PRECISION,
    max_contracts_per_position INTEGER,
    strategy_version VARCHAR(64) NOT NULL,
    git_commit_sha VARCHAR(64),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
