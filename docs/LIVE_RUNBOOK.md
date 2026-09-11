# LIVE-RUNBOOK (деплой бота в реальном режиме)

> Жёсткий регламент для запуска в LIVE. Прочитай сплошь до конца перед первым
> запуском. Каждый шаг имеет критерий «можно/нельзя продолжать». Версия фактов —
> 2026-09-11, при изменениях конфигов сверяй с `ci.yml`, `application.yml`, `docs/15`, `docs/16`.

## 0. Что такое LIVE в этом проекте (тер-минимум)

- `TRADING_MODE=LIVE` — бот отправляет **реальные ордера** на MOEX через Alor.
  `SIMULATION` — ордера виртуальные (деньги не тратятся).
- **LIVE-guard — fail-closed**: при `TRADING_MODE=LIVE` вход в позицию разрешён
  ТОЛЬКО тикерам из `LIVE_TICKERS_ALLOWLIST`. Сейчас он = `CNYRUBF` (один фьючерс).
  Пустой список = заблокированы ВСЕ входы (это защита, а не баг).
- `max-contracts-per-position = 1`, `futures-max-open-positions = 1`, маржа для
  CNYRUBF 1 000–2 700 ₽ (side-specific, не spec.go 850 ₽).
- Единственный механизм деплоя в LIVE — GitHub Actions job `deploy-live` вручную,
  через GitHub Environment `live` (required reviewers). Автодеплой всегда SIMULATION.

---

## 1. Предварительные условия (выполнить один раз)

### 1.1. GitHub Secrets (Settings → Secrets and variables → Actions)

| Секрет | Откуда взять | Зачем |
|---|---|---|
| `YC_REGISTRY_ID` | Yandex Cloud → Container Registry → ID репозитория | префикс `cr.yandex/<id>` |
| `YC_SA_JSON` | Yandex Cloud service-account key (JSON) | аутентификация `yc` на runner |
| `YC_FOLDER_ID` | Yandex Cloud folder | `yc config set folder-id` |
| `VM_HOST` | IP публичной VM | SCP/SSH-деплой |
| `VM_USER` | пользователь на VM (напр. `ubuntu`) | SSH |
| `VM_SSH_KEY` | приватный ключ SSH доступа на VM (`ssh-ed25519 ...`) | SSH-аутентификация |
| `ALOR_TOKEN` | Личный кабинет Alor (`https://my.alor.ru`) | доступ к торговле |
| `ALOR_REFRESH_TOKEN` | Там же | обновление токена |
| `AUTH_USER` / `AUTH_PASSWORD` | твой выбор | basic-auth фронта |
| `ANALYTICS_USER` / `ANALYTICS_PASSWORD` | отдельный логин | доступ к `/api/v1/analytics/*` |
| `LLM_PROVIDER` / `LLM_API_KEY` | провайдер RouterAI/Moonshot/DeepSeek/Qwen | LLM-агенты; **пустой = детерминированные fallback (безопасно)** |
| `NEWS_ENABLED` / `NEWS_BASE_URL` / `NEWS_API_KEY` | платная подписка rg.ru | новости эмитентов (Agent-2); пустые = без новостей |

### 1.2. GitHub Environment `live` (Settings → Environments → **Create environment: live**)

- **Required reviewers**: добавить себя (или отдельного аккаунта для второго мнения).
  `deploy-live` не стартует, пока эти ревьюеры не одобрят.
- В Environment-секреты продублировать НЕ нужно — job читает обычные secrets,
  Environment нужен только ради required reviewers.

### 1.3. Проверка VM (один раз)

```bash
ssh <VM_USER>@<VM_HOST>
docker --version        # ожидаем Docker ≥ 24 / docker compose v2
docker compose version
df -h                  # свободно ≥ 5 ГБ
```

### 1.4. Локальная проверка билда (обязательна перед деплоем)

```bash
.\gradlew.bat test ktlintCheck      # юнит + стиль
.\gradlew.bat integrationTest       # Testcontainers: TimescaleDB/Redis/RabbitMQ
```

Все три — зелёные. Это критерий «можно продолжать».

