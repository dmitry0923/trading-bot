# SIM-деплой в Yandex Cloud: ревью готовности (2026-11-03)

**Объект:** репозиторий `trading-bot` (HEAD `317dada` + правки ревью),
runbook `docs/55-sim-deploy-yandex-cloud.md`.

**Метод:** статическая проверка кода/CI/compose/конфигов + локальные прогоны
(`docker compose config`, `promtool`, `amtool`, `actionlint`, `shellcheck`,
`smoke_sim.ps1`, полный `test + integrationTest + ktlintCheck`, Docker-smoke).
Ресурсы Yandex Cloud не создавались — это действия пользователя.

**Вердикт: `CONDITIONAL GO` для SIM.**
Со стороны кода/конфигов блокеров нет. Осталось внешнее: создать ресурсы,
заполнить GitHub secrets и `/opt/trading-bot/.env`, включить `timescaledb`
до первого деплоя.

## 1. Чек-лист готовности

| # | Пункт | Статус | Комментарий |
|---|---|---|---|
| 1 | Каталог, SA `trading-bot-deploy`, `sa-key.json` | **user action** | команды в docs/55 §2.1 |
| 2 | Registry `trading-bot` | **user action** | §2.1 |
| 3 | Сеть/подсеть/security groups | **user action** | §2.2; 22/80/443, 8080 наружу не публикуется |
| 4 | VM 4/4, Ubuntu 24.04 | **user action** | §2.3 |
| 5 | Docker + compose plugin >=2.24 | **ready** | `vm_bootstrap.sh` ставит Docker из official repo и **проверяет версию compose** (новое) |
| 6 | Managed PostgreSQL 16 + `timescaledb` + порт 6432 | **user action** | §2.4; extension включать ДО первого деплоя |
| 7 | Managed Redis 7 | **user action** | §2.5 |
| 8 | `/opt/trading-bot/.env` | **ready template** | §4; обязательные ключи перечислены |
| 9 | GitHub secrets + `USE_MANAGED_DB` / `USE_TLS` | **ready wiring** | §5; CI использует все перечисленные секреты |
| 10 | `deploy-sim` собирает/пушит/деплоит | **ready** | GIT_COMMIT, registry login, scp, `up --wait` |
| 11 | Healthcheck приложения | **ready** | liveness/readiness, `start_period: 60s`, wait 240s |
| 12 | Prometheus/Alertmanager/Grafana | **ready** | конфиги валидированы, файлы копируются на VM |
| 13 | Smoke-проверка | **ready** | `scripts/cloud/smoke_sim.ps1`, 6/6 локально |
| 14 | B (forward paper) | **ready / отдельно** | остаётся на текущей машине (локальный `trading-bot-db`) |

## 2. Найденные и исправленные пробелы

1. **`WS_QUOTES_ENABLED` не пробрасывался в контейнер.** `.env` не влиял на
   `trading.ws-quotes-enabled` (compose передавал только `ALOR_WS_ORDERS_ENABLED`).
   Добавлено `WS_QUOTES_ENABLED: ${WS_QUOTES_ENABLED:-true}` в app environment,
   `.env.example` и шаблон docs/55. Для SIM без ALOR-токена можно поставить
   `false` (REST-fallback), чтобы не было reconnect-шума.
2. **`alerting` не гейтил деплой.** `deploy-sim`/`deploy-live` зависели только
   от `backend`/`frontend`; сломанные правила Prometheus могли уехать на VM.
   `needs` расширен до `[backend, frontend, alerting]`.
3. **Bootstrap не проверял версию compose.** На уже подготовленной VM со старым
   плагином `!reset` упал бы только на деплое. Теперь `vm_bootstrap.sh`
   требует compose >= 2.24 и падает с понятным сообщением.

Ранее (в этом же цикле) исправлено: `alertmanager.yml` не копировался на VM —
без него Alertmanager не стартовал бы; `app:8080` не публикуется наружу.

