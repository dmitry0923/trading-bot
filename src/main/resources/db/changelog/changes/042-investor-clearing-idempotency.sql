--liquibase formatted sql
--changeset dmitry:042

-- Идемпотентность клиринга на уровне БД. Раньше защита от повторного
-- `POST /clearing/settle` была read-then-write по метке даты в description:
-- два параллельных вызова оба её проходили и списывали средства дважды,
-- а дата бралась в JVM-зоне (UTC), тогда как деловой день — Московский.
-- Бизнес-дата заполняется только для CLEARING: ввод/вывод может повторяться
-- в один день и не должен блокироваться.
ALTER TABLE investor_transactions ADD COLUMN IF NOT EXISTS business_date DATE;

-- Бэкфилл исторических CLEARING-строк (дата создания как приближение бизнес-дня).
UPDATE investor_transactions
SET business_date = created_at::date
WHERE type = 'CLEARING' AND business_date IS NULL;

-- Дедупликация легаси-дублей (оставляем самое раннее списание за день),
-- иначе уникальный индекс не создастся на уже испорченных данных.
DELETE FROM investor_transactions t
USING investor_transactions older
WHERE t.type = 'CLEARING'
  AND older.type = 'CLEARING'
  AND t.investor_id = older.investor_id
  AND t.business_date = older.business_date
  AND (older.created_at < t.created_at OR (older.created_at = t.created_at AND older.id < t.id));

CREATE UNIQUE INDEX IF NOT EXISTS uq_investor_transactions_clearing_day
    ON investor_transactions(investor_id, type, business_date)
    WHERE type = 'CLEARING';
