# 6. База данных

PostgreSQL 15, доступ через `spring-boot-starter-data-r2dbc` (reactive `DatabaseClient`). Все 9 репозиториев — suspend-функции (не блокируют потоки), кроме `DailyRiskSnapshotRepository` (намеренно sync: вызывается из синхронного риск-движка, внутри `Mono.block()`). JDBC (`spring-boot-starter-jdbc` + `spring.datasource.*`) остаётся **только** для Liquibase-миграций схемы. JPA/Hibernate нет.

## 6.1. ER-диаграмма

```mermaid
erDiagram
    positions ||--o{ agent_logs : "cycle"
    strategies ||--o{ agent_logs : "cycle"
    positions ||--o{ order_outbox : "alor_order_id"
    positions ||--o{ blind_spots : "ticker"
    positions ||--o{ strategy_adjustments : "ticker"
    strategies ||--o{ candles : "ticker"

    positions {
        bigserial id PK
        varchar ticker
        varchar direction "LONG|SHORT"
        int quantity
        numeric entry_price
        numeric current_price
        numeric close_price
        numeric stop_loss
        numeric take_profit
        numeric trailing_stop_price
        numeric pnl
        varchar status "OPEN|CLOSED|TAKE_PROFIT"
        varchar alor_order_id
        varchar close_reason
        timestamp opened_at
        timestamp closed_at
    }
    strategies {
        bigserial id PK
        varchar ticker
        varchar action "BUY|SELL|HOLD|CLOSE"
        numeric target_price
        int quantity
        numeric stop_loss
        numeric take_profit
        boolean trailing_stop
        numeric confidence
        text reasoning
        text raw_json
        varchar cycle_id
        timestamp valid_until
        timestamp created_at
    }
    candles {
        varchar ticker
        varchar timeframe
        numeric open_price
        numeric high_price
        numeric low_price
        numeric close_price
        bigint volume
        timestamp time "partition key (TimescaleDB)"
    }
    agent_logs {
        bigserial id PK
        varchar cycle_id
        varchar agent_name
        varchar ticker
        varchar action
        numeric confidence
        text reasoning
        text raw_output
        bigint latency_ms
        int tokens_used
        boolean is_cached
        varchar override_reason
        timestamp created_at
    }
    blind_spots {
        bigserial id PK
        varchar ticker
        varchar condition_pattern
        numeric loss_rate
        int occurrence_count
        varchar recommendation
        boolean is_active
        timestamp detected_at
        timestamp resolved_at
    }
    strategy_adjustments {
        bigserial id PK
        varchar ticker
        varchar adjustment_type
        numeric old_value
        numeric new_value
        varchar triggered_by
        varchar reason
        timestamp created_at
    }
    order_outbox {
        uuid id PK
        jsonb payload
        varchar status "PENDING|SENT|FAILED"
        varchar alor_order_id
        timestamp created_at
        timestamp processed_at
        text error_message
    }
```

## 6.2. Таблицы

### positions

| Колонка | Тип | NOT NULL | Описание |
|---|---|---|---|
| id | BIGSERIAL | PK | автоинкремент |
| ticker | VARCHAR(20) | ✓ | тикер MOEX |
| direction | VARCHAR(10) | ✓ | LONG / SHORT |
| quantity | INT | ✓ | количество лотов |
| entry_price | NUMERIC(19,6) | ✓ | цена входа (avgPrice из verifyOrder) |
| current_price | NUMERIC(19,6) | | последняя цена из монитора |
| close_price | NUMERIC(19,6) | | цена закрытия (WS fill или market) |
| stop_loss | NUMERIC(19,6) | | стоп-лосс |
| take_profit | NUMERIC(19,6) | | тейк-профит |
| trailing_stop_price | NUMERIC(19,6) | | текущий трейлинг-стоп |
| pnl | NUMERIC(19,6) | | итоговый P&L |
| status | VARCHAR(20) | ✓ default 'OPEN' | OPEN / CLOSED / TAKE_PROFIT |
| alor_order_id | VARCHAR(100) | | номер ордера Alor (связь с outbox и WS) |
| close_reason | VARCHAR(50) | | STOP_LOSS / TAKE_PROFIT / TRAILING_STOP / STRATEGY_CLOSE / EXECUTION_FILL |
| opened_at | TIMESTAMP | ✓ | время открытия |
| closed_at | TIMESTAMP | | время закрытия |

Индексы: `idx_positions_ticker(ticker)`, `idx_positions_status(status)`, `idx_positions_closed_at(closed_at)`.

### strategies

| Колонка | Тип | NOT NULL | Описание |
|---|---|---|---|
| id | BIGSERIAL | PK | |
| ticker | VARCHAR(20) | ✓ | |
| action | VARCHAR(20) | ✓ | BUY/SELL/HOLD/CLOSE |
| target_price | NUMERIC(19,6) | ✓ | целевая цена |
| quantity | INT | ✓ | лоты |
| stop_loss / take_profit | NUMERIC(19,6) | | из финального решения |
| trailing_stop | BOOLEAN | ✓ default false | |
| confidence | NUMERIC(5,4) | ✓ | 0..1 |
| reasoning | TEXT | ✓ | обоснование (с меткой Meta) |
| raw_json | TEXT | | полный JSON финального решения арбитра |
| cycle_id | VARCHAR(50) | ✓ | идемпотентность / трассировка |
| valid_until | TIMESTAMP | ✓ | срок годности сигнала |
| created_at | TIMESTAMP | ✓ | |

Индексы: `idx_strategies_ticker(ticker)`, `idx_strategies_created_at(created_at)`.

### candles

TimescaleDB-гипертаблица (см. миграцию `012-timescale-candles.sql`): `create_hypertable('candles', 'time')`, чанки по 1 неделе. На обычном PostgreSQL (без расширения timescaledb) конвертация безопасно пропускается — таблица остаётся обычной (миграция атомарна и не ломает старт приложения).

| Колонка | Тип | NOT NULL | Описание |
|---|---|---|---|
| ticker | VARCHAR(20) | ✓ | |
| timeframe | VARCHAR(20) | ✓ | MINUTE_10 |
| open_price / high_price / low_price / close_price | NUMERIC(19,6) | ✓ | OHLC |
| volume | BIGINT | ✓ | |
| time | TIMESTAMP | ✓ | начало свечи (partition key) |

UNIQUE `(ticker, timeframe, time)` — защита от дублей (partition key `time` входит в уникальность, поэтому индекс допустим для гипертаблицы). Индекс `idx_candles_ticker_time(ticker, timeframe, time DESC)`.

> **Реализовано**: партиционирование по `time` через TimescaleDB-чанки + автоматическое удаление чанков старше 90 дней (`add_retention_policy`) — размер таблицы ограничен, VACUUM-нагрузка на историю отсутствует (раздел 6.4).
>
> **Примечание**: compression TimescaleDB не используется — он несовместим с UNIQUE-индексами, а UNIQUE нужен для идемпотентной записи `ON CONFLICT DO NOTHING`. При текущем объёме (~50K строк/мес) компрессия не требуется.

### agent_logs

| Колонка | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| cycle_id | VARCHAR(50) | correlation id цикла (для агентов 1–5) или "META" |
| agent_name | VARCHAR(100) | Agent-1-Technical ... Agent-6-Performance, TradingBot |
| ticker | VARCHAR(20) | |
| action | VARCHAR(50) | conclusion/action/CHALLENGE:LEVEL |
| confidence | NUMERIC(5,4) | |
| reasoning | TEXT | |
| raw_output | TEXT | сырой ответ LLM |
| latency_ms | BIGINT | |
| tokens_used | INT | добавлено миграцией 002 |
| is_cached | BOOLEAN | добавлено миграцией 002 |
| override_reason | VARCHAR(200) | добавлено миграцией 002 |
| created_at | TIMESTAMP | |

Индексы: `idx_agent_logs_cycle(cycle_id)`, `idx_agent_logs_created_at(created_at)`.

### order_outbox

см. раздел 4.1 (полная спецификация). Индекс `idx_outbox_status_created(status, created_at)`.

### blind_spots

| Колонка | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| ticker | VARCHAR(20) | |
| condition_pattern | VARCHAR(4000) | например "Stop-Loss hit rate > 60% for SBER" |
| loss_rate | NUMERIC(5,4) | доля убыточных сделок под паттерном |
| occurrence_count | INT | число вхождений (инкремент при повторе) |
| recommendation | VARCHAR(4000) | рекомендация |
| is_active | BOOLEAN | TRUE пока не resolved |
| detected_at / resolved_at | TIMESTAMP | |

Индекс: `idx_blind_spots_ticker_active(ticker, is_active)`.

### strategy_adjustments

| Колонка | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| ticker | VARCHAR(20) | |
| adjustment_type | VARCHAR(50) | CONFIDENCE / SL_PERCENT / TP_PERCENT |
| old_value / new_value | NUMERIC(19,6) | |
| triggered_by | VARCHAR(50) | META_AGENT |
| reason | VARCHAR(4000) | |
| created_at | TIMESTAMP | |

Индекс: `idx_adjustments_ticker(ticker)`.

## 6.3. Liquibase Changelogs

**Master changelog** `db/changelog/db.changelog-master.yaml`:

> Важно: Spring Boot 3.2 отключает `DataSourceAutoConfiguration`, когда в контексте есть R2DBC `ConnectionFactory` (условие `@ConditionalOnMissingBean(type = "io.r2dbc.spi.ConnectionFactory")`), а `LiquibaseAutoConfiguration` требует бин `DataSource`. Поэтому бин `liquibaseDataSource` создаётся явно в `com.trading.bot.config.DatabaseConfig` из свойств `spring.datasource.*` (`DataSourceProperties.initializeDataSourceBuilder()`). Без этого бина Liquibase не выполняет миграции (тесты падали с `relation does not exist`).

```yaml
databaseChangeLog:
  - include:
      file: db/changelog/changes/001-create-tables.sql
  - include:
      file: db/changelog/changes/002-add-agent-log-columns.sql
  - include:
      file: db/changelog/changes/003-order-outbox.sql
```

**Пример changeset** (001):

```text
--liquibase formatted sql
--changeset dmitry:001

CREATE TABLE IF NOT EXISTS positions (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    ...
);
CREATE INDEX IF NOT EXISTS idx_positions_ticker ON positions(ticker);
```

**Пример changeset с TimescaleDB-конвертацией candles** (`012-timescale-candles.sql`): миграция атомарна — при недоступности расширения (обычный PG / Managed PG без включённого timescaledb) блокируются только DDL в DO-блоке с предупреждением, приложение стартует без изменений схемы.

## 6.4. Оптимизация запросов

**Частые выборки и покрытие**:

| Запрос | Где | Индекс |
|---|---|---|
| `positions WHERE status='OPEN'` | TradingBotService | `idx_positions_status` |
| `positions WHERE alor_order_id=?` | applyExecutionReport | `alor_order_id` (рекомендуется добавить индекс) |
| `positions WHERE status != 'OPEN' AND closed_at >= ?` | TradeAnalysisService | `idx_positions_closed_at` |
| `positions ... AND ticker=? AND closed_at >= ?` | timePatternAnalysis | составной `(ticker, closed_at)` — рекомендация |
| `candles WHERE ticker=? AND timeframe=? AND time BETWEEN ?` | loadCandles | `idx_candles_ticker_time` (гипертаблица, pruned by chunk) |
| `agent_logs WHERE cycle_id=?` | трассировка | `idx_agent_logs_cycle` |
| `order_outbox WHERE status='PENDING' AND created_at < ?` | outbox worker | `idx_outbox_status_created` |

**Партиционирование `candles` (TimescaleDB)**: таблица растёт быстрее всех (10 тикеров × 144 свечи/день = ~1 400 строк/день, ~42 000/мес). Гипертаблица решает проблему роста и разрастания B-Tree-индексов:

- чанки по `time` (1 неделя) — запись идёт только в горячий чанк, выборки по периоду pruning по чанкам;
- `add_retention_policy('candles', INTERVAL '90 days')` автоматически удаляет чанки старше 90 дней — размер таблицы ограничен, VACUUM-нагрузка на историю отсутствует;
- свечи перезагружаемы с MOEX ISS, поэтому retention безопасен.

**Включение на Yandex Managed PostgreSQL**: расширение управляется НЕ через SQL, а через консоль/CLI/Terraform (`--extensions timescaledb` + shared_preload_libraries `timescaledb`, перезапуск мастера). До включения миграция `012` пропускается с предупреждением, `candles` остаётся обычной таблицей (батч-запись через `saveAll` всё равно убирает деградацию). После включения нужно перезапустить приложение (или выполнить конвертацию вручную).

**Архивация старых данных**: для `agent_logs` — `raw_output` старых циклов можно удалять (аггрегаты остаются в `positions`). Запланировано как @Scheduled job (раздел 13).

**Массовая запись свечей**: репозиторий пишет свечи батчами (`CandleRepository.saveAll`, multi-row `INSERT ... ON CONFLICT DO NOTHING`, батчи по 500 строк) вместо паттерна `exists`+`save` на каждую строку — это убирает деградацию записи независимо от СУБД.

**Прочие рекомендации**:
- добавить индекс на `positions.alor_order_id` (есть частый lookup по WS-fill);
- покрывающий индекс для `TradeAnalysisService`: `(ticker, status, closed_at)` include (pnl, close_reason, opened_at).

## 6.5. Использование БД в бэктесте

`BacktestEngine` (раздел 11) читает историю исключительно из таблицы `candles`:

