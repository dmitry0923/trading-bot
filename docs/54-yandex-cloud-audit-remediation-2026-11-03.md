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
| **P1-3** ночной цикл закрытий | **не воспроизводится** | в текущем коде нет `OUTSIDE_TRADING_HOURS`-закрытия в мониторах; `TradingHoursGuard` используется только в `TradingGate` для входа; force-close — только `TradingControlService` при `forceCloseEnabled && forceCloseTime` |

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

## 4. P1-3: почему не меняли код

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

## 5. Что осталось из аудита

| Пункт | Приоритет | Когда |
|---|---|---|
| P1-2 WS watchdog/реконнект | высокий | следующий пакет (24/7 stability) |
| P1-4 имена метрик/алерты/Alertmanager | высокий | следующий пакет |
| P1-5 `.block()`/пулы БД | средний | следующий пакет |
| P1-6 память 4 ГБ/Managed DB | высокий для 4 ГБ VM | перед SIM-деплоем |
| P0-2 `GIT_COMMIT` в образе | блокер LIVE | перед LIVE |
| P0-3 Lockbox IAM (`kid`/PS256) | блокер LIVE | перед LIVE |
| P2-1…P2-5 | security/гигиена | перед публичным деплоем |

**LIVE по-прежнему NO-GO:** не включать `TRADING_MODE=LIVE` до закрытия
P0-2/P0-3 и прохождения B (forward paper) + deployment-gate + риск-ревью.
Live-параметры не меняются: `maxC=1`, Kelly, `CNYRUBF`.

## 6. Верификация

- `./gradlew test integrationTest ktlintCheck --rerun-tasks`:
  **BUILD SUCCESSFUL in 22m 41s**; unit **3 389** (0 падений, 1 skipped),
  integration **224** (0 падений, 1 skipped), ktlint — чисто.
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
  — exit 0; healthcheck указывает на `/actuator/health/liveness`.
- `FundingConfig` defaults: live/history FORTS-URL; env override через
  relaxed binding (`FUNDING_MOEX_URL` / `FUNDING_MOEX_HISTORY_URL`).
- Тесты funding обновлены: «blank URL отключает источник» теперь явно
  задаёт `null`, а не полагается на старый пустой default.