---

## 2. Деплой SIM (этап-прикрытие, автоматический)

При push в `main` CI сам соберёт и развернёт **SIMULATION** сборку на VM
(job `deploy-sim`). Это обязательный шаг даже для LIVE: `deploy-live` использует
образы с тем же SHA, что собраны в `deploy-sim` (`Verify LIVE images exist`).

1. Смержить рабочую ветку в `main`:
   ```bash
   git checkout main && git pull
   git merge research/<ветка>
   git push origin main
   ```
2. В Actions дождаться статуса:
   - `Backend (Gradle...)` — PASSED
   - `Frontend` — PASSED
   - `Deploy SIM to Yandex Cloud` — SUCCESS (только на push в `main`)
3. **Проверить SIM на VM** (обязательно, прежде чем думать о LIVE):
   ```bash
   ssh <VM_USER>@<VM_HOST>
   docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
   docker compose -f docker-compose.yml -f docker-compose.prod.yml logs --tail=200 app
   ```
   Критерий «можно продолжать»:
   - app поднялся, в логах нет `FATAL`/непрерывных `ERROR`/`WARN: entry.rejected`;
   - `/actuator/health` на VM отвечает `UP`:
     ```bash
     curl -s http://127.0.0.1:8080/actuator/health
     ```
   - нет повторных `LIVE_TICKER_NOT_ALLOWED` и нет неожиданных `alor.order.blocked`.

> Внимание: SBOM-обёртка здесь не требуется — SIM на живых данных самодостаточен.

---

## 3. Деплой LIVE (ручной, через GitHub Actions)

### 3.1. Пройти чек-лист готовности (обязательно)

Прежде чем нажимать — зафиксируй ответы:

- [ ] 1. Все три проверки локально зелёные (`test` + `integrationTest` + `ktlintCheck`).
- [ ] 2. На VM уже стоит SIM-сборка последнего SHA, health `UP`, логов нет блокирующих.
- [ ] 3. На счёте достаточно средств: для CNYRUBF нужны минимум ~4 000 ₽
      (ГО ~2 700 ₽ + запас на daily-loss потолок `min(2% AUM, 5 000₽)` + издержки).
- [ ] 4. `ALOR_TOKEN` / `ALOR_REFRESH_TOKEN` в Secrets актуальный (не истёк).
- [ ] 5. Ты согласен с риск-реалистичной трактовкой: CNYRUBF — RESEARCH_ONLY
      (WFA OOS PF 0.69 на 365д), это исследовательский запуск, не подтверждённая
      «доходность 100%».
- [ ] 6. Окно торговли = биржевые часы MOEX (будни; клиринг 18:45 МСК не трогаем
      — открытие/закрытие на границе клиринга не считаются).

### 3.2. Запуск job

GitHub → Actions → **CI** → **Run workflow** → заполнить:

- ветка: `main`
- `Deploy LIVE (CNYRUBF only)` → **включить** (true)
- `Подтверждение запуска LIVE-деплоя` → ввести строку **`LIVE_CONFIRM`**

Кликнуть **Run workflow**. После этого: GitHub запросит одобрение у required
reviewers Environment `live` — подтвердить.

### 3.3. Что делает `deploy-live` (сверка)

- проверяет `live_confirmation == "LIVE_CONFIRM"` и `deploy_live == "true"`;
- ждёт одобрения Environment `live`;
- проверяет наличие образов `trading-bot-app:<SHA>` и `trading-bot-frontend:<SHA>`
  (если их нет — job падает с сообщением «Build it first by pushing this SHA to main»);
- копирует compose-файлы на `/opt/trading-bot`;
- пишет `.env` с `TRADING_MODE=LIVE`, `TRADING_TICKERS=CNYRUBF`,
  `LIVE_TICKERS_ALLOWLIST=CNYRUBF`;
- `docker compose pull && up -d`, Image prune, статус контейнеров.

---

## 4. Верификация LIVE на VM (первые 1–2 часа — критично)

