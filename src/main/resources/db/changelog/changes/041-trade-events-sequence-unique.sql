--liquibase formatted sql
--changeset dmitry:041

-- Дефект: sequence_number вычислялся как MAX+1 без уникального индекса, поэтому
-- два конкурентных append одного агрегата получали один и тот же номер, и порядок
-- lineage (ORDER BY sequence_number) становился недетерминированным.
--
-- 1. Нормализуем уже накопленные дубликаты ТОЛЬКО у затронутых агрегатов: порядок
--    восстанавливается по (occurred_at, id) — тому порядку, в котором события были
--    бы записаны без гонки. Агрегаты без дубликатов не переписываются.
WITH duplicated AS (
    SELECT aggregate_id
    FROM trade_events
    GROUP BY aggregate_id
    HAVING COUNT(*) <> COUNT(DISTINCT sequence_number)
),
renumbered AS (
    SELECT
        t.id,
        ROW_NUMBER() OVER (PARTITION BY t.aggregate_id ORDER BY t.occurred_at, t.id) AS rn
    FROM trade_events t
    JOIN duplicated d ON d.aggregate_id = t.aggregate_id
)
UPDATE trade_events te
SET sequence_number = r.rn
FROM renumbered r
WHERE te.id = r.id
  AND te.sequence_number <> r.rn;

-- 2. Гарантия монотонности переводится в БД: конкурентный append теперь либо
--    получает свой номер, либо падает с unique violation (репозиторий повторяет).
DROP INDEX IF EXISTS idx_trade_events_aggregate;
CREATE UNIQUE INDEX IF NOT EXISTS idx_trade_events_aggregate_sequence
    ON trade_events(aggregate_id, sequence_number);
