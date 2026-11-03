# Аудит перед Yandex Cloud: пакет 1.1 + 1.2 (2026-11-03)

**Источник:** сессия `ses_e64686768ffejWU4rRI2KbWrCk` («Аудит торгового робота
на Kotlin»), ревизия `196afd3`. Код с тех пор не менялся (HEAD `6e830f1`
отличается только `artifacts/paper_momentum/*`), поэтому выводы аудита
актуальны.

**Вердикт аудита:** SIM/Paper — `CONDITIONAL GO` после P0-1; LIVE — `NO-GO`
(P0-2, P0-3, funding URL). Торговое ядро (exactly-once вход, outbox,
закрытие позиций, LIVE-guard, МСК) проверено и признано надёжным.

## 1. Что вошло в пакет 1.1 + 1.2

| Пункт | Статус | Что сделано |
|---|---|---|
| **P0-1** healthcheck/503 | **исправлено** | liveness/readiness probes, Docker healthcheck на `/actuator/health/liveness`, бизнес-семантика `TradingHealthIndicator`, публичный доступ к probes |
| **P1-1** CBR TLS + funding URL | **исправлено** | HARICA-сертификаты в образе, `-Djavax.net.ssl.trustStore`, дефолтные FORTS-URL в `FundingConfig` |
| **P1-2** WS watchdog/reconnect | **исправлено** | активность только по входящим кадрам (данные/PONG); клиент закрывает Netty-сессию по `HEARTBEAT_TIMEOUT` → reconnect |
| **P1-3** ночной цикл закрытий | **не воспроизводится** | в текущем коде нет `OUTSIDE_TRADING_HOURS`-закрытия в мониторах; `TradingHoursGuard` используется только в `TradingGate` для входа; force-close — только `TradingControlService` при `forceCloseEnabled && forceCloseTime` |
| **P1-4** метрики/алерты | **исправлено** | имена метрик синхронизированы, `TradingPaused` через `rate`, Alertmanager + webhook-sink, promtool-тесты, CI job |
| **P1-5** blocking DB/пулы | **исправлено частично** | HTTP-путь переведён на suspend (`findRecentAwait`), блокирующие event-path вызовы ограничены 5 с, R2DBC/Hikari лимиты; полный suspend-рефактор `updateDailyPnl` — отдельная задача |
| **P1-6** память/Managed DB | **исправлено** | `MaxRAMPercentage=50`, убран `AlwaysPreTouch`, `mem_limit` всем сервисам, `DB_HOST`/`REDIS_HOST` из env, `TRACE_STORAGE_ENABLED=false` |

## 2. P0-1: health/liveness

### Изменения

- `application.yml`:
  - `management.endpoint.health.probes.enabled=true`;
  - группа `liveness` = `livenessState,ping`;
  - группа `readiness` = `readinessState`;
  - `management.health.rabbit.enabled=${OUTBOX_RABBITMQ_ENABLED:false}` —
    опциональный Rabbit outbox больше не роняет `/actuator/health`.
- `SecurityConfig`: `/actuator/health/liveness` и `/actuator/health/readiness`
  в `permitAll` (Docker healthcheck ходит без JWT).
- `docker-compose.yml`: healthcheck `app` → `/actuator/health/liveness`.
- `TradingHealthIndicator`:
  - SIMULATION: `UP` даже при отключённом WS и вне торговых часов;
  - LIVE: `DOWN` только при недоступном Redis или отсутствии WS quotes
    **в торговые часы**; вне часов — `UP` с `trading_hours=CLOSED`;
  - Redis-проверка ограничена `withTimeoutOrNull(1_000)`;
  - добавлен `Clock` bean (МСК) для тестируемости.
- Новый integration-тест: probes публичны и отдают 200 без JWT.

### Проверка

- `/actuator/health/liveness` → **200**;
- `/actuator/health/readiness` → **200**;
- `/actuator/health` → **200 UP** в SIMULATION вне торговых часов
  (07:55 МСК), без ложного `DOWN` из-за RabbitMQ;
- `TradingHealthIndicatorTest` — 7 кейсов (SIM/LIVE, часы, Redis, WS).

## 3. P1-1: CBR TLS и funding

### Изменения

- `docker/certs/` — публичные `HARICA TLS RSA Root CA 2021` и
  `HARICA DV TLS RSA`;
- `Dockerfile`: в builder импортирует оба сертификата в копию `cacerts`,
  runtime получает `/app/cacerts` и JVM-флаги
  `-Djavax.net.ssl.trustStore=/app/cacerts`;
- `FundingConfig`: дефолтные URL FORTS
  (`.../engines/futures/markets/forts/securities/{ticker}.json`) для
  live-снапшота и history; env `FUNDING_MOEX_URL` /
  `FUNDING_MOEX_HISTORY_URL` перекрывают через relaxed binding;
- `application.yml` больше не задаёт пустые `funding.moex-url` /
  `moex-history-url`, которые затирали дефолты;
- `.env.example` и комментарий в `docker-compose.yml` документируют
  переопределение.