```bash
ssh <VM_USER>@<VM_HOST>
cd /opt/trading-bot

# 1. Статус контейнеров
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps

# 2. Проверка, что сборка НЕ уехала в SIMULATION (guard): в .env должно быть LIVE
grep TRADING_MODE .env          # → TRADING_MODE=LIVE
grep LIVE_TICKERS_ALLOWLIST .env # → LIVE_TICKERS_ALLOWLIST=CNYRUBF

# 3. Логи app — живой поток решений
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs --follow app

# 4. Health
curl -s http://127.0.0.1:8080/actuator/health

# 5. Prometheus-метрики бота (если Prometheus на VM)
curl -s http://127.0.0.1:8080/actuator/prometheus | grep -E "entry|alor|futures|market.data"
```

### 4.1. Критерии «всё хорошо»

- `market.data.age_ms` ≤ 15 000 (свежесть цены);
- `futures.go_cache_age_ms` ≤ 30 000 и `futures.balance_cache_age_ms` ≤ 30 000;
- спред метрика `market.data.spread_percent` ≤ 0.1 (nullable, не растёт);
- в логах НЕТ непрерывных `entry.rejected{reason=...}` по причинам
  `LIVE_TICKER_NOT_ALLOWED` / `PORTFOLIO_MARGIN_DATA_UNAVAILABLE` / `VOLATILITY_GUARD`
  (единичные допускаются, серии — стоп);
- нет `llm.fallback.activated` сериями (если LLM-ключ задан); если ключ пуст —
  fallback ожидаем.

### 4.2. Критерии «СТОП-разбор» (продолжать нельзя)

- `TRADING_MODE=SIMULATION` в `.env` бота (деплой не в LIVE) — откат;
- принудительные реконсилии/фенсинг-ошибки в логах (см. `docs/12`), потеря локов;
- цена не обновляется > 15 с (stale) серийно;
- баланс/ГО-кэш старше 30 с серийно при попытке входа.

---

## 5. Ежедневный контроль (после первого дня)

- Просмотр `entry.rejected` по тикеру — какие гейты реально режут.
- `потеря ≤ min(2% AUM, 5 000₽)/день` — автостоп у бота; следи, что он срабатывает.
- funding-расчёт: CNYRUBF 0.5 ₽/клиринг, 2 клиринга/день — сверь с выпиской.
- Metrics в Grafana/Prometheus: `close.price_estimated`, `futures.go_cache_age_ms`.

---

## 6. Откат / возврат в SIMULATION

```bash
ssh <VM_USER>@<VM_HOST>
cd /opt/trading-bot
cat > .env <<'EOF'
REGISTRY=$REGISTRY
TAG=$TAG
TRADING_MODE=SIMULATION
...
EOF
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --force-recreate app
```

Либо просто не запускать `deploy-live` (старый файл `.env` с SIM останется).
Гарантия безопасности: на автопушке `deploy-sim` принудительно пишет
`TRADING_MODE=SIMULATION`, даже если секрет `TRADING_MODE` задан.

---

## 7. FAQ по LIVE-guard

**Q: Могу ли я торговать GAZP/SBER в LIVE?**
Нет. `LIVE_TICKERS_ALLOWLIST=CNYRUBF` жёстко в job `deploy-live`; изменение
allowlist — отдельный PR и явное решение, иначе `DecisionEngine` и
`RestOrderTransport` заблокируют все входы (fail-closed).

**Q: Что если `LLM_API_KEY` пуст в LIVE?**
Все LLM-агенты падают на детерминированные fallback (NEUTRAL/HOLD). Торговля
идёт по детерминированным стратегиям; LLM — только advisory-слой. Это безопасно.

**Q: Как понять, что бот в LIVE реально торгует CNYRUBF?**
В логах `entry` с `reason=ACCEPTED` для тикера CNYRUBF + ордера в выписке Alor
(не только в SIM-логах). Метрика `alor.order.*`.

**Q: Что за `LIVE_TICKER_NOT_ALLOWED` в SIM?**
В SIM allowlist не применяется; если причина появилась в SIM — значит,
`TRADING_MODE` фактически `LIVE` (проверь `grep TRADING_MODE .env`).