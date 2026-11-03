# SIM-деплой в Yandex Cloud: runbook (2026-11-03)

**Статус:** подготовлено. Выполнение требует доступов к Yandex Cloud и GitHub
(пользователь).

**Цель:** 24/7 SIM-контур в Yandex Cloud (без LIVE-ордеров). LIVE — отдельный
gate после B и риск-ревью.

**Что уже готово в коде:** P0-1/P0-2/P0-3/P1-1…P1-6 (см. `docs/54`).

## 0. Архитектура

```
Internet (22/80/8080 только с admin IP; TLS — после P2-4)
        │
   ┌────▼──────────────────────────── VM (Ubuntu 24.04, 4 vCPU / 4 GB) ───┐
   │  docker compose:                                                      │
   │   app :8080 (healthcheck liveness)  ← Prometheus scraper              │
   │   prometheus :9090 (127.0.0.1)  →  alertmanager :9093 → alert-sink    │
   │   grafana :3000 (127.0.0.1)     minio :9000 (traces, off by default)  │
   └──────────────┬───────────────────────────────┬───────────────────────┘
                  │ VPC, security group           │
      ┌───────────▼──────────┐        ┌───────────▼──────────┐
      │ Managed PostgreSQL 16│        │ Managed Redis 7      │
      │ + timescaledb        │        │ (6379 внутри VPC)    │
      └──────────────────────┘        └──────────────────────┘
```

Локальные `postgres`/`redis` контейнеры в этом режиме удаляются
`docker-compose.managed.yml`; включается repo-переменной
`USE_MANAGED_DB=true`.

## 1. Размеры и стоимость (ориентир)

| Ресурс | Минимум | Рекомендуется |
|---|---|---|
| VM | 2 vCPU / 4 GB, 30 GB SSD | 4 vCPU / 4 GB, 40 GB SSD |
| Managed PostgreSQL | 2 vCPU / 4 GB, 50 GB | то же + HA не нужен для SIM |
| Managed Redis | 1–2 GB | 2 GB |

Ориентировочная стоимость SIM-контура: **10–15 тыс. ₽/мес** (VM ~3–4k,
Managed PG ~5–7k, Redis ~1.5–2.5k, диск/registry/IP ~1k). Тарифы меняются —
сверяйте калькулятор Yandex Cloud перед созданием.

Альтернатива «всё на одной VM 8 GB» дешевле, но противоречит аудиту
(Managed DB/Redis + лимиты памяти). На 4 GB VM без Managed DB не хватит
памяти.

## 2. Ресурсы Yandex Cloud

Все команды — с `yc` CLI, профиль с правами на каталог. Если флаг отличается
в вашей версии CLI — `yc <команда> --help`.

### 2.1 Каталог, сервисный аккаунт, registry

```bash
FOLDER_ID=<ваш folder id>
yc config set folder-id "$FOLDER_ID"

yc iam service-account create --name trading-bot-deploy
# push/pull образов из CI и с VM
yc resource-manager folder add-access-binding "$FOLDER_ID" \
  --role container-registry.images.pusher \
  --service-account-name trading-bot-deploy
# позже, для Lockbox (P0-3 уже поддерживается):
#   --role lockbox.payloadViewer
#   --role viewer (для диагностики)

yc iam key create --service-account-name trading-bot-deploy --output sa-key.json
yc container registry create --name trading-bot
yc container registry configure-docker
```

`sa-key.json` — содержимое GitHub-секрета `YC_SA_JSON` (не коммитить).

### 2.2 Сеть и security groups

```bash
yc vpc network create --name trading-bot-net
yc vpc subnet create --name trading-bot-subnet \
  --network-name trading-bot-net --range 10.10.0.0/24 --zone ru-central1-a

# SSH и веб — только с вашего IP (ADMIN_IP), не 0.0.0.0/0.
yc vpc security-group create --name trading-bot-vm-sg --network-name trading-bot-net \
  --rule "direction=ingress,port=22,protocol=tcp,v4-cidrs=[$ADMIN_IP/32]" \
  --rule "direction=ingress,port=80,protocol=tcp,v4-cidrs=[$ADMIN_IP/32]" \
  --rule "direction=ingress,port=8080,protocol=tcp,v4-cidrs=[$ADMIN_IP/32]" \
  --rule "direction=egress,port=any,protocol=any,v4-cidrs=[0.0.0.0/0]"

# PostgreSQL/Redis — только из VM-sg (по id).
yc vpc security-group create --name trading-bot-pg-sg --network-name trading-bot-net \
  --rule "direction=ingress,port=6432,protocol=tcp,security-group-id=<vm-sg-id>" \
  --rule "direction=egress,port=any,protocol=any,v4-cidrs=[0.0.0.0/0]"
yc vpc security-group create --name trading-bot-redis-sg --network-name trading-bot-net \
  --rule "direction=ingress,port=6379,protocol=tcp,security-group-id=<vm-sg-id>" \
  --rule "direction=egress,port=any,protocol=any,v4-cidrs=[0.0.0.0/0]"
```

