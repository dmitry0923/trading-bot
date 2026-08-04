--liquibase formatted sql
--changeset dmitry:004

-- Фьючерсные поля позиций (Si: плечо, GO, маржа, ликвидация, вариационная маржа)
ALTER TABLE positions ADD COLUMN IF NOT EXISTS instrument_type VARCHAR(10) NOT NULL DEFAULT 'STOCK';
ALTER TABLE positions ADD COLUMN IF NOT EXISTS leverage NUMERIC(10,4);
ALTER TABLE positions ADD COLUMN IF NOT EXISTS go_per_contract NUMERIC(19,6);
ALTER TABLE positions ADD COLUMN IF NOT EXISTS margin_used NUMERIC(19,6);
ALTER TABLE positions ADD COLUMN IF NOT EXISTS liquidation_price NUMERIC(19,6);
ALTER TABLE positions ADD COLUMN IF NOT EXISTS variation_margin NUMERIC(19,6) NOT NULL DEFAULT 0;
ALTER TABLE positions ADD COLUMN IF NOT EXISTS stop_loss_points INT;

CREATE INDEX IF NOT EXISTS idx_positions_instrument_type ON positions(instrument_type);

-- Дневной риск-снапшот: восстановление daily P&L и статуса лимита после рестарта
CREATE TABLE IF NOT EXISTS daily_risk_snapshot (
    id BIGSERIAL PRIMARY KEY,
    trade_date DATE NOT NULL,
    daily_pnl NUMERIC(19,6) NOT NULL DEFAULT 0,
    limit_reached BOOLEAN NOT NULL DEFAULT FALSE,
    max_drawdown_today NUMERIC(19,6) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_daily_risk_snapshot_date UNIQUE (trade_date)
);
