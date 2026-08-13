# 13. Roadmap и планы развития

## 13.1. Текущее состояние (v2.1)

**Реализовано в текущей ветке:**

- 6 LLM-агентов (тех. анализ, фундаментал, стратегия, контрар, арбитр, feedback) + Guardrails + semantic cache + resilience (CB/RateLimiter/Retry).
- Alor-интеграция: REST + WebSocket, idempotency, outbox, контроль спреда.
- MOEX ISS данные, индикаторы (MA, RSI, ATR, MACD, Bollinger, EMA), Kelly criterion, адаптивные SL/TP.
- Стратегии `conservative`/`default`/`aggressive`, self-learning (PerformanceFeedbackAgent).
- 7 таблиц, Liquibase, REST API, Prometheus-метрики, alertmanager-конфиг.
- Docker compose (TimescaleDB/PostgreSQL 15 + Redis 7).
- **Event-driven слой** (раздел 2.3): `PriceChangedEvent`, `StrategyGeneratedEvent`, `EntrySignalEvent`, `ExecutionReportEvent` + `TradingEventPublisher` + `@EventListener`.
- **Sector concentration** (раздел 5.3): `risk.max-sector-exposure`, справочник `risk.sectors`.
- **Volatility guard** (раздел 5.4): ATR% > `risk.max-volatility-percent` → HOLD.
- **Backtest framework** (раздел 11): `BacktestEngine`, `SimulatedExecution`, `BacktestMetrics`, endpoint `GET /api/v1/backtest/{ticker}`, 6 unit-тестов.
- **TimescaleDB для candles** (раздел 6.4): гипертаблица (чанки по time, retention 90 дней) + батч-запись свечей `CandleRepository.saveAll` вместо построчного `exists`+`save`.
- **Наблюдаемость LLM-агента** (раздел 13.18): JSON-логирование + trace_id, трейс-хранилище S3/MinIO, Shadow Mode / decision-level A/B + Grafana, **RAG-анализ ошибок** по трейсам (`/api/v1/rag/*`).

## 13.2. Дорожная карта по версиям

```mermaid
gantt
    title MMVB Trading Bot roadmap
    dateFormat  YYYY-MM-DD
    section v2.0 (реализовано)
    Мультиагентный конвейер          :done, a0, 2025-01-01, 2025-03-01
    section v2.1 (реализовано)
    Event-driven слой                :done, a1, 2025-03-01, 2025-05-01
    Risk guard (sector/volatility)   :done, a2, after a1, 2025-06-01
    Backtest framework               :done, a3, after a2, 2025-07-01
    section v2.2 (краткосрочная)
    Emergency stop endpoint          :v21, 2025-07-01, 2025-09-01
    Persist daily PnL                :done, v21b, after v21, 2025-10-01
    section v2.3 (среднесрочная)
    LLM в бэктесте, WebSocket-only   :v22, 2025-10-01, 2026-01-01
    section v2.4 (долгосрочная)
    ML-агенты (CatBoost/LightGBM)    :v23, 2026-01-01, 2026-04-01
    section v2.5
    Cross-exchange, multi-timeframe  :v24, 2026-04-01, 2026-07-01
```

### v2.2 — Краткосрочные улучшения

| Фича | Описание | Статус |
|---|---|---|
| Emergency stop endpoint | `POST /api/v1/bot/emergency-stop` — закрывает все позиции + запрещает открытие | ✅ |
| Persist daily PnL | перенос `dailyPnl` из памяти в БД (`daily_risk_snapshot`) + `GET /api/v1/risk/daily-pnl-history` (раздел 6.6) | ✅ |
| Партиционирование `candles` | TimescaleDB hypertable: чанки по time (1 неделя) + retention 90 дней (раздел 6.4) | ✅ v2.2 |
| Партиционирование `positions`/`agent_logs` | PostgreSQL native partitioning (раздел 6.4) | ✅ v2.2 |
| Точный контроль SL/TP в лимитных заявках | биржевые stop/take-profit-заявки при открытии позиции (раздел 13.7.4) | ✅ v2.2 |
| Distributed lock | возможность запуска нескольких инстансов без гонок (раздел 2.6) | ✅ v2.2 |
| Multi-account | поддержка нескольких Alor-портфелей с общим конвейером и персональными лимитами | ✅ |
| Backtest: сохранение результатов | таблица `backtest_results` + сравнение итераций | ✅ |
| Backtest: out-of-sample | walk-forward (train → tune SL/TP → OOS), защита от переобучения | ✅ |

### v2.3 — Среднесрочные

| Фича | Описание | Статус |
|---|---|---|
| **LLM-агенты в бэктесте** | заменить детерминированный `signalAt()` на конвейер tech→fund→strategy→contrarian→arbitrator (раздел 11.1) | ✅ (раздел 13.8.1) |
| Backtest: панельный прогон | несколько тикеров за один вызов, распределение результатов | ✅ (POST `/api/v1/backtest/panel`, раздел 11.6.1) |
| Backtest: конфиг `bt.*` | вынос констант 20%/2%/4% и `initialCapital` из кода | ✅ (раздел 11.8.1) |
| WebSocket-only исполнение | полный переход на WS для market-data и ордеров, REST — только fallback | ✅ (раздел 13.8.2) |
| Уменьшение LLM-латентности | параллельные вызовы агентов, дельта-промпты | ✅ (раздел 13.8.5) |
| Очередь (RabbitMQ) для outbox | RabbitMQ — дополнительный канал доставки outbox-строк (publisher → очередь → консьюмер через `redispatchById`), DB-worker остаётся фолбэком | ✅ (раздел 13.8.4) |

### v2.4 — ML-агенты

- Замена/дополнение части LLM-инференса ML-моделями (CatBoost/LightGBM) для задач, где нужна скорость и стабильность: скрининг кандидатов, оценка вероятности удержания тренда.
- Retraining pipeline: собранные через `agent_logs` и сделки данные → features → обучение на CI.
- ✅ **Шаг 1 (датасет-экспорт)**: `GET /api/v1/ml/dataset` (CSV) + `/dataset/stats`, признаки на входе из candles + макро + `agent_logs` + слепые зоны, флаг `ml.enabled` (раздел 13.11).
- ✅ **Шаг 1.5 (исторические макро-снапшоты)**: `macro_snapshots` + `MacroSnapshotCollector`, макро в датасете берутся на момент входа (`macro_source=SNAPSHOT`), lookahead-утечка устранена (раздел 13.11.2).
- ✅ **Шаг 2 (обучение на CI)**: `ml/` пайплайн (CatBoost/LightGBM, temporal OOS 20%, метрика M3 — profit factor на OOS vs LLM-baseline), GitHub Actions `ml-train.yml` + pytest-тесты (раздел 13.11.3).
- ✅ **Шаг 3 (инференс и скрининг)**: загрузка обученной CatBoost-модели (`.cbm`) в бэкенд + `GET /api/v1/ml/screen` — ранжирование тикеров по вероятности выигрышного исхода (раздел 13.11.4).
- ✅ **ML-фильтр входа** (доп. шаг): прогноз модели как гейт входа в торговый цикл (`DecisionEngine`), `ml.filter.enabled`/`ml.filter.threshold`, fail-closed при недоступной модели (раздел 13.11.5).
- ✅ **ML-фильтр в бэктесте** (доп. шаг): `bt.ml-filter-enabled` — модель гейтит входы бэктеста на момент бара (confidence=null), live-гейт не затрагивается (раздел 13.11.6).
- ✅ **ML-оценка удержания тренда** (доп. шаг): `GET /api/v1/ml/trend` — ранжирование по trendScore (модель + индикаторная сила тренда), опциональный тренд-гейт входа `ml.filter.trend-gate-enabled` (раздел 13.11.7).
- ✅ **Онлайн-калибровка порога уверенности** (доп. шаг): `ConfidenceCalibrator` — порог входа тикера подбирается по фактическим исходам сделок (уверенность стратега на входе × win/pnl), fallback — правила по win rate (раздел 13.11.8).
- ✅ **Confidence-aware сайзинг** (доп. шаг): размер позиции масштабируется по уверенности сигнала относительно адаптивного порога — маржинальный сигнал режет размер, высокая уверенность даёт полный размер (раздел 13.11.9).

### v2.5 — Расширение горизонтов

- Multi-timeframe: вход по 10-минутному, фильтр по часовому/дневному.
- Cross-exchange: мониторинг котировок на нескольких площадках, арбитраж между MOEX и международными рынками (по мере регуляторной готовности).

## 13.3. План стабилизации (непрерывно)

1. **Набор regression-тестов** по каждому модулю (Guardrails, SemanticCache, Agent parsers, outbox, BacktestEngine).
2. **Backtest всех тикеров** по критериям раздела 11.5 перед каждой новой стратегией.
3. **Chaos testing**: отключение Redis/Postgres/Kimi/сети — проверка graceful degradation.
4. **Нагрузочное тестирование**: до 100 тикеров × 6 агентов × 2 LLM-вызова — бюджет латентности и стоимости.
5. **Мониторинг вырожденных случаев**: SPREAD > 1%, депозитарные паузы, гэпы.