## 3. Риски и ограничения (не блокеры SIM)

| Риск | Митигация |
|---|---|
| `timescaledb` не включён до первого деплоя | миграции 012/046 оставят обычные таблицы (работает, но без chunk/retention); включать в консоли/CLI до деплоя, см. docs/55 §2.4 |
| DNS/TLS не настроены | первый запуск по `http://VM_IP` с admin IP; TLS — `USE_TLS=true` после A-записи, Let's Encrypt требует 80/443 из интернета |
| `AUTH_*` приходят из `.env.deploy` (CI), `DB_PASS`/`JWT_SECRET`/`METRICS_SCRAPE_TOKEN`/`GRAFANA_ADMIN_PASSWORD` — только из `.env` | шаблон docs/55 §4; CI не перезаписывает `.env` |
| `prometheus-token` генерируется из `.env` | при отсутствии `METRICS_SCRAPE_TOKEN` CI падает до деплоя (понятная ошибка) |
| Managed PG: SSL | `DB_SSL_MODE=require` (проброшен в app), порт 6432 |
| Managed Redis: TLS | 6379 внутри VPC; при 6380 — `REDIS_SSL_ENABLED=true` |
| Alertmanager шлёт в `alert-sink` (заглушка) | для реальных уведомлений заменить webhook URL в `alertmanager.yml` |
| `db_backup.sh` с Managed PG | корректно пропускается (нет локального контейнера `postgres`), бэкапы — автоматические в облаке |
| 4 GB VM | `mem_limit` суммарно ≈3,1 ГБ + swap 2 ГБ; heap 768 МБ; backtest/WFA на этой VM не запускать |
| B на VM с Managed PG | не заработает без адаптации (`docker exec trading-bot-db`); B остаётся локально |

## 4. Верификация (локально)

| Проверка | Результат |
|---|---|
| `test + integrationTest + ktlintCheck` | **BUILD SUCCESSFUL in 29m 17s**, unit **3 400**, integration **225**, 0 падений |
| `docker compose config` (local / +managed / +tls) | exit 0; managed убирает postgres/redis; TLS добавляет Caddy, снимает ports frontend |
| `promtool check rules` / `test rules` | 17 rules SUCCESS / SUCCESS |
| `amtool check-config` | SUCCESS |
| `actionlint` | новых замечаний нет (pre-existing SC2086, `secrets` в `environment.url`) |
| `shellcheck vm_bootstrap.sh` | чисто |
| Docker-образ | `user=app`, liveness/readiness/health 200, smoke **6/6** |
| `GIT_COMMIT` | baked в image ENV + label `org.opencontainers.image.revision` |

## 5. Порядок действий пользователя

1. Создать ресурсы по docs/55 §2 (SA, registry, сеть, VM, Managed PG/Redis).
2. Включить `timescaledb` в Managed PostgreSQL **до** первого деплоя.
3. Прогнать `scripts/cloud/vm_bootstrap.sh` на VM.
4. Создать `/opt/trading-bot/.env` (chmod 600) по шаблону §4.
5. Заполнить GitHub secrets и переменные `USE_MANAGED_DB=true`
   (позже `USE_TLS=true` после DNS).
6. Push в `main` → дождаться `backend/frontend/alerting/deploy-sim`.
7. Проверить `$COMPOSE ps`, `GIT_COMMIT`, логи Liquibase, Prometheus targets.
8. Запустить `scripts/cloud/smoke_sim.ps1` — ожидается `SMOKE OK`.
9. Наблюдать 1–2 недели: `docker stats`, алерты, `order_outbox` (не должен
   расти), `funding_unknown`, CBR `status=OK`.

**LIVE остаётся NO-GO:** до B (F1–F5), deployment-gate и отдельного
риск-ревью. Параметры live не менялись: `maxC=1`, Kelly, `CNYRUBF`.
