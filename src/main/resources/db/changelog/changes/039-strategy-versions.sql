--liquibase formatted sql
--changeset dmitry:039

-- Monthly Tuning Engine - журнал версий стратегии.
--
-- Одна строка на период (YYYY-MM): сериализованные параметры стратегии, итог
-- monthly gate check и метрики месяца. Позволяет откатываться на предыдущую
-- версию и проверять, что деплой соответствует тому, что прошло gate.
--
-- gate_status: PASS / WARN / ALERT (см. PerformanceGateCheck.kt).
-- parameters: JSONB - сериализованные параметры стратегии (SL, TP, риск и т.д.).
-- metrics: JSONB - метрики месяца (PF, DD, WinRate, Sharpe).

CREATE TABLE IF NOT EXISTS strategy_versions (
    id          BIGSERIAL PRIMARY KEY,
    version     VARCHAR(20)  NOT NULL,        -- 'YYYY-MM' (например, '2026-10')
    parameters  JSONB        NOT NULL,        -- сериализованные параметры стратегии
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
    'Журнал версий стратегии по итогам Monthly Tuning Engine (docs/24, фаза 3.5).';
COMMENT ON COLUMN strategy_versions.version     IS 'Период деплоя: YYYY-MM';
COMMENT ON COLUMN strategy_versions.parameters  IS 'Сериализованные параметры стратегии';
COMMENT ON COLUMN strategy_versions.gate_status IS 'Итог monthly gate check: PASS/WARN/ALERT';
COMMENT ON COLUMN strategy_versions.metrics     IS 'Метрики месяца: PF, DD, WinRate, Sharpe';
COMMENT ON COLUMN strategy_versions.rolled_back IS 'true - версия откачена';