### 2.3 VM

```bash
# ssh-key.pub — ваш публичный ключ, формат "user:<key>" (yc-user для Ubuntu).
yc compute instance create --name trading-bot-sim \
  --zone ru-central1-a \
  --network-interface subnet-name=trading-bot-subnet,nat-ip-version=ipv4,security-group-ids=<vm-sg-id> \
  --create-boot-disk image-folder-id=standard-images,image-family=ubuntu-2404-lts-amd64,size=40GB,type=network-ssd \
  --cores 4 --memory 4G --core-fraction 100 \
  --metadata-from-file ssh-keys=ssh-keys.txt \
  --service-account-name trading-bot-deploy
```

Публичный IP — в GitHub-секрет `VM_HOST`, пользователь (`yc-user`) — в
`VM_USER`.

### 2.4 Managed PostgreSQL

```bash
yc managed-postgresql cluster create trading-bot-pg \
  --environment production --network-name trading-bot-net --zone ru-central1-a \
  --resource-preset b2.medium --disk-size 50 --disk-type network-ssd \
  --postgresql-version 16 \
  --security-group-ids <pg-sg-id> \
  --backup-window-start 03:00 --backup-retain-period-days 7 \
  --deletion-protection

# Сначала пользователь, затем БД с owner=trader: Liquibase создаёт таблицы
# от имени владельца схемы.
yc managed-postgresql user create trader --cluster-name trading-bot-pg \
  --password '<DB_PASS>'
yc managed-postgresql database create trading-bot-pg \
  --db-name trading_bot --owner trader

# extension timescaledb (иначе миграции 012/037 создадут обычные таблицы):
yc managed-postgresql database update trading-bot-pg trading_bot \
  --extensions timescaledb,pgcrypto
```

Если `--extensions` недоступен в CLI — консоль: Cluster → Databases →
`trading_bot` → Extensions → включить `timescaledb`. Требуется
`shared_preload_libraries=timescaledb`; Yandex включает его при добавлении
расширения (hosts перезапускаются).

Важно: включайте расширение **до** первого деплоя. Если 012/037 уже
применены без него, Liquibase считает changeset выполненным; таблицы
останутся обычными, а конвертация в hypertable — ручная (см. комментарии в
`012-timescale-candles.sql`/`046-microstructure-table-fallback.sql`). Для SIM
это не блокер (работает обычный PostgreSQL), но теряются chunk/retention.

Хост и порт: **6432** (пулер) — в `DB_HOST`/`DB_PORT`, `DB_SSL_MODE=require`.

### 2.5 Managed Redis

```bash
# Пресет выберите по памяти (1–2 GB достаточно): yc managed-redis resource-preset list
yc managed-redis cluster create trading-bot-redis \
  --environment production --network-name trading-bot-net --zone ru-central1-a \
  --resource-preset <preset> --disk-size 20 --disk-type network-ssd \
  --redis-version 7.2 \
  --security-group-ids <redis-sg-id> \
  --deletion-protection
```

Хост — в `REDIS_HOST`, порт — `6379` (внутри VPC, без TLS) или `6380` +
`REDIS_SSL_ENABLED=true`.

## 3. Подготовка VM

```bash
# локально
scp -i <ssh-key> scripts/cloud/vm_bootstrap.sh yc-user@$VM_HOST:/tmp/
ssh -i <ssh-key> yc-user@$VM_HOST 'sudo bash /tmp/vm_bootstrap.sh'
```

Скрипт ставит Docker + compose plugin (>=2.24, нужен `!reset`), настраивает
ротацию логов, swap 2 GB и каталог `/opt/trading-bot` с владельцем `VM_USER`.