## 13.4. Контрольные точки (milestones)

| Миля | Критерий готовности |
|---|---|
| M1 (v2.2) | Emergency stop ✅ + persist daily PnL ✅, тесты зелёные, документация обновлена |
| M2 (v2.3) | LLM-бэктест проходит критерии по ≥ 5 тикерам; WebSocket-only стабилен 1 неделю SIMULATION |
| M3 (v2.4) | ML-модель выигрывает у базовой LLM-версии на out-of-sample выборке |
| M4 (v2.5) | Cross-exchange сигналы без ложных арбитражных входов |

## 13.5. Что уже снято с планов (сделано)

Эти пункты были в планах, но реализованы в v2.1:

| Пункт | Где реализовано |
|---|---|
| Event-driven очередь (вместо @Scheduled) | раздел 2.3 — события + `@EventListener`, остаточные `@Scheduled` для poll/outbox |
| Sector Concentration | раздел 5.3 — `exceedsSectorExposure`, `risk.max-sector-exposure` |
| Volatility Check (ATR% > 5% → запрет) | раздел 5.4 — `isVolatilityTooHigh`, `risk.max-volatility-percent` |
| Backtest framework (первый этап) | раздел 11 — детерминированный `BacktestEngine` + endpoint + тесты |

> Пометка «не реализовано» из ранних версий документации снята — эти фичи теперь покрыты кодом и тестами.

## 13.6. Приоритизация

| Приоритет | Фича | Обоснование |
|---|---|---|
| P0 | Emergency stop | безопасность: ручная остановка в любой момент |
| P0 | Persist daily PnL | лимит убытка не должен теряться при рестарте |
| P1 | LLM в бэктесте | соответствие живому конвейеру, достоверность критериев приёма |
| P1 | Партиционирование candles | ✅ TimescaleDB hypertable + retention 90 дней (раздел 6.4) |
| P2 | Distributed lock | мульти-реплика (после стабилизации singleton) |
| P2 | Multi-account | бизнес-расширение |
| P3 | RabbitMQ outbox | при росте нагрузки |
| P3 | ML-агенты | экспериментально, после стабилизации LLM-конвейера |

## 13.7. Детализация фич v2.2

### 13.7.1. Emergency stop

> **Статус**: ✅ реализовано (`EmergencyStopService`, 2 endpoints, halt `EMERGENCY_STOP` в `TradingGate`). Коммит с планом C-001..C-003 → emergency stop.

**Требования:**

- `POST /api/v1/bot/emergency-stop` с телом `{"reason": "...", "liquidate": bool}`.
- При вызове: флаг `bot:emergency-stop=true` в Redis + локально.
- `TradingBotService.run()`, `StrategyService.run()`, `monitor()` проверяют флаг в начале цикла → немедленный выход.
- Опционально: закрыть все открытые позиции рыночными ордерами (через outbox).
- Повторный вход: только после `POST /api/v1/bot/resume` или рестарта с очисткой флага.

**Реализация:**

| Слой | Изменение |
|---|---|
| Controller | 2 endpoints: `POST /emergency-stop`, `POST /resume` |
| Service | `EmergencyStopService` (флаг Redis + локальный, проверка в циклах) |
| Risk | `RiskManagementService` учитывает флаг в `validateNewStrategy` → сразу HOLD |

**Статус реализации (текущая версия):**

- `EmergencyStopService` — флаг Redis (`bot:emergency-stop`) + локальный, персист причины в `trading_halt` (reason `EMERGENCY_STOP`), восстановление после рестарта, опциональная ликвидация позиций через `TradingControlService.forceCloseNow("EMERGENCY_STOP")`.
- `TradingGate` — halt `EMERGENCY_STOP` → `TradingBlockReason.EMERGENCY_STOP` (source MANUAL/AUTO); блокирует все входы акций и фьючерсов (`isTradingEnabled()`).
- `StrategyService.run()` — ранний выход при активном флаге (метрика `strategy.skipped{reason=EMERGENCY_STOP}`).
- В текущей архитектуре `TradingBotService.run()`/`monitor()` отсутствуют (событийная модель): входы маршрутизируются через `onStrategyGenerated` → `TradingGate.isTradingEnabled()`, поэтому они блокируются автоматически; мониторинг открытых позиций и реконсиляция продолжают работать.
- Авто-стоп (source=AUTO, убыток >10% за час) — не реализован, требует хранения PnL с таймстампами (см. 5.8).
| Метрики | `bot.emergency_stop{reason}`, alert `EmergencyStop` |

### 13.7.2. Persist daily PnL ✅

**Реализовано:**

- Таблица `daily_risk_snapshot` — одна строка на дату (`UNIQUE (trade_date)`), миграция `004-futures-risk.sql` (раздел 6.6).
- `DrawdownProtectionService` — upsert снапшота на закрытие позиции и по циклу риск-движка.
- При старте (`ApplicationReadyEvent`) и при первом касании нового дня подгружается P&L за сегодня из БД — рестарт в течение дня не «забывает» накопленный убыток.
- Новый endpoint `GET /api/v1/risk/daily-pnl-history?days=30` (график дневных результатов).
- Сброс лимита — автоматически по календарной дате 00:00 МСК (новая строка даты).

### 13.7.3. Backtest: сохранение результатов ✅

**Реализовано:**

- Таблица `backtest_results(id, ticker, params jsonb, metrics jsonb, oos jsonb, created_at)` + индекс `(ticker, created_at DESC)` (миграция `022-backtest-results.sql`).
- `BacktestEngine.run` пишет результат после прогона (best-effort: сбой записи не роняет прогон; пустые прогоны с 0 сделок не сохраняются).
- `GET /api/v1/backtest/results?ticker=&limit=` — сравнение итераций (последние по времени, `limit` 1..100, params/metrics/oos отдаются распарсеным JSON).
- Метрика `bt_pass_total{result=PASS|REJECT}` — результат каждой итерации.
- `BacktestResult.metrics()` — компактная карта метрик для персиста (без equityCurve/monthlyReturns/tradeReturns).
- Walk-forward валидация `GET /api/v1/backtest/{ticker}/validate` также сохраняет прогон с OOS-сводкой (consistency/robust/oosTrades/oosReturn/oosSharpe/oosSortino/oosProfitFactor) — см. 13.7.7.

### 13.7.4. Точный контроль SL/TP в лимитных заявках ✅

**Идея:** при открытии позиции выставлять на бирже Alor условные stop- и take-profit-заявки, чтобы SL/TP исполнялись биржей независимо от бота (мгновенная реакция, переживают рестарт). Локальный мониторинг остаётся fallback'ом, если заявка не выставлена/уровень устарел.

**Реализация (LIVE-режим, `protectionOrdersEnabled = alorClient.isLiveMode`):**

| Слой | Изменение |
|---|---|
| Alor API | `POST /client/orders/actions/limit` со `type` в теле: `stop` (`stopPrice`+`stopEndUnixTime=0`) и `take-profit`; контракт доставки как у лимитки (idempotencyKey, sim-режим) — `placeStopOrder`/`placeTakeProfitOrder` |
| Outbox | в payload добавлены `purpose` (`sl`/`tp`/`cancel`) и `stopPrice`; тип `cancel` маршрутизируется в `cancelOrder` (CONFIRMED/REJECTED — однозначны, UNCERTAIN — retry) |
| `positions` | новые поля `sl_order_id`, `tp_order_id`, `sl_order_price`, `tp_order_price`, `sl_pending_replace`, `tp_pending_replace` (миграция `020-protection-orders.sql`) |
| Исполнение | `attachProtectionOrders` — выставление SL на `ExitRules.effectiveSl` (максимум жёсткого стопа и trailing) и TP на `takeProfit` из трёх точек подтверждения входа (full resolve / remainder cancel / WS fill) и из reconcile |
| Перевыставление | `onProtectionLevelsChanged` (trailing-монитор, стратегия) ставит флаг pending; `finishProtectionReplacement` снимает старую заявку и только ПОСЛЕ подтверждения отмены очищает флаг — новая выставляется на следующий цикл (защита от двойного стопа/тейка) |
| Reconcile | `reconcileProtectionOrders`: пропагация orderId из outbox (`resolveProtectionOutbox`, не пере-армирует заявку с подтверждённой отменой), детект исполнения/отмены через `verifyOrder`, завершение перевыставлений, выставление недостающих |
| Закрытие | `cancelProtectionOrders` снимает контр-заявки при закрытии; WS fill / verifyOrder защитной заявки → финализация с reason `STOP_LOSS`/`TAKE_PROFIT`; при `pendingClose` «в полёте» защитная заявка закрывает первой → локальный close-ордер отменяется |
| Мониторинг | `ExitRules.exchangeSlCovers`/`exchangeTpCovers` — если биржевая заявка актуальна на уровне, локальный мониторинг НЕ дублирует закрытие |

**Fallback:** при недоступности/отказе выставления (UNCERTAIN/FAILED с исчерпанными ретраями) локальный мониторинг SL/TP/trailing продолжает закрывать позиции как раньше.

