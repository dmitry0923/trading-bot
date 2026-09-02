--liquibase formatted sql
--changeset dmitry:032

-- Per-ticker approval for LIVE trading (execution interlock, P1 audit).
-- Только DeploymentGate со статусом LIVE_ALLOWED может (пере)одобрить тикер.
-- Исполнительный слой блокирует реальные ордера для тикера, отсутствующего здесь
-- (fail-closed): никакой BUY/SELL не уходит на биржу без персистентного approval.
CREATE TABLE IF NOT EXISTS deployment_approval (
    ticker VARCHAR(32) PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    frozen_confidence_threshold DOUBLE PRECISION,
    params_hash VARCHAR(64),
    approved_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