Проверка:

```bash
ssh -i <ssh-key> yc-user@$VM_HOST 'docker compose version && docker run --rm hello-world'
```

## 4. `/opt/trading-bot/.env` (создаётся вручную, chmod 600)

CI перезаписывает только `.env.deploy`; этот файл не трогается.

```dotenv
# обязательные
DB_PASS=<пароль trader>
JWT_SECRET=<случайная строка не короче 32 байт>
AUTH_USER=admin
AUTH_PASSWORD=<пароль>
ANALYTICS_USER=analytics
ANALYTICS_PASSWORD=<пароль>
METRICS_SCRAPE_TOKEN=<случайные 32+ символа>
GRAFANA_ADMIN_PASSWORD=<пароль>

# Managed PostgreSQL/Redis (docs/55)
DB_HOST=<pg-host>.mdb.yandexcloud.net
DB_PORT=6432
DB_NAME=trading_bot
DB_USER=trader
DB_SSL_MODE=require
REDIS_HOST=<redis-host>.mdb.yandexcloud.net
REDIS_PORT=6379
REDIS_SSL_ENABLED=false

# прочее
TRACE_STORAGE_ENABLED=false
JWT_COOKIE_SECURE=false   # true только после TLS/прокси (P2-4)
TZ=Europe/Moscow
```

`ALOR_TOKEN`, `ALOR_REFRESH_TOKEN`, `LLM_*`, `AUTH_*`, `ANALYTICS_*` CI
привозит в `.env.deploy` (он читается последним и перекрывает `.env`).
В `.env` они нужны только для ручного запуска compose без `.env.deploy`.

## 5. GitHub: секреты и переменные

Secrets (Settings → Secrets and variables → Actions):

| Секрет | Значение |
|---|---|
| `YC_REGISTRY_ID` | id registry (`yc container registry list`) |
| `YC_FOLDER_ID` | id каталога |
| `YC_SA_JSON` | содержимое `sa-key.json` |
| `VM_HOST` | публичный IP VM |
| `VM_USER` | `yc-user` |
| `VM_SSH_KEY` | приватный SSH-ключ (тот же, что в `ssh-keys.txt`) |
| `ALOR_TOKEN`, `ALOR_REFRESH_TOKEN` | токены Alor (refresh — рабочий!) |
| `LLM_PROVIDER`, `LLM_API_KEY` | LLM (можно пусто, LLM off) |
| `AUTH_USER`, `AUTH_PASSWORD` | admin UI/API |
| `ANALYTICS_USER`, `ANALYTICS_PASSWORD` | read-only пользователь |

Repository variable:

| Variable | Значение |
|---|---|
| `USE_MANAGED_DB` | `true` (Managed PG/Redis) |

Environment `live` с required reviewers — только перед LIVE, сейчас не нужен.

## 6. Первый деплой

1. Push в `main` (или `gh workflow run CI`).
2. `deploy-sim` собирает образы с `GIT_COMMIT=$GITHUB_SHA`, копирует compose
   и конфиги на VM, делает pre-migration backup (с Managed PG скрипт
   пропускается — бэкапы делает облако), поднимает стек с `--wait`.
3. Проверка на VM:

```bash
cd /opt/trading-bot
COMPOSE="docker compose --env-file .env --env-file .env.deploy \
  -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.managed.yml"
$COMPOSE ps
docker exec trading-bot-app printenv GIT_COMMIT   # == github.sha
$COMPOSE logs --tail=100 app
```

4. Smoke с локальной машины:

```powershell
pwsh -File scripts/cloud/smoke_sim.ps1 `
  -BaseUrl http://<VM_HOST>:8080 -AuthUser admin -Password '<AUTH_PASSWORD>'