### 13.7.5. Distributed lock ✅

**Идея:** разрешить запуск нескольких инстансов бота без гонок. Критические секции выполняет только «лидер» (владелец Redis-ключа), конкуренция исключается атомарным `SET key token NX PX`.

**Реализация:**

| Слой | Изменение |
|---|---|
| Redis-лок | `DistributedLockService`: `acquire` = `SET distributed-lock:<name> <uuid> NX PX ttl`, `release` = Lua compare-and-delete (удаляет ключ только своего владельца). TTL гарантирует освобождение при падении реплики; уникальный владелец исключает освобождение чужого прогона |
| Config | `distributed-lock.*` (`enabled`, `schedulerTtlSeconds`, `positionOpenTtlSeconds`). По умолчанию `enabled=false` — single-instance работает без Redis, `runExclusive` исполняет блок напрямую |
| Вход в позицию | `DecisionEngine.openPosition` берёт lock `position:<ticker>` (внутри per-ticker mutex) с `failOpenOnError=false`: при конкуренции/недоступности Redis вход пропускается (fail-closed — не открывать без лока) |
| Планировщики | lock `scheduler:*` (outbox-worker, strategy cycle, poll, reconciles, force close) с `failOpenOnError=true`: конкуренция → работает лидер, сбой Redis → блок всё равно исполняется (fail-open — не пропустить reconcile/close) |
| Метрики | `distributed.lock.acquired/contended/skipped/error/release.error` с тегом `name` |

**Что НЕ входит:** очередь RabbitMQ и полноценный leader-election-флаг (v2.3+, раздел 13.8). Lock работает в рамках одной БД — позиции и так идемпотентны (idempotencyKey + outbox + стейт-машина), это ещё один барьер против двойного входа.

### 13.7.6. Multi-account ✅

**Идея:** несколько Alor-портфелей через общий LLM-конвейер. Сигнал генерируется один раз, распределяется по аккаунтам (весовой round-robin с учётом ёмкости), ордера маршрутизируются в портфель аккаунта, лимиты риска — персональные. Пустая таблица `trading_accounts` = legacy single-account режим (портфель из `AlorConfig.portfolio`, позиции без `account_id`) — поведение идентично до-мультиаккаунтной версии.

**Реализация:**

| Слой | Изменение |
|---|---|
| Миграция | `021-multi-account.sql`: таблица `trading_accounts`; `account_id` на `positions`, `order_outbox`, `daily_risk_snapshot`; FK + индексы; уникальность дневных снапшотов `(trade_date, account_id)` + частичный индекс для global-строк |
| Аккаунт | `TradingAccount` (`alor_portfolio`, `exchange`, `enabled`, `aum_rub`, `max_open_positions`, `max_daily_loss_rub`, `weight`) — персональные переопределения лимитов, NULL = дефолт из `RiskConfig`/AUM |
| Распределение | `TradingAccountService.selectAccount` — весовой round-robin по включённым аккаунтам с ёмкостью (`max_open_positions`), кэш 30с; `portfolioOf(accountId)` — маршрутизация портфеля |
| Риск | персональный дневной лимит (`max_daily_loss_rub`), лимит позиций и AUM-переопределение на аккаунт; `daily_risk_snapshot.account_id` — снапшот P&L на аккаунт |
| Исполнение | `order_outbox.account_id` — доставка ордеров в нужный портфель; per-портфельные WS-подписки и реконсиляция состояний |
| Dashboard | `/api/v1/dashboard?accountId=` и SSE `/api/v1/dashboard/stream?accountId=` фильтруют снимок (позиции, closed-today, daily P&L) по аккаунту; null = агрегированный вид |

**API (управление и мониторинг, раздел 07):**

- `GET/POST /api/v1/accounts`, `GET/PUT/DELETE /api/v1/accounts/{id}` — CRUD реестра (ADMIN; DELETE — 409 при позициях или неотправленных outbox-ордерах);
- `GET /api/v1/accounts/{id}/dashboard` — live-снимок аккаунта (AUM, daily P&L, лимиты, позиции);
- `GET /api/v1/accounts/{id}/daily-pnl?days=` — история дневных P&L аккаунта;
- `GET /api/v1/dashboard?accountId=` / `GET /api/v1/dashboard/stream?accountId=` — фильтрация общего дашборда (404 при неизвестном аккаунте).

**Тесты:** `TradingAccountServiceTest` (legacy/round-robin/portfolio), `TradingAccountControllerIntegrationTest` (accounts CRUD, per-account dashboard и SSE-фильтр на Testcontainers), `DashboardServiceTest` (агрегированный vs per-account снимок).

### 13.7.7. Backtest: out-of-sample ✅

**Идея:** оценка стратегии на данных, не участвовавших в настройке — защита от переобучения на in-sample бэктесте (раздел 11.5, требование C-002). Вместо простого split 80/20 реализован walk-forward (скользящие фолды).

**Реализация (`BacktestValidator`):**

- Свечи делятся на последовательные фолды; для каждого фолда — in-sample (train) окно с подбором SL/TP по сетке `(1%,2%)/(2%,4%)/(3%,6%)` (максимум PF при ≥30 сделок, при равенстве — Sharpe), затем прогон на out-of-sample (test) окне, не участвовавшем в настройке.
- Агрегация OOS-сделок всех фолдов → сводная кривая капитала и метрики.
- `ValidationResult.isRobust()`: OOS Sharpe > 0.5, OOS PF > 1.1, ≥60% прибыльных фолдов, ≥100 OOS-сделок. Проваливается при переобучении и при тонком распределении сделок.
- Эндпоинт `GET /api/v1/backtest/{ticker}/validate?days=&folds=&timeframe=` возвращает consistency/robust/OOS-метрики и сохраняет прогон с OOS-сводкой в `backtest_results` (13.7.3).

**Тесты:** `BacktestValidatorTest`, OOS-персист в `BacktestResultPersistenceIntegrationTest`.

### 13.7.8. Backtest: Monte Carlo и стресс-сценарии (реализовано)

**Идея:** walk-forward (13.7.7) проверяет устойчивость к переобучению на данных, но не к
«удачному» порядку сделок и росту издержек. Monte Carlo + стресс-сценарии (обзор MR-004/H-003)
дополняют валидацию оценкой «хрупкости» доходности.

**Реализация (`MonteCarloAnalyzer`, раздел 11.7):**

- **Monte Carlo** — bootstrap-ресемплинг фактических сделок бэктеста с возвращением:
  N случайных путей (N = `bt.monte-carlo-simulations`, по умолчанию 1000) собирают по
  `trades.size` сделок из истории (порядок переставляется, повторы допускаются). Метрики
  распределения: медианная доходность, P5 (95% нижняя граница/VaR), P95, доля убыточных путей.
  `MonteCarloResult.isRobust()`: `p5Return > 0` и `probabilityOfLoss < 0.25` — доходность не
  достигается удачным порядком сделок. Seed фиксирован (`bt.monte-carlo-seed`, 42) —
  прогоны воспроизводимы.
- **Стресс-сценарии исполнения** — перепрогон движка с ужесточёнными издержками
  (комиссия и проскальзывание параметризованы в `SimulatedExecution`/`BacktestEngine`):
  `commission_x2`, `commission_x5`, `slippage_x2`, `slippage_x5`, `combined_stress` (×3+×3).
  Каждый сценарий пересчитывает метрики и `passable` — видно, при каком росте издержек
  стратегия перестаёт проходить критерии приёма.
- Сводный вердикт `BacktestRobustnessReport.isRobust()`: базовый прогон проходит критерии
  И Monte Carlo устойчив И все стресс-сценарии проходят.
- Эндпоинт `GET /api/v1/backtest/{ticker}/robustness?days=&simulations=&seed=` возвращает
  базовые метрики, распределение Monte Carlo и таблицу стресс-сценариев. Метрика
  `api.backtest.robustness` (тег `ticker`).

**Тесты:** `MonteCarloAnalyzerTest` (детерминизм по seed, квантили, стресс-мэппинг),
стресс-множители в `BacktestEngineTest`, параметризация издержек в `SimulatedExecutionTest`.

## 13.8. Детализация фич v2.3

### 13.8.1. LLM-агенты в бэктесте (реализовано)

Заменить `signalAt()` (детерминированный RSI/MACD) на конвейер агентов:

```mermaid
flowchart LR
    C[Candles] --> IC[IndicatorCalculator]
    IC --> TA[TechnicalAnalysisAgent]
    IC --> FA[FundamentalAnalysisAgent]
    TA --> ST[StrategyAgent]
    FA --> ST
    ST --> CT[ContrarianAgent]
    CT --> AR[ArbitratorAgent]
    AR -->|Final| SIM[SimulatedExecution]
```

Реализация: `BacktestSignalGenerator` (интерфейс, suspend `signal`) с двумя
компонентами — `DeterministicBacktestSignalGenerator` (индикаторный RSI/MACD,
выбран по умолчанию: `bt.agent.enabled=false`) и `AgentBacktestSignalGenerator`
(конвейер агентов: `bt.agent.enabled=true`). `BacktestEngine.simulate` и
`BacktestValidator.validate` стали suspend. Всё по конфигу `bt.agent.*`
(11.8.1), агентный режим включается профилем `backtest`
(`application-backtest.yml`). Детали и таблица решений:

