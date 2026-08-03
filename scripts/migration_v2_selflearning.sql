-- ============================================================
-- Миграция v2: Self-Learning & Adaptive Risk Management
-- ============================================================
-- Применить: psql -U trader -d trading_bot -f migration_v2_selflearning.sql

-- Таблица корректировок стратегии (история изменений от Meta-Agent)
CREATE TABLE IF NOT EXISTS strategy_adjustments (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    adjustment_type VARCHAR(50) NOT NULL CHECK (adjustment_type IN ('CONFIDENCE', 'SL_PERCENT', 'TP_PERCENT', 'PAUSE', 'POSITION_SIZE')),
    old_value NUMERIC(19,6),
    new_value NUMERIC(19,6),
    triggered_by VARCHAR(50) NOT NULL CHECK (triggered_by IN ('META_AGENT', 'ADAPTIVE_RISK', 'MANUAL')),
    reason TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_adjustments_ticker ON strategy_adjustments(ticker, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_adjustments_type ON strategy_adjustments(adjustment_type, created_at DESC);

-- Таблица слепых зон (blind spots) — паттерны, приводящие к убыткам
CREATE TABLE IF NOT EXISTS blind_spots (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    condition_pattern TEXT NOT NULL,
    loss_rate DOUBLE PRECISION NOT NULL CHECK (loss_rate >= 0 AND loss_rate <= 1),
    occurrence_count INT NOT NULL DEFAULT 1,
    recommendation TEXT NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    detected_at TIMESTAMP DEFAULT NOW(),
    resolved_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_blind_spots_active ON blind_spots(ticker, is_active);
CREATE INDEX IF NOT EXISTS idx_blind_spots_detected ON blind_spots(detected_at DESC);

-- Оптимизация индексов для аналитики закрытых позиций
CREATE INDEX IF NOT EXISTS idx_positions_closed_at ON positions(closed_at DESC) WHERE status != 'OPEN';
CREATE INDEX IF NOT EXISTS idx_positions_ticker_closed ON positions(ticker, closed_at DESC) WHERE status != 'OPEN';

-- Комментарии
COMMENT ON TABLE strategy_adjustments IS 'История автоматических корректировок параметров стратегии';
COMMENT ON TABLE blind_spots IS 'Обнаруженные паттерны, приводящие к систематическим убыткам';
