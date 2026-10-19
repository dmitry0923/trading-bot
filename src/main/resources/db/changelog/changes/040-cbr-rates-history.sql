--liquibase formatted sql
--changeset dmitry:040 splitStatements:false

-- Ряд ставок Банка России (P2 c1 плана 18_10_2026, research-слой).
--
-- Закрывает дыру «нет ряда ставок»: ежедневная ключевая ставка ЦБ и RUONIA —
-- новый макро-класс данных, ортогональный OHLCV и funding. Источник —
-- официальный SOAP-сервис ЦБ (DailyInfoWebServ, методы KeyRate/Ruonia);
-- донакачка — CbrRatesHistoryLoader, чтение — CbrRatesHistoryRepository,
-- research-доступ — MacroRatesController (/api/v1/macro/rates-history*).
--
-- key_rate/ruonia nullable: RUONIA за дату D публикуется на D+1, поэтому крайняя
-- дата может быть заполнена лишь частично; повторная донакачка дозаполняет поля
-- через ON CONFLICT (rate_date) DO UPDATE с COALESCE.
--
-- В торговом контуре не участвует: macro.live-rate-enabled=false по умолчанию
-- (MacroContextService остаётся на статичной macro.cbr-rate).

CREATE TABLE IF NOT EXISTS cbr_rates_history (
    rate_date DATE         PRIMARY KEY,
    key_rate  NUMERIC(8,4),
    ruonia    NUMERIC(8,4),
    source    VARCHAR(16)  NOT NULL DEFAULT 'CBR',
    loaded_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE cbr_rates_history IS
    'Дневной ряд ставок ЦБ РФ (ключевая ставка, RUONIA) - research P2 c1.';
COMMENT ON COLUMN cbr_rates_history.key_rate IS 'Ключевая ставка ЦБ РФ, % годовых';
COMMENT ON COLUMN cbr_rates_history.ruonia   IS 'Ставка RUONIA за день, % годовых';
