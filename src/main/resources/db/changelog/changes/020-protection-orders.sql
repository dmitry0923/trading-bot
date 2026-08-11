--liquibase formatted sql
--changeset dmitry:020

-- Биржевые защитные заявки (SL/TP) на бирже Alor (roadmap v2.2 «Точный контроль
-- SL/TP в лимитных заявках»).
--
-- - sl_order_id / tp_order_id — id стоп/тейк-заявок на бирже Alor (заявки
--   выставляются при открытии позиции);
-- - sl_order_price / tp_order_price — уровень, на который выставлена заявка: по
--   нему детектируется необходимость перевыставления при сдвиге trailing-стопа
--   или обновлении SL/TP стратегией;
-- - sl_pending_replace / tp_pending_replace — перевыставление «в полёте»: старая
--   заявка ещё не снята — новую НЕ выставляем (защита от двойного стопа/тейка).
ALTER TABLE positions
    ADD COLUMN IF NOT EXISTS sl_order_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS tp_order_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS sl_order_price NUMERIC(19,6),
    ADD COLUMN IF NOT EXISTS tp_order_price NUMERIC(19,6),
    ADD COLUMN IF NOT EXISTS sl_pending_replace BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS tp_pending_replace BOOLEAN NOT NULL DEFAULT FALSE;