| Проблема | Решение |
|---|---|
| Стоимость: 10 тикеров × 365 дней × 6 агентов | сэмплирование: сигнал каждые N баров (`bt.agent.sample-every`), tech+fund параллельно (`coroutineScope`/`async`) |
| Латентность | кэш LLM-ответов по fingerprint бара + изолированный namespace (`bt.agent.cache-namespace`) |
| Детерминированность | `bt.agent.temperature=0.0`, все агенты с детерминированными fallback (работают без API-ключа) |
| Тайм-ауты | resilience4j конфиг (`resilience4j.circuitbreaker.instances.llm`) |

Тесты: `AgentBacktestSignalGeneratorTest` (warm-up/сэмплинг/полная цепочка —
25 backtest-тестов зелёные).

### 13.8.2. WebSocket-only исполнение (реализовано)

**Функция — WS-primary доставка ордеров, REST — fallback.** Абстракция
`OrderTransport` (place-limit / place-conditional / cancel) с тремя реализациями:
`WsOrderTransport` (WebSocket), `RestOrderTransport` (REST-fallback) и
`RoutedOrderTransport` (маршрутизатор). Включается `alor.ws-orders-enabled=true`
(`ALOR_WS_ORDERS_ENABLED`, выключено по умолчанию).

Схема:

```mermaid
flowchart LR
    OB[OrderOutboxService] --> AL[AlorClient]
    AL --> RT[RoutedOrderTransport]
    RT -->|wsOrdersEnabled + LIVE + default portfolio| WS[WsOrderTransport]
    RT -->|Unavailable до отправки / не-WS сценарий| REST[RestOrderTransport]
    WS --> S[WS-канал OrdersGetAndSubscribeV2]
    S -->|событие id=idempotencyKey| C[Confirmed/Rejected]
```

Контракт доставки (единый для обоих транспортов):

| Исход | WS | REST |
|---|---|---|
| Принято | событие с `orderNumber` | 200 + `orderNumber` |
| Определённый отказ | WS-reject (status/error) | 4xx (кроме 429) |
| UNCERTAIN (неизвестно, могло дойти) | таймаут/обрыв → `OrderDeliveryUncertainException` | сеть/5xx/429 после retry → `OrderDeliveryUncertainException` |
| Fallback безопасен | `OrderTransportUnavailableException` ДО отправки (нет канала/не LIVE/не тот портфель) → маршрутизатор шлёт по REST | — |

Ключевые решения:

| Аспект | Решение |
|---|---|
| Канал | один persistent WS-сокет на дефолтный портфель (`ReactorNetty`), подписка `OrdersGetAndSubscribeV2`; переподключение с backoff 1s→60s |
| Корреляция | размещение по `id`=idempotencyKey, отмена по `orderNumber`+status; direct-ответы по `requestId`=guid |
| Роутинг | WS только для дефолтного портфеля + LIVE; multi-account/`SIMULATION` → REST (полностью корректный путь с реконсиляцией) |
| Таймаут ответа | `alor.ws-order-timeout-ms` (default 10000) → UNCERTAIN + State Reconciliation по idempotency key (нет double execution) |
| Парсер | `WsOrderMessages.matchPlace/matchCancel` exception-safe: битое сообщение ≠ обрыв канала |
| Токен | общий `AlorTokenProvider` (refresh + кэш) для REST и WS |
| Метрики | `alor.ws.orders.connected/disconnected/sent/confirmed/rejected/uncertain/fallback` |

Тесты: `WsOrderMessagesTest`, `WsOrderTransportTest`, `RoutedOrderTransportTest`,
`RestOrderTransportTest` (fake-сокет, 33 клиентских теста). Полный
`test ktlintCheck koverVerify` — BUILD SUCCESSFUL.

### 13.8.3. Панельный бэктест (реализовано)

`POST /api/v1/backtest/panel` (`PanelBacktestService`): прогон по нескольким тикерам за один
вызов, параллельно (`async`/`awaitAll`). Ответ — `results[]` (по тикеру) + `summary`
(распределение: `passShare`, `avgTotalReturn`, `medianTotalReturn`, `min`/`max`, `totalTrades`).
Дефолты параметров берутся из конфига `bt.*` (11.8.1). Результаты персистятся в
`backtest_results`, метрика `api.backtest.panel`. См. раздел 11.6.1.

### 13.8.4. Очередь (RabbitMQ) для outbox (реализовано)

**Функция — дополнительный канал доставки** outbox-строк (не замена): при сохранении ордера
помимо inline-dispatch `OrderOutboxPublisher` публикует id строки в RabbitMQ, консьюмер
`OutboxOrderConsumer` диспетчирует его через тот же `OrderOutboxService.redispatchById`
(единый диспетчер → те же гарантии идемпотентности, что и у inline/DB-worker). Источник
истины — строка в БД: RabbitMQ не является обязательным компонентом, DB-worker остаётся
фолбэком, гарантии «никакого double execution» не меняются.

Схема:

```mermaid
flowchart LR
    S[placeOrder / placeCancelOrder] -->|save PENDING| DB[(order_outbox)]
    S --> P[OrderOutboxPublisher] -->|best-effort id| EX[(exchange)]
    EX --> Q[queue]
    Q --> C[OutboxOrderConsumer]
    C --> R[redispatchById]
    R -->|PENDING → dispatch| AL[AlorClient]
    R -->|SENT/FAILED| DB
    DB -->|findRetryable| W[DB-worker] --> AL
    C -->|reject после bounded retry| DLX[(dlx)] --> DLQ[dlq парковка]
```

Детали реализации:

| Аспект | Решение |
|---|---|
| Топология | `exchange` (Direct) + `queue` (durable, `x-dead-letter-exchange = dlx`) + `dlq`; биндинг по `routingKey` |
| Ack | **AUTO** — контейнер подтверждает после нормального возврата, отклоняет при исключении |
| Retry | stateless, 3 попытки (backoff 1s → 2s → 10s), после исчерпания `RejectAndDontRequeueRecoverer` → DLQ |
| Идемпотентность | консьюмер не исполняет ордер сам — вызывает `redispatchById` (PENDING → dispatch, SENT → ack, FAILED → ack без переотправки) |
| Ошибки | невалидный body / сбой диспетчера → bounded retry → DLQ; строка в БД остаётся за DB-worker'ом |
| Publisher | best-effort: сбой публикации проглатывается (inline-dispatch не зависит от Rabbit) |
| Включение | `app.outbox.rabbitmq.enabled=true` + `spring.rabbitmq.*`; выключено по умолчанию (поведение прежнее) |
| Метрики | `outbox.published`, `outbox.publish_failed`, `outbox.consumed{outcome}`, `outbox.consumed_failed`, `outbox.consumed_invalid` |

**Ключевые решения:**

- **AUTO вместо MANUAL**: при ручном ack контейнер НЕ отклоняет сообщение после исчерпания
  ретраев (отклонение требует `AmqpRejectAndDontRequeueException` с `rejectManual=true`),
  поэтому poison-сообщение застревало бы unacked и не попадало в DLQ.
- **Без `Boolean`-возврата в listener**: в Spring AMQP 4.x `Boolean`-возврат трактуется как
  reply-пакет, а не ack-сигнал → двойной ack (`unknown delivery tag`) и повторная обработка
  заакенных сообщений.
- **Консьюмер условен**: бин создаётся только при `app.outbox.rabbitmq.enabled=true`
  (одно условие с фабрикой контейнера).

**Тесты:** `RabbitMqTransportIntegrationTest` (полный путь против Postgres + RabbitMQ:
publish → consume → SENT; already-SENT не переотправляется; invalid → DLQ),
`OrderOutboxPublisherTest`, `OutboxOrderConsumerTest`, хуки в `OrderOutboxServiceTest`.

### 13.8.5. Уменьшение LLM-латентности (реализовано)

Две независимые оптимизации агентного контура:

1. **Параллельные вызовы независимых агентов.** `DiscretionaryStrategy.runChain`
   (live-контур для A/B-эксперимента и аналитики) запускал Technical + Fundamental
   + адаптивный порог строго последовательно. Теперь первые два — как в
   `AgentBacktestSignalGenerator` (13.8.1) — исполняются параллельно
   (`coroutineScope`/`async`), что вдвое сокращает lat-часть цепочки до первого
   LLM-вызова стратега. Зависимая часть (strategist → contrarian → arbitrator)
   остаётся последовательной — шаг зависит от результата предыдущего.
   Проверка параллельности — `DiscretionaryStrategyTest` (3 вызова стартуют
   одновременно, `maxConcurrent ≥ 2`).