```kotlin
candleRepo.findByTickerAndTimeframeAndTimeBetween(
    ticker, "MINUTE_10", now.minusDays(days), now
)
```

Требования к данным для бэктеста:

| Аспект | Требование | Реализация |
|---|---|---|
| Глубина истории | ≥ 32 свечей для `minBarsForSignal + 2` | иначе `emptyResult()` |
| Полнота OHLC | open/high/low/close заполнены | наполнение MOEX ISS |
| Непрерывность | пробелы допустимы (обработка по порядку времени) | `sortedBy { it.time }` |
| Лотность | `quantity` совместим с позициями | `lotRounded` вниз |

> **Проект**: таблица `backtest_results` для сохранения результатов и сравнения итераций (раздел 11.8).

## 6.6. Дневной P&L в БД

Дневной лимит убытка (`risk.max-daily-loss-rub`) опирается на дневной P&L, который **персистится в БД** — состояние переживает рестарт пода в течение торгового дня (раздел 5.6).

| Аспект | Реализация |
|---|---|
| Хранение | таблица `daily_risk_snapshot` — одна строка на торговую дату (`UNIQUE (trade_date)`) |
| Обновление | upsert из `DrawdownProtectionService` (на закрытие позиции и по циклу риск-движка) |
| Сброс | по календарной дате 00:00 МСК (новый день → новый снапшот) |
| Восстановление | загрузка сегодняшнего снапшота при старте (`ApplicationReadyEvent`) и при первом касании дня |
| Прозрачность | `GET /api/v1/risk/daily-pnl` (из БД) |
| History | `GET /api/v1/risk/daily-pnl-history?days=30` — график дневных результатов, статистика лимитов |

DDL (миграция `004-futures-risk.sql`, changeset `dmitry:004`):

```text
--liquibase formatted sql
--changeset dmitry:004

CREATE TABLE IF NOT EXISTS daily_risk_snapshot (
    id BIGSERIAL PRIMARY KEY,
    trade_date DATE NOT NULL,
    daily_pnl NUMERIC(19,6) NOT NULL DEFAULT 0,
    limit_reached BOOLEAN NOT NULL DEFAULT FALSE,
    max_drawdown_today NUMERIC(19,6) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_daily_risk_snapshot_date UNIQUE (trade_date)
);
```

Обновление: `INSERT ... ON CONFLICT (trade_date) DO UPDATE SET daily_pnl = EXCLUDED.daily_pnl, ...`.

## 6.7. Транзакционность и консистентность

| Операция | Транзакция | Комментарий |
|---|---|---|
| Outbox INSERT | `@Transactional` (placeOrder) | строка ордера и его payload — атомарны |
| Открытие позиции | INSERT positions | идемпотентность по сигналу (одна позиция на тикер) |
| Применение fill | UPDATE по `alor_order_id` | повторный fill не создаёт дубль |
| Закрытие | UPDATE status/close_price | `closed_at`, `close_reason`, `pnl` в одном UPDATE |
| Партиционирование candles | TimescaleDB hypertable | миграция `012`, атомарный DO-блок; retention 90 дней |

Важное следствие: **PostgreSQL — единый источник правды**. Redis хранит только кэшируемые артефакты (стратегии с TTL, semantic cache, feedback), и потеря Redis не разрушает бизнес-данные. `candles` — гипертаблица TimescaleDB, но доступ к ней через те же R2DBC/Liquibase, что и к обычным таблицам.

## 6.8. Резервное копирование

| Уровень | Рекомендация |
|---|---|
| pg_dump | ежедневно, офсайт |
| PITR | включить WAL-архив (managed PG) |
| Проверка restore | еженедельный тест восстановления на staging |
| Свечи | перезагружаемы с MOEX ISS (не критичны) |
| positions/strategies | критичны — восстановление из бэкапа |

## 6.9. Мониторинг БД

| Метрика | Источник | Предел |
|---|---|---|
| Размер таблиц | `pg_total_relation_size` | candles — следить за ростом (retention 90 дней ограничивает) |
| Число чанков candles | `chunks_detailed_size('candles')` / `timescaledb_information.chunks` | рост числа чанков ≈ 52/год, проверять равномерность |
| Сработавший retention | журнал TimescaleDB background jobs (`timescaledb_information.jobs`) | удаление чанков должно идти регулярно |
| Медленные запросы | pg_stat_statements | > 100 мс |
| Заблокированные транзакции | pg_stat_activity | > 30 c |
| Bloat | pgstat | регулярная autovacuum |

Включение pg_stat_statements:

```text
-- в postgresql.conf
shared_preload_libraries = 'pg_stat_statements';
```