```

Ожидаемо: liveness/readiness/health 200, login, `trading/status`,
`dashboard` — 200.

## 7. Что проверить после первого запуска

| Проверка | Как | Ожидание |
|---|---|---|
| Build identity | `docker exec trading-bot-app printenv GIT_COMMIT` | полный SHA коммита |
| Liquibase | `$COMPOSE logs app \| grep -i liquibase` | миграции применены, ошибок нет |
| TimescaleDB | `$COMPOSE logs app \| grep -i timescale` | hypertable создан (или warning, если extension не включён) |
| CBR | `GET /api/v1/macro/rates-history?days=1` (admin-JWT) | `loaded>0`, `keyRates>0` |
| Funding | `/api/v1/backtest/CNYRUBF/funding-history/status` (admin-JWT) | ряд есть |
| Prometheus | `ssh -L 9090:localhost:9090 ...` → `http://localhost:9090/targets` | target `trading-bot` UP |
| Alertmanager | `ssh -L 9093:localhost:9093 ...` | UI открывается, receiver default |
| Grafana | `ssh -L 3000:localhost:3000 ...` | дашборды загружены |
| Память | `docker stats --no-stream` | в пределах mem_limit |
| SIM-режим | `GET /api/v1/trading/status` | `mode=SIMULATION`, торговля включена по расписанию |

## 8. Эксплуатация

```bash
cd /opt/trading-bot
COMPOSE="docker compose --env-file .env --env-file .env.deploy \
  -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.managed.yml"

$COMPOSE logs -f --tail=200 app      # логи
$COMPOSE restart app                 # рестарт без пересборки
$COMPOSE up -d --wait                # применить изменения compose
```

**Rollback:** `deploy-sim` всегда пушит `:latest` и `:<sha>`. Надёжный
способ — re-run workflow на старом коммите (перезапишет `.env.deploy` и
поднимет тот же SHA). Быстрый локальный откат — на VM прописать в
`.env.deploy` `TAG=<предыдущий sha>` и `$COMPOSE up -d --wait` (до
следующего деплоя).

**Бэкапы:** Managed PostgreSQL — автоматические (7 дней по умолчанию).
Локальный `db_backup.sh` — только для локального postgres; с Managed PG он
корректно пропускается.

**Переключение с локальной БД на Managed:** первый деплой с
`USE_MANAGED_DB=true` запускается с `--remove-orphans` и удаляет старые
контейнеры `postgres`/`redis`. Данные локальной БД не переносятся
автоматически: для SIM торговли приложение само догрузит свечи с MOEX, но
историю для backtest/research нужно перенести (`pg_dump`/`pg_restore`) или
загрузить заново (`scripts/load_candles_deep.ps1`).

**Диск:** `docker system df`; старые образы чистит CI (`image prune -f`),
логи ограничены `max-size`.

## 9. B (forward paper) — отдельный контур

`scripts/paper_momentum_daily.ps1` использует **локальный** контейнер
`trading-bot-db` (`docker exec`) и Windows Task Scheduler. На Linux-VM с
Managed PG он не заработает без адаптации (нужен доступ к БД по сети).

Решение по умолчанию: **B остаётся на текущей машине**, cloud-деплой его не
затрагивает. Перенос B на VM — отдельная задача (PowerShell Core + сетевой
psql/R2DBC + state в volume).

## 10. Чего не делать

- Не открывать `8080` в интернет без TLS/прокси и белого списка (P2-4 ещё не
  закрыт).
- Не включать `TRADING_MODE=LIVE`: LIVE запрещён до B (F1–F5),
  deployment-gate и риск-ревью.
- Не коммитить `.env`, `sa-key.json`, `prometheus-token`.
- Не менять live-параметры: `maxC=1`, Kelly, `LIVE_TICKERS_ALLOWLIST=CNYRUBF`.
- Не запускать на 4 GB VM локальные postgres+redis вместе с app: памяти не
  хватит (используйте Managed или 8 GB).

## 11. Чек-лист

- [ ] Каталог, SA `trading-bot-deploy`, `sa-key.json`
- [ ] Registry `trading-bot`
- [ ] Сеть/подсеть/security groups (22/80/8080 только с admin IP)
- [ ] VM 4/4, Ubuntu 24.04, Docker + compose >=2.24
- [ ] Managed PostgreSQL 16, БД `trading_bot`, пользователь `trader`,
      extension `timescaledb`, порт 6432
- [ ] Managed Redis 7
- [ ] `/opt/trading-bot/.env` (chmod 600) с DB/Redis/JWT/metrics
- [ ] GitHub secrets + `USE_MANAGED_DB=true`
- [ ] `deploy-sim` зелёный, `$COMPOSE ps` — все healthy
- [ ] `smoke_sim.ps1` — SMOKE OK
- [ ] Prometheus target UP, Alertmanager UI доступен
- [ ] `GIT_COMMIT` в контейнере == коммит
- [ ] B продолжает работать локально (0 сделок / cron 23:55)