2. **Дельта-промпты.** При `llm.delta-prompts-enabled=true` (`LLM_DELTA_PROMPTS_ENABLED`,
   выключено по умолчанию) стратег и контрариан получают вместо полного текста
   `techReasoning`/`fundReasoning` только компактную дельту отчётов с прошлой оценки
   тикера:

   - `AgentReportDelta` — чистый компрессор: `null` на первой оценке (полный текст),
     `"NO_CHANGE"` при идентичных значениях, иначе перечисление изменённых полей
     (`conclusion`, `confidence`, `trend`, `rsi`, `atr`, `macd`, `reasoning` до 120
     символов). Числовые поля — с `Locale.ROOT` (запятая от локали не попадает в промпт).
   - `DeltaPromptStore` — in-memory `ConcurrentHashMap` последних отчётов по тикеру
     (конкурентный доступ: тикеры обрабатываются параллельно); состояние обновляется
     после каждого цикла, при перезапуске пусто → первая оценка всегда с полным текстом.
   - Фолбэк: при выключенной фиче/отсутствии предыдущего отчёта агенты работают как
     раньше (полный `reasoning`). Сигнальные поля (`conclusion`/`confidence`/`trend`/`rsi`)
     передаются всегда — дельта сжимает только текст обоснований.
   - Метрика: `agent.delta.prompts{mode=DELTA|FULL}` — доля циклов с дельтой.

   Дельта-промпты не затрагивают критический путь советника: `LlmAdvisor` — один
   LLM-вызов, там сжимать нечего. Ограничение в 2 одновременных LLM-вызова
   (`llm.queue-concurrency`) и semantic cache (по fingerprint рынка) не менялись.

**Тесты:** `DiscretionaryStrategyTest` (цепочка + параллельность + дельты),
`AgentReportDeltaTest`, `DeltaPromptStoreTest` (5 юнит-тестов компрессора, 5 —
хранилища). Полный набор: 563 теста, 1 Docker-зависимый fail (`SemanticCacheTest`),
ktlint чист.

## 13.9. Метрики зрелости продукта

| Уровень | Признак |
|---|---|
| L1 — прототип | бот работает в SIMULATION, LLM fallback допустим |
| L2 — стабильный | 1 неделя SIMULATION без `bot.halted.daily_loss`, метрики полные |
| L3 — проверенный | бэктесты PASS по ≥ 5 тикерам, chaos-тесты зелёные |
| L4 — боевой | LIVE с лимитами, emergency stop, persist daily PnL (v2.2) |
| L5 — масштабируемый | мульти-реплика (distributed lock), k8s, RabbitMQ (v2.3+) |

Целевое состояние на текущий момент — **L3** (бэктест реализован, но критерии ещё не подтверждены данными реального портфеля).

## 13.10. Критерии выхода из беты

- [ ] 2 недели подряд без ошибок цикла (`bot.cycle.error == 0`)
- [ ] `maxConsecutiveLosses < 4` для всех тикеров (нет пауз)
- [ ] Проскальзывание ≤ 0.1% в среднем (`trade.slippage.rub / общий оборот`)
- [ ] Бэктест `isPassable() == true` для ≥ 50% тикеров портфеля
- [ ] Все алерты критического уровня срабатывают корректно (проверено)
- [ ] Документация актуальна (этот документ — часть DoD)

## 13.11. Детализация v2.4 (ML-агенты)

**Задачи для ML-замены** (где детерминизм/скорость важнее гибкости):

| Задача | Сейчас (LLM) | ML-кандидат |
|---|---|---|
| Скрининг кандидатов (10 тикеров → топ-N) | каждый тикер полный конвейер | градиентный бустинг по признакам индикаторов |
| Оценка вероятности продолжения тренда | StrategyAgent (LLM) | бинарный классификатор (тренд вверх/вниз) |
| Порог уверенности | AdaptiveRiskService (правила) | онлайн-калибровка по исходам сделок (✅ 13.11.8) |

**Признаки** (features): RSI, ATR%, MACD-гистограмма, Bollinger %B, EMA-наклон, волатильность, winRate/чac, слепые зоны тикера, макро (ставка, нефть, курс).

**Pipeline**:

1. Сбор датасета: `positions` (исход сделки) + `candles` + `agent_logs`.
2. Обучение на CI (этап в пайплайне, данные — экспорт из БД).
3. Валидация: out-of-sample 20%, метрика — выигрыш у LLM-baseline (M3).
4. Промоушн: feature-флаг `ml.enabled`.

**Риски**:

- Переобучение на короткой истории (мало сделок).
- Нестационарность рынка MOEX — дрейф признаков.
- Регуляторные ограничения автоматической торговли.

### 13.11.1. Шаг 1: экспорт датасета (реализовано)

**Endpoints** (закрыты аутентификацией, гейтятся `ml.enabled=false` → 404):

- `GET /api/v1/ml/dataset?since=&ticker=&maxRows=` — CSV-файл `ml_dataset.csv`
  (query-параметры опциональны; строки — самые свежие закрытые позиции);
- `GET /api/v1/ml/dataset/stats?since=&ticker=` — качество данных: число позиций,
  win rate, разбивка по тикерам/направлениям, суммарный P&L.

**Строка датасета** (`MlDatasetRow`, 28 колонок):

- **Метка**: `win` (pnl > 0), `pnl_rub`, `pnl_percent`, `close_reason`, `duration_min`, `hour_of_day`;
- **Признаки на входе** (без lookahead, по свечам до `openedAt`, `MlFeatureExtractor`):
  `rsi14`, `atr_percent`, `macd_hist_percent`, `bb_percent_b`, `ema_slope_percent`,
  `volatility20_percent`, `ret_3`, `ret_10`, `ret_20`;
- **Макро**: `cbr_rate`, `brent`, `usd_rub`;
- **LLM-агент** (`agent_logs` по `cycleId`): `strategy_action`, `strategy_confidence`
  (Agent-3-Strategist, последняя запись цикла);
- **Слепая зона**: `in_blind_spot_hour` — активная слепая зона тикера
  «Entry at hour H for TICKER» на час входа.

**Известные ограничения (задокументированные риски)**:

- Макро-значения — снапшот конфига/рынка на момент экспорта, а не исторические:
  для корректного обучения без lookahead нужен исторический макро-контекст
  (следующий инкремент — таблица макро-снапшотов).
- `winRate/час` как признак не включён (агрегат по прошлым сделкам); час входа
  выгружается сырым (`hour_of_day`), агрегация — на этапе обучения.
- Позиции без 30+ свечей до входа пропускаются (`skippedInsufficientData`).

**Метрики**: `ml.dataset.export` (counter, mode), `ml.dataset.export.rows/skipped/positions`
(gauges). Конфиг: `ml.enabled`, `ml.dataset.timeframe`, `ml.dataset.lookback-bars`,
`ml.dataset.max-rows`.

### 13.11.2. Исторические макро-снапшоты (реализовано)

Устраняет задокументированный риск 13.11.1: макро-признаки в обучающей строке
теперь соответствуют моменту ВХОДА в позицию (без lookahead-утечки), а не моменту
экспорта.

**Схема**:

1. `MacroSnapshotCollector` (`@Scheduled`, период `macro.snapshot-interval-ms`)
   раз в N минут берёт `MacroContextService.fetch()` и сохраняет слепок в
   `macro_snapshots` (`captured_at`, `cbr_rate`, `brent_price`, `usd_rub`).
   Работает только при `macro.snapshot-enabled=true`; ошибки сбора не роняют бота.
2. `MlDatasetService.export` одной выборкой `findBetween` загружает снапшоты на
   всё окно позиций (от `min(openedAt) - 1 день` до `max(openedAt)`) и для каждой
   строки бинарным поиском берёт **последний снапшот с `captured_at <= openedAt`**
   (снапшоты после входа не используются — граница lookahead).
3. Если снапшота на момент входа нет (исторические сделки до включения
   коллектора) — фолбэк на текущий контекст. Источник фиксируется новой колонкой
   `macro_source`: `SNAPSHOT` | `CURRENT` (всего в CSV теперь 29 колонок).

**Ограничение**: снапшоты собираются «вперёд» — сделки, закрытые до включения
коллектора, остаются с `macro_source=CURRENT`. Для датасета с полным историческим
макро нужно включить коллектор заранее (`MACRO_SNAPSHOT_ENABLED=true`).

**Метрики**: `macro.snapshot.saved` / `macro.snapshot.collect.error` (counters),
`ml.dataset.macro.source` (counter, tag `source=SNAPSHOT|CURRENT`). Конфиг:
`macro.snapshot-enabled`, `macro.snapshot-interval-ms`.

### 13.11.3. Шаг 2: обучение на CI (реализовано)

Обучающий пайплайн в `ml/` (Python 3.11, градиентный бустинг), запускаемый
GitHub Actions (`ml-train.yml`). Потребляет CSV-экспорт `GET /api/v1/ml/dataset`
и обучает бинарный классификатор исхода позиции (цель `win`).

**Дизайн (принципы борьбы с lookahead/переобучением):**

