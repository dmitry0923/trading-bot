-- V038: Monthly Tuning Engine — таблица версий стратегий.
--
-- Хранит параметры стратегии, статус gate check и метрики за каждый
-- месяц тюнинга. Поддерживает rollback к предыдущей рабочей версии.
--
-- gate_status: PASS / WARN / ALERT (см. PerformanceGateCheck.kt).
-- parameters: JSONB с параметрами стратегии (SL, TP, пороги и т.д.).
-- metrics: JSONB с метриками прошедшего месяца (PF, DD, WinRate, Sharpe).

CREATE TABLE IF NOT EXISTS strategy_versions (
    id          BIGSERIAL PRIMARY KEY,
    version     VARCHAR(20)  NOT NULL,        -- 'YYYY-MM' (напр. '2026-10')
    parameters  JSONB        NOT NULL,        -- параметры стратегии
    gate_status VARCHAR(10)  NOT NULL         -- PASS / WARN / ALERT
        CHECK (gate_status IN ('PASS', 'WARN', 'ALERT')),
    metrics     JSONB        NOT NULL,        -- PF, DD, WinRate, Sharpe, Trades
    deployed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    rolled_back BOOLEAN      NOT NULL DEFAULT false,
    notes       TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS uidx_strategy_versions_version
    ON strategy_versions (version)
    WHERE NOT rolled_back;

COMMENT ON TABLE strategy_versions IS
    'Версии стратегии по результатам Monthly Tuning Engine (docs/24, Фаза 3.5).';
COMMENT ON COLUMN strategy_versions.version     IS 'Период тюнинга: YYYY-MM';
COMMENT ON COLUMN strategy_versions.parameters  IS 'JSON-параметры стратегии';
COMMENT ON COLUMN strategy_versions.gate_status IS 'Статус gate check: PASS/WARN/ALERT';
COMMENT ON COLUMN strategy_versions.metrics     IS 'Метрики прошедшего месяца';
COMMENT ON COLUMN strategy_versions.rolled_back IS 'true — версия откатана';