### Проверка

- `docker build` — успешно, `keytool` импортировал оба сертификата;
- MOEX ISS FORTS endpoints отдают `SWAPRATE` (проверено вживую для
  `CNYRUBF`: live и history);
- `FundingConfig` defaults используются, если env не задан.

## 4. P1-2: WS watchdog и принудительный reconnect

### Изменения

- `AlorWebSocketClient.startHeartbeat` больше **не** вызывает
  `webSocketManager.onActivity(stream)` после успешной отправки ping:
  успешная запись в локальный сокет не доказывает, что соединение живо.
  Активность фиксируется только на входящих кадрах (`session.receive()` —
  данные/PONG), которые уже вызывают `onActivity` в обоих flow.
- `AlorWebSocketClient` подписан на `webSocketManager.events`: при
  `HEARTBEAT_TIMEOUT` для своего потока закрывает активную Netty-сессию.
  `execute()` завершается → срабатывает `scheduleReconnect`, а не ожидание
  TCP-таймаута ОС (до 15 минут).
- Watcher стартует с `CoroutineStart.UNDISPATCHED` **до** `connect(0)`,
  поэтому replay-событие прошлой сессии видит `active == null` и не закрывает
  новую сессию. Активная сессия хранится в `AtomicReference` (корректная
  видимость между потоками).
- Новый счётчик `alor.ws.heartbeat_timeout_close{stream}` — закрытие сессии
  именно по watchdog-таймауту.

### Проверка

- `heartbeat ping success does not count as inbound activity` — успешная
  отправка ping не вызывает `onActivity` (регресс на исходный дефект).
- `heartbeat timeout event closes the active session and triggers reconnect` —
  событие `HEARTBEAT_TIMEOUT` закрывает сессию, растёт
  `alor.ws.quotes.reconnect` и `alor.ws.heartbeat_timeout_close`.
- `replayed heartbeat timeout does not close a fresh session` — replay
  прошлого события не убивает новое соединение.

## 5. P1-3: почему не меняли код

В текущем `196afd3`/`6e830f1`:

- `FuturesPositionMonitor` и `StockPositionMonitor` **не** вызывают
  `TradingHoursGuard` и не закрывают позиции по `OUTSIDE_TRADING_HOURS`;
- `TradingHoursGuard` используется только в `TradingGate.globalBlocks()` —
  это блокировка **входов**, не выходов;
- принудительное закрытие выполняет только
  `TradingControlService.scheduledForceClose()`, и оно выходит сразу, если
  `forceCloseEnabled=false` или `forceCloseTime` пустой;
- это уже покрыто тестами:
  `TradingGateTest.outside trading hours blocks globally` и
  `TradingControlServiceTest.scheduled close does nothing when disabled or
  time is blank`.

Поэтому P1-3 из аудита — **ложное срабатывание** (описанного пути в коде
нет). Добавлять защиту от несуществующего цикла не стали; при следующем
аудите этот пункт нужно проверять по фактическому коду, а не по отчёту.

## 6. P1-5/P1-6: пулы БД и память

### P1-5 — блокирующие вызовы и лимиты

- `DailyRiskSnapshotRepository`: добавлены `findRecentAwait` и
  `deleteAllAwait`; `ApiController.getDailyPnlHistory` и
  `TradingAccountController.dailyPnlHistory` используют suspend-версию без
  `withContext(Dispatchers.IO)`.
- Оставшиеся блокирующие `findByDate`/`findRecent`/`upsert`/`deleteAll`
  (event/scheduler-пути `DrawdownProtectionService`, тесты) получили
  **`block(5s)`** вместо бессрочного `.block()`: при исчерпании пула вызов
  падает по таймауту, а не вешает поток навсегда.
- `ApiController.requireAccountBlocking` (SSE-эндпоинт, не может быть suspend)
  ограничен `withTimeoutOrNull(2s)`.
- R2DBC pool: `max-acquire-time=5s`, `max-idle-time=10m`,
  `max-life-time=30m`, `validation-query=SELECT 1`.
- Liquibase JDBC/Hikari: `maximum-pool-size=2`, `minimum-idle=0`,
  `idle-timeout=30s`, `max-lifetime=30m`, `connection-timeout=5s` — на
  Managed PostgreSQL не держим 10 лишних коннектов.
- **Остаток:** `DrawdownProtectionService.updateDailyPnl` остаётся
  не-suspend (вызывается из `DailyLossCircuitBreaker`/`RiskManagementService`);
  его запись ограничена 5 с, но полный перевод на `suspend` —
  отдельный рефакторинг с проверкой порядка halt/закрытия.

### P1-6 — память и инфраструктура

- `Dockerfile`: `-XX:MaxRAMPercentage=50.0`, убран `-XX:+AlwaysPreTouch`
  (на 4 ГБ VM pre-touch физически занимал ~3 ГБ heap до старта).
- `docker-compose.yml`: явные `mem_limit` — app 1536m, postgres 768m,
  redis 128m, minio 64m, rabbitmq 128m, prometheus 256m, alertmanager 64m,
  alert-sink 16m, grafana 192m, frontend 32m (сумма ≈3,1 ГБ, остаётся запас ОС).