| Аспект | Решение |
|---|---|
| OOS-выборка | **Temporal split** — последние 20% сделок по `opened_at` (без случайного сплита, который «заглядывает в будущее»); на train-части дополнительно отрезается хвост 10% как validation для early stopping |
| Модель | CatBoost (default, артефакт `model.cbm`) или LightGBM (`--model lightgbm`, `model.txt`); `auto_class_weights=Balanced`, `early_stopping` |
| Признаки | 15 числовых (индикаторы + макро + `strategy_confidence` + `hour_of_day` + `in_blind_spot_hour`) + 2 категориальных (`strategy_action`, `direction`); пропуски обрабатываются нативно; мета-колонки (id/даты/цены/P&L/`macro_source`) в модель НЕ подаются |
| Воспроизводимость | `--seed` (default 42); отчёт пишет версию модели, сплит и признаки |
| Данные | защита от дрейфа схемы: требуются все 29 колонок `CSV_HEADER`; аборт при < `--min-rows` (50), пустом датасете или одном классе в train |

**Оценка (отчёт `eval_report.json` + `training.log`):**

- Классические метрики на OOS: accuracy/precision/recall/f1/ROC-AUC/log-loss;
- Стратифицированная 5-fold CV (ROC-AUC) на train-части;
- **Метрика M3 (profit factor на OOS)**: `all_trades` (все сделки) vs
  `llm_baseline` (только строки, где стратег сказал BUY/SELL) vs
  `ml_selected` (прогноз `p >= --threshold`, default 0.5);
  `m3.ml_beats_llm_pf` / `ml_beats_all_pf` — результат сравнения.
- `feature_importance.tsv` — ранжирование признаков (контроль дрейфа).

**Артефакты** (upload-artifact, retention 30 дней): `model.cbm|model.txt`,
`eval_report.json`, `feature_importance.tsv`, `training.log`.

**CI (`ml-train.yml`)**: `workflow_dispatch` (ручной запуск) + еженедельный
`schedule` (пн 05:00 UTC). Датасет скачивается через API: `POST /auth/login`
(секреты `ML_API_BASE/USERNAME/PASSWORD`) → Bearer → `/api/v1/ml/dataset`;
либо прямая ссылка через input `dataset_url`. Секреты и `ml.enabled=true`
обязательны (иначе эндпоинт экспорта отдаёт 404).

**Тесты**: `ml/tests/test_train.py` (pytest, smoke на синтетическом датасете:
успешный прогон + артефакты, отказ на пустом/малом датасете, дрейф схемы).
Проверяются в CI (джоба `ml-training`, лёгкий набор: lightgbm без catboost).

**Ограничения (задокументированные)**:

- Метрика PF считается по фактическому P&L позиций датасета; «LLM-baseline» —
  эвристика (строки со `strategy_action ∈ {BUY, SELL}`), а не отдельный прогон
  LLM-конвейера.
- `hour_of_day` подаётся сырым числом (циклическое преобразование sin/cos —
  будущее улучшение).
- Качество модели ограничено историей сделок: при < 50 строках пайплайн
  абортится (сигнал — копить данные через коллектор макро-снапшотов).

### 13.11.4. Шаг 3: инференс и скрининг кандидатов (реализовано)

Загрузка обученной модели CatBoost (артефакт 13.11.3, `model.cbm`) в бэкенд и
скрининг кандидатов: ранжирование тикеров по вероятности выигрышного исхода.

**Зависимость**: `ai.catboost:catboost-prediction:1.2.8` (JVM-инференс, нативные
библиотеки включены для Windows/Linux/macOS).

**Порядок признаков фиксируется дважды**: `ml/train.py` (`NUMERIC_FEATURES` +
`CATEGORICAL_FEATURES`) и `MlFeatureVector` (`numericFeatures()` /
`categoricalFeatures()`) — 15 числовых + 2 категориальных. Несовпадение порядка
даёт «мусорные» предсказания без ошибки, поэтому порядок покрыт юнит-тестом
(`MlFeatureVectorTest`).

**Инференс** (`service/ml/`):

- `MlModel` — интерфейс (`probability(numeric, categorical)` → 0..1);
- `CatBoostMlModel` — обёртка над `CatBoostModel.loadModel(path)` + сигмоида
  (raw score CatBoost — лог-ит, вероятность получается сигмоидой);
- `MlModelProvider` — ленивая загрузка при первом обращении, кэширование;
  **graceful degradation**: при `ml.enabled=false` или отсутствующем/битом файле
  подставляется `NoopMlModel` — бот не падает, скрининг отвечает 503.

**Скрининг** (`GET /api/v1/ml/screen?tickers=SBER,GAZP&topN=5`):

- Признаки считаются на ТЕКУЩИЙ момент как на вход в позицию: свечи
  (`MlFeatureExtractor`), последний макро-снапшот `captured_at <= now`
  (без lookahead, фолбэк на текущий контекст), слепая зона на текущий час;
- Решение стратега на момент скрининга ещё не принято: `strategy_action=""`,
  `strategy_confidence=NaN` (отдельная категория/пропуск, как в обучении);
- Модель прогоняется в **обоих** направлениях (LONG/SHORT), для тикера остаётся
  лучшее; результат сортируется по вероятности убывания и ограничивается topN;
- Тикеры без 30+ свечей пропускаются в `skipped`.

**Коды ответа**: `ml.enabled=false` → 404; модель недоступна → 503; пустой
`tickers` → 400; успех → 200 с `candidates` (ticker/direction/probability/
inBlindSpotHour/hourOfDay) и `skipped`.

**Метрики**: `ml.screening` (counter, `status=OK`), `ml.screening.candidates`
(gauge), `ml.screening.skipped` (gauge). Конфиг: `ml.model.path` (default
`ml/model.cbm`), `ml.screening.top-n` (default 5).

**Промоушн**: положить `model.cbm` по пути `ML_MODEL_PATH` (env-оверрайд
`ml.model-path`) и включить `ml.enabled=true` — скрининг начнёт отдавать
результаты без пересборки.

### 13.11.5. ML-фильтр входа в торговый цикл (реализовано)

Интеграция обученной модели в реальный торговый цикл: прогноз CatBoost как гейт
входа. `DecisionEngine` после risk-вердикта `Allowed` вызывает `MlEntryFilter`:
если вероятность выигрышного исхода для сигнала ниже порога — вход отклоняется
(метрика `${profile.metricPrefix}.risk.reject`, reason=`ML_FILTER`).

**Признаки** строятся на текущий момент (`MlFeatureResolver`), как на вход в
позицию: свечи + последний макро-снапшот без lookahead (фолбэк на текущий
контекст) + слепая зона на текущий час. В отличие от скрининга, используются
реальные `strategy_action`/`strategy_confidence` из сигнала (LLM-стратег уже
отработал).

**Конфиг**: `ml.filter.enabled` (default false), `ml.filter.threshold` (0.5).
Отдельный флаг от `ml.enabled`: включение модуля (например, для экспорта
датасета) само по себе не гейтит входы.

**Политика отказов**: при выключенном фильтре — pass-through; при включённом
фильтре и недоступной модели или недостатке данных — вход БЛОКИРУЕТСЯ
(fail-closed: оператор явно включил фильтр, вход без ML-оценки недопустим).

**Метрики**: `ml.entry.filter` (counter, tags `ticker`,
`result=PASS|REJECT|FAIL_CLOSED`), плюс `${profile.metricPrefix}.risk.reject`
(reason=`ML_FILTER`).

**Ограничение**: фильтр применяется к живым входам (`DecisionEngine`);
интеграция в `BacktestEngine` описана в разделе 13.11.6.

### 13.11.6. ML-фильтр в бэктесте (реализовано)

Применение того же [MlEntryFilter] (раздел 13.11.5) к входам `BacktestEngine` —
консистентность результатов бэктеста и live-цикла.

**Конфиг**: `bt.ml-filter-enabled` (default false, env `BT_ML_FILTER_ENABLED`).
Флаг бэктеста не влияет на live-гейт (`ml.filter.enabled`): можно оценить влияние
модели на бэктесте до включения фильтра в реальном цикле.

**Семантика**:

- При `bt.ml-filter-enabled=true` фильтр вызывается на каждой попытке входа
  (новое открытие и реверс) на момент бара (`at = current.time`), признаки — как
  в live, но `strategy_confidence=null` (детерминированный генератор не даёт
  уверенности → отдельная категория пропуска в модели);
- При `requireEnabled=false` глобальные флаги `ml.enabled`/`ml.filter.enabled`
  игнорируются (бэктест — изолированный прогон), но модель должна быть доступна:
  `ml.enabled=false` или отсутствующий файл → fail-closed (все входы блокируются);
- Сигнал инверсии при отклонённом встречном входе: текущая позиция закрывается,
  встречная не открывается (как в live);
- Блокировки не считаются сделками и не попадают в `backtest_results` (пустой
  прогон = 0 сделок); счётчик блокировок пишется в лог и метрику.

**Метрика**: `bt_ml_blocked_total` (counter, tag `ticker`) — число входов,
отклонённых ML-фильтром за прогон.

**Замечание по производительности**: признаки строятся на каждый бар входа
(запросы к свечам/снапшотам/слепым зонам); для длинных прогонов с включённым
фильтром ожидаемо большее время.

### 13.11.7. ML-оценка удержания тренда (реализовано)

