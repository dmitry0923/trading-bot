--liquibase formatted sql
--changeset dmitry:044 splitStatements:false

-- `positions.status` был VARCHAR(20), а статус рассинхрона `RECONCILIATION_REQUIRED`
-- занимает 23 символа: запись падала с `[22001] value too long for type character
-- varying(20)` (скан-10 P2).
--
-- Практическое следствие: ветка ресинхронизации НЕ МОГЛА сохраниться. Любой
-- рассинхрон (direction/quantity mismatch, фантомная позиция) выбрасывал исключение
-- при UPDATE, обработчик логировал ошибку — и halt по неизвестной биржевой позиции
-- не публиковался. Позиция оставалась OPEN с локальными данными, расходящимися с
-- биржей, и риск-контуры продолжали считать её «нормальной».
--
-- VARCHAR(32) — с запасом под будущие статусы (сейчас максимум 23 символа).
ALTER TABLE positions ALTER COLUMN status TYPE VARCHAR(32);

DO $$
BEGIN
    RAISE NOTICE 'positions.status widened to varchar(32): RECONCILIATION_REQUIRED is now persistable';
END $$;