- `DB_HOST`/`DB_PORT`/`REDIS_HOST`/`REDIS_PORT` берутся из env с local-дефолтами
  (`${DB_HOST:-postgres}` и т.д.) — можно подключить Managed PostgreSQL/Redis.
- `TRACE_STORAGE_ENABLED` по умолчанию **false** (MinIO-трейсы не пишутся без
  явного включения).
- `.env.example` документирует лимиты и внешние хосты.
- **Оговорка:** при `APP_MEM_LIMIT=1536m` JVM heap ≈768 МБ; для тяжёлых
  backtest/WFA на той же VM этого мало. Research-прогоны лучше выносить на
  отдельную VM/профиль, а не поднимать лимит на 4 ГБ инстансе.

## 7. P1-4: метрики и доставка алертов

### Исправления правил

- `DailyLossLimitReached`: `drawdown_daily_limit_reached` (не существует) →
  `risk_daily_limit_reached == 1 or drawdown_blocked == 1`.
- `TradingPaused`: `strategy_pause_total > 0` (Counter горел бы вечно) →
  `rate(strategy_pause_total[10m]) > 0`.
- `ConsecutiveLosses` использует корректное имя
  `drawdown_consecutive_losses` (gauge появляется после первого
  `computeStatus`; до этого алерт просто неактивен).

### Доставка

- Добавлен `alertmanager` (`prom/alertmanager:v0.27.0`) с
  `alertmanager.yml`: route/grouping, webhook receiver.
- Добавлен `alert-sink` (`hashicorp/http-echo`) — рабочий приёмник webhook
  для проверки доставки; в production URL заменяется на Telegram/webhook-реле.
- `prometheus.yml`: `alerting.alertmanagers` → `alertmanager:9093`.
- Compose: сервисы `alertmanager` (64m) и `alert-sink` (32m), порт
  Alertmanager только на `127.0.0.1:9093`.

### Тесты

- `prometheus-alerting-rules-test.yml`: `DailyLossLimitReached` срабатывает
  на `risk_daily_limit_reached=1`; `TradingPaused` срабатывает на новый
  `strategy_pause_total` и **не** горит вечно после прекращения пауз.
- CI job `alerting`: `promtool check rules`, `promtool test rules`,
  `amtool check-config` (Docker-образы тех же версий, что в compose).
- Локально: `scripts/check_alerting.ps1`.

## 8. Что осталось из аудита

| Пункт | Приоритет | Когда |
|---|---|---|
| Полный suspend-рефактор `DrawdownProtectionService.updateDailyPnl` | средний | после SIM-деплоя |
| P0-2 `GIT_COMMIT` в образе | блокер LIVE | перед LIVE |
| P0-3 Lockbox IAM (`kid`/PS256) | блокер LIVE | перед LIVE |
| P2-1…P2-5 | security/гигиена | перед публичным деплоем |

**LIVE по-прежнему NO-GO:** не включать `TRADING_MODE=LIVE` до закрытия
P0-2/P0-3 и прохождения B (forward paper) + deployment-gate + риск-ревью.
Live-параметры не меняются: `maxC=1`, Kelly, `CNYRUBF`.

## 9. Верификация

- `./gradlew test integrationTest ktlintCheck --rerun-tasks`:
  **BUILD SUCCESSFUL in 22m 6s**; unit **3 391** (0 падений, 1 skipped),
  integration **225** (0 падений, 1 skipped), ktlint — чисто.
- `docker build` — успешно; оба HARICA-сертификата импортированы в
  `/app/cacerts`.
- Контейнер на `trading-bot_default` (SIMULATION):
  `/actuator/health/liveness` → **200**,
  `/actuator/health/readiness` → **200**,
  `/actuator/health` → **200 UP**.
- Из контейнера `GET /api/v1/macro/rates-history?days=1` → `loaded=2`,
  `keyRates=2` — TLS-цепочка ЦБ работает через образ (P1-1 подтверждён
  end-to-end).
- `docker compose -f docker-compose.yml -f docker-compose.prod.yml config`
  — exit 0; healthcheck указывает на `/actuator/health/liveness`;
  `alertmanager`/`alert-sink` присутствуют в конфиге.
- `promtool check rules` — **SUCCESS: 17 rules found**;
  `promtool test rules` — **SUCCESS** (включая отсутствие вечного
  `TradingPaused`); `amtool check-config` — **SUCCESS** (1 receiver).
- Alertmanager + alert-sink подняты в compose; тестовый alert принят
  (`amtool alert query` → active), webhook-sink доступен на
  `http://alert-sink:8080/alerts`.
- `FundingConfig` defaults: live/history FORTS-URL; env override через
  relaxed binding (`FUNDING_MOEX_URL` / `FUNDING_MOEX_HISTORY_URL`).
- Тесты funding обновлены: «blank URL отключает источник» теперь явно
  задаёт `null`, а не полагается на старый пустой default.