Ответ на вопрос «в какую сторону рынок скорее продолжит движение» —
задача из таблицы ML-замен (оценка вероятности продолжения тренда).

**Скоринг** ([MlTrendScore], чистая функция): `trendScore = 0.6 * P(win) + 0.4 *
сила_тренда_по_индикаторам`, где P(win) — прогноз модели для направления, а
детерминированная сила тренда (0..1) — согласованность индикаторов признакового
вектора с направлением: EMA-наклон, return20, MACD-гистограмма, отклонение %B от
средней полосы (0.5 — нейтрально, 1.0 — все индикаторы за направление). Модель
обучена на исходах позиций, поэтому P(win) = P(продолжение движения) на горизонте
`ml.trend.horizon-bars` (default 6 баров MINUTE_10 ≈ 1 час, интерпретационный).

**Endpoint**: `GET /api/v1/ml/trend?tickers=SBER,GAZP&topN=5` — те же признаки и
батч-паттерн, что у скрининга (свечи + макро-снапшот без lookahead + слепая зона);
модель прогоняется в обоих направлениях, для тикера остаётся лучшее по trendScore.
Ранжирование идёт по trendScore (а не по сырой вероятности, как в 13.11.4).
Коды: `ml.enabled=false` → 404; модель недоступна → 503; пустой `tickers` → 400.

**Тренд-гейт входа** (опциональный, default off): при
`ml.filter.trend-gate-enabled=true` вход в позицию (live и бэктест) требует
`trendScore >= ml.filter.trend-min-score` (default 0.5) в дополнение к порогу
вероятности 13.11.5. Отдельный флаг от `ml.filter.enabled` — включается после
валидации прогноза тренда без изменения поведения базового фильтра. При
выключенном гейте поведение фильтра 13.11.5/13.11.6 не меняется.

**Метрики**: `ml.trend.forecast` (counter, status=OK), `ml.trend.candidates` /
`ml.trend.skipped` (gauges).

**Ограничение**: тренд-классификатор использует ту же модель win-исхода (в
обучении `ml/train.py` целевая метка — `win` позиции, а не явная метка
«тренд вверх/вниз»); полноценный отдельный классификатор тренда (метка по
форвардной доходности) — будущий шаг в `ml/train.py` (`--target trend`).

### 13.11.8. Онлайн-калибровка порога уверенности (реализовано)

Замена статичных правил по win rate на обучение по накопленным исходам сделок —
задача «Порог уверенности» из таблицы ML-замен (13.11): порог тикера должен
ужесточаться, когда сделки с низкой уверенностью стратега проигрывают, и
смягчаться, когда они выигрывают.

**Механика** ([ConfidenceCalibrator], чистая функция; вызов —
`AdaptiveRiskService.getAdaptiveConfidenceThreshold`):

1. Закрытые позиции тикера за окно `risk.confidence-calibration-days` (14 дней)
   с заполненными `cycleId` и `pnl`;
2. Для их `cycleId` батч-запросом поднимается уверенность стратега на входе
   (`agent_logs`, агент `Agent-3-Strategist`); позиции детерминированных стратегий
   без лога стратега в выборку не попадают;
3. Ищется НИЖНЯЯ граница уверенности c в диапазоне
   [min-threshold..max-threshold] (0.50..0.85, шаг 0.05), при которой выборка
   `confidence >= c` содержит >= `min-trades` (10) сделок и имеет win rate
   >= `target-win-rate` (0.55);
4. Порог из диапазона ограничен сверху/снизу конфигом — калибровка не может
   уйти в экстремумы (0 или 1) из-за шума маленькой выборки.

**Fallback** при недостатке данных (калибровка выключена, < `min-trades` сделок,
ни одна граница не достигает целевого win rate) — прежние правила по win rate
за 14 дней (0.55–0.80, раздел 3.5). Fallback накапливается отдельной метрикой,
так что включение калибровки диагностируемо.

**Конфиг**: `risk.confidence-calibration-*` (enabled/days/min-trades/target-win-rate/min-threshold/max-threshold/step).

**Метрики**: `adaptive.confidence_threshold` (gauge, `ticker` — применяемый порог),
`adaptive.confidence_calibrated` / `adaptive.confidence_fallback` (counters, `ticker`).

**Ограничение**: порог влияет на вход (guardrail LOW_CONFIDENCE / override
LOW_DRAFT_CONFIDENCE), но не масштабирует размер позиции; confidence-сайзинг —
отдельная задача в риск-движке.

### 13.11.9. Confidence-aware позиционный сайзинг (реализовано)

Размер позиции теперь учитывает уверенность сигнала — завершение формулы
H-001: `размер = капитал × риск% × волатильность × уверенность`. Связывает
калиброванный порог (13.11.8) с сайзингом: чем сильнее сигнал превышает порог,
тем больше позиция.

**Механика** (`AdaptiveRiskService.calculateOptimalPositionSize`, новый множитель
`confidenceSizingFactor`):

- Уверенность сигнала (`Signal.confidence`) передаётся из `StockEntryProfile.sizePosition`;
- Линейная интерполяция между `risk.confidence-sizing-min-factor` (0.5) при
  `confidence == адаптивный порог тикера` (getAdaptiveConfidenceThreshold, 13.11.8)
  и `risk.confidence-sizing-max-factor` (1.0) при `confidence >= risk.confidence-sizing-ceiling` (0.90);
- Confidence ниже порога — clamp на min-factor (страховка; вход и так гейтится порогом);
- Множитель только УРЕЗАЕТ размер относительно baseline (max factor = 1.0) — никогда
  не раздувает позицию;
- `confidence == null` (API, детерминированные сигналы) или `confidence-sizing-enabled=false` →
  множитель 1.0, поведение прежнее.

**Конфиг**: `risk.confidence-sizing-*` (enabled/min-factor/max-factor/ceiling).

**Метрика**: `adaptive.confidence_factor` (gauge, `ticker`).

**Ограничение**: множитель применяется в stock-контуре (`AdaptiveRiskService`);
фьючерсный сайзинг Si остаётся фиксированным (1 контракт, раздел 15).

## 13.12. Детализация v2.5 (Cross-exchange)

**Целевые рынки**: MOEX (основной) + внебиржевые/другие площадки по мере готовности.

**Арбитражные условия** (осторожно):

- Расхождение котировок > комиссии + проскальзывание + издержки перевода.
- Синхронизация времени (NTP), минимальное окно проверки.

**Ограничения**:

- Валютные/юридические ограничения движения капитала.
- Разные режимы торгов (T+2 на MOEX).
- Арбитраж выполняется **только** в SIMULATION до отдельного approval.

## 13.13. Управление зависимостями roadmap

```mermaid
flowchart LR
    A[Persist daily PnL] --> B[Emergency stop]
    A --> C[История P&L график]
    B --> D[v2.3 база стабильности]
    D --> E[LLM в бэктесте]
    E --> F[Панельный backtest]
    F --> G[v2.4 ML-агенты]
    D --> H[WebSocket-only]
    H --> I[RabbitMQ outbox]
    I --> J[Мульти-реплика + lock]
    J --> K[v2.5 cross-exchange]
```

Ключевое: **persist daily PnL — фундамент** для emergency stop и истории; **LLM в бэктесте — фундамент** для ML-этапа (метрики качества решений).

## 13.14. Риски дорожной карты

| Риск | Вероятность | Влияние | Митигация |
|---|---|---|---|
| LLM-бэктест дорогой/медленный | средняя | задержка M2 | сэмплирование баров, кэш ответов |
| MOEX меняет API | низкая | пауза интеграций | абстракция клиентов, тесты на контракты |
| Переобучение ML | высокая | ложная прибыль | строгая out-of-sample валидация |
| Регуляторные ограничения | низкая | остановка LIVE | SIMULATION-first, approval-гейт |
| Утечка секретов | низкая | критично | IAM-аудит, ротация, никаких секретов в git |
| Бот в одной реплике → SPOF | средняя | простой | distributed lock (раздел 13.7.5, ✅ v2.2) |

## 13.15. KPI по версиям

| Версия | KPI успеха |
|---|---|
| v2.1 (текущая) | 39 тестов зелёных; событийный слой без потерь (`event.handled ≈ event.published`); бэктест-endpoint работает |
| v2.2 | emergency stop < 1 c на реакцию; daily PnL переживает рестарт (тест); candles — гипертаблица TimescaleDB, выборка за год < 200 мс, retention удаляет чанки > 90 дней |
| v2.3 | LLM-бэктест ≥ 5 тикеров PASS; WebSocket-only 1 неделя SIMULATION без ошибок |
| v2.4 | ML-модель > LLM-baseline на out-of-sample по profit factor |
| v2.5 | 0 ложных арбитражных входов за месяц SIMULATION |

## 13.16. Процесс внесения фичи

1. **RFC**: описание в разделе roadmap (этот файл), владелец, KPI.
2. **TDD**: тесты до кода (например, для backtest — сначала `BacktestEngineTest`).
3. **Промежуточный деплой**: SIMULATION на 1 неделю.
4. **Оценка по KPI** таблицы выше.
5. **Промоушн**: только если KPI достигнуты; обновить README и статусы в этом разделе.

Этот процесс гарантирует, что статус в документации («🔜» / «✅») всегда честно отражает код — как это сделано для v2.1 (раздел 13.5).

## 13.17. Покрытие тестами (Kover)

- **Гейт в CI**: `./gradlew koverVerify` — правило `minBound(50%)` по всем модулям, `onCheck=true`.
  Отчёт `build/reports/kover/report/index.html` выгружается артефактом каждого PR.
- **Локальная проверка**: `.\gradlew.bat koverReport` (HTML/XML/Binary), затем `.\gradlew.bat koverVerify`.
- Текущий уровень покрытия и разбивка по пакетам — в отчёте Kover.

**План повышения покрытия до 100% (к v2.2):**

| Пакет / класс | Что добавить | Приоритет |
|---|---|---|
| `client.AlorRestClient` / `AlorWebSocketClient` | unit-тесты на mock WebClient/WS-потока (парсинг, fallback, outbox-повторы) | P0 |
| `service.RiskManagementService` | пороговые сценарии: maxPositionRub, daily loss limit, sector/volatility, restart-resume daily PnL | P0 |
| `service.FeedbackService` / `SelfLearningService` | feedback-парсинг, обучение агентов, fallback на дефолт | P1 |
| `service.StrategyService` | HOLD при `confidence < 0.5`, учёт sector/volatility guard | P1 |
| `service.SettingsService` | применённые настройки → RiskConfig/LeverageConfig (runtime), валидация | P1 |
| `controller.*` | `@WebMvcTest` для всех endpoints (роли ADMIN/ANALYTICS, в т.ч. запрет POST для ANALYTICS) | P1 |
| `infrastructure.*` (UuidV7, outbox poller, промпты) | краевые случаи, retry, таймауты | P2 |
| `backtest.BacktestEngine` | реальная фикстура MOEX уже покрыта (`RealDataBacktestFixtureTest`); добавить edge-кейсы (пустой вход, деление на 0) | P2 |

> Критерий «100% покрытие» применяется к критичным торговым путям (client, risk, execution, settings);
> для генерации отчётов и инфраструктуры допускается исключение через `@Generated`/фильтры Kover.

## 13.18. Наблюдаемость LLM-агента (3 фазы) — реализовано

План наблюдаемости LLM-агента выполнен полностью (compile + tests + ktlint зелёные).

### Phase 1 — Structured JSON logging + trace_id ✅

- `logback-spring.xml`: JSON в stdout (LogstashEncoder), профиль `!json-logs-off`.
  MDC-поля: `trace_id`, `cycle_id`, `ticker`, `agent`, `application=mmvb-trading-bot-v2`.
- `TraceContext` (`infrastructure/tracing`): MDC-контекст корутин (`mdcContext`/`withMdc`).
- `StrategyService.run()`: `trace_id = cycle_id = UuidV7`, дочерние корутины наследуют MDC.
- `ResilientLlmClient.complete()`: `withMdc(AGENT=...)` вокруг каждого LLM-вызова.
- `Position.cycleId` + миграция `013-position-cycle-id.sql` (индекс); `PositionOpened/ClosedEvent.cycleId`.

### Phase 2 — Трейс-хранилище S3/MinIO ✅

- `TraceStorage` / `S3TraceStorage` (`infrastructure/tracing`): lazy MinioClient,
  auto-create бакета, ключ `<traceId>/<agent>/<createdAt>-<uuid>.json`, best-effort.
- `ResilientLlmClient`: `ResolvedEndpoint.provider`, `persistTrace()` во `withMdc`;
  `LlmResponse.storageKey` кэшируется через SemanticCache.
- `agent_logs.storage_key` (миграция `014`), все 6 агентов сохраняют `storageKey`.
- Конфиг `trace-storage.*` (env `MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET`),
  MinIO-сервис в `docker-compose.yml`.

### Phase 3 — Shadow Mode / Decision-level A/B ✅

- `ExperimentConfig` (`experiment.*`): `enabled`, `experimentId`, `variantPromptVersion`,
  `shadowExecution`, `rolloutPercent` (+ `inRollout(cycleId)`).
- `PaperTradingService`: на каждый цикл пишет две записи в `experiment_decisions`:
  - **CONTROL** — решение текущего пайплайна (исполняется, кроме полного shadow);
  - **VARIANT** — `is_paper=true`: либо повторный вызов Арбитра с промптом
    `variantPromptVersion` (реальное A/B, `bypassCache=true`), либо тень контроля
    (`shadow-copy`, без LLM).
- При `PositionClosedEvent` фиксируется исход обеих рук: CONTROL — фактический P&L,
  VARIANT — гипотетический (HOLD→0, противоположное направление→−P&L, то же→P&L×qty_ratio).
- `ArbitratorAgent.adjudicate(bypassCache=false)` — для A/B-вызова кэш обходится.
- Метрики `experiment.*` (logged/executed/shadowed/llm/outcome), API
  `GET /experiment/status`, `POST /experiment/enable`, `GET /experiment/decisions`.
- Управление через `BotSettings` (`experimentEnabled`, `experimentRolloutPercent`,
  `variantPromptVersion`) — миграция не требуется (JSON-блоб `bot_settings`), таблица
  `experiment_decisions` — миграция `015-experiment-decisions.sql`.

Следующий шаг: сравнение исходов рук уже визуализируется в Grafana — dashboard
«Trading Bot - A/B Experiment» (провижининг `grafana/provisioning/`, см. 09.2).
Промоушн вариантной руки — только после накопления статистики и решения по KPI.

### Phase 4 — RAG-анализ ошибок ✅

Анализ первопричины ошибок LLM-агентов по трейсам из S3/MinIO (см. раздел 9.5):

- `RagConfig` (`rag.*`, env `RAG_ENABLED` и др.): `enabled=false` по умолчанию,
  `corpusLimit=500`, `refreshIntervalMs=600000`, `maxResults=5`,
  `similarityThreshold=0.02`, `llmEnabled=true`.
- `TraceEmbedder` — локальный TF-IDF (токенизация EN+RU, стоп-слова, idf,
  косинус) — без внешнего vector DB и без затрат embedding API.
- `TraceCorpusIndex` — in-memory корпус с `@Volatile` снимком, параллельное
  чтение (Semaphore 8) на `Dispatchers.IO`; `search(query)` / `searchSimilar(trace)`.
- `TraceStorage.list()/read()` — чтение ключей по lastModified и объектов.
- `RagErrorAnalyzer` — переиндексация на `ApplicationReadyEvent` и по расписанию;
  `analyze(query,ticker,k)`, `analyzeTrace(storageKey,k)`, `status()`; LLM-разбор
  (prompt `rag-analyzer`, `temperature=0.2`) с rule-based fallback; метрики
  `rag.*`; API `GET /api/v1/rag/status`, `POST /api/v1/rag/refresh`,
  `POST /api/v1/rag/analyze`, `POST /api/v1/rag/analyze-trace`.
- Best-effort: сбой хранилища/LLM не влияет на торговлю.

Дальнейшие шаги (вне текущей задачи): асинхронный разбор каждого fallback-трейса,
`ticker`-фильтрация корпуса, пагинация корпуса при > 500 трейсов, алерт
`RagIndexEmpty` при пустом корпусе в течение N часов.

### Закрытие пробелов наблюдаемости (v2.2)

Остаточные пробелы, обнаруженные при аудите постановки задачи, закрыты:

| Пробел | Решение |
|---|---|
| `LlmTrace.errorMessage` не заполнялся | `LlmResponse.errorMessage` + передача причины/текста исключения в `persistTrace()` (fallback-трейсы теперь несут причину NO_API_KEY/CALL_ERROR + message) |
| Синхронный `putObject` на hot-path LLM-вызова | `AsyncTraceStorage` (`@Primary`): ограниченный FIFO-буфер, ключ возвращается сразу, фоновый консюмер пишет; при переполнении — синхронный fallback; метрики `trace.write.async`, `trace.buffer.size` |
| `trace_id` не доходил до исполнения ордеров | `TradingBotService` event-хендлеры ставят MDC из `strategy.cycleId`/`position.cycleId` (`onStrategyGenerated`, `onEntrySignal`, `onPriceChanged`, `pollMarketData`, WS-котировки) |
| Нет дешёвого доступа к трейсам | `TraceQueryService` + `GET /api/v1/traces` (по `key`/`cycleId`/recent) — без RAG/LLM |
| Хранение выключено по умолчанию, нет retention | `TRACE_STORAGE_ENABLED:-true` в `docker-compose.yml`; `trace-storage.retention-days` → S3 lifecycle expiration (idempotent) |

Новые конфиги (`trace-storage.*`): `async-buffer-size` (env `TRACE_ASYNC_BUFFER_SIZE`),
`retention-days` (env `TRACE_RETENTION_DAYS`). API: `GET /api/v1/traces`.
Тесты: `AsyncTraceStorageTest`, `TraceObjectKeyTest`, `TraceQueryServiceTest`, `LlmResponseTest`.
