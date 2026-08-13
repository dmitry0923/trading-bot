# 8. Конфигурация и переменные окружения

## 8.1. application.yml (полный, с комментариями)

```yaml
server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus   # какие actuator-endpoint'ы открыты
  metrics:
    export:
      prometheus:
        enabled: true                             # включить /actuator/prometheus
    tags:
      application: ${spring.application.name}     # глобальный label для всех метрик

spring:
  application:
    name: mmvb-trading-bot-v2
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:trading_bot}
    username: ${DB_USER:trader}
    password: ${DB_PASS:}
    driver-class-name: org.postgresql.Driver
  r2dbc:
    url: r2dbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:trading_bot}
    username: ${DB_USER:trader}
    password: ${DB_PASS:}
    pool:
      initial-size: 5
      max-size: 20
  liquibase:
    enabled: true
    change-log: classpath:db/changelog/db.changelog-master.yaml
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    timeout: 5s
  rabbitmq:                        # опционально: RabbitMQ-транспорт outbox (раздел 13.8.4)
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}

app:
  outbox:
    rabbitmq:                      # вкл/выкл + топология; disabled = поведение прежнее
      enabled: ${OUTBOX_RABBITMQ_ENABLED:false}
      exchange: ${OUTBOX_RABBITMQ_EXCHANGE:trading.outbox}
      queue: ${OUTBOX_RABBITMQ_QUEUE:trading.outbox.orders}
      routing-key: ${OUTBOX_RABBITMQ_ROUTING_KEY:order}
      dlx: ${OUTBOX_RABBITMQ_DLX:trading.outbox.dlx}
      dlq: ${OUTBOX_RABBITMQ_DLQ:trading.outbox.orders.dlq}

alor:                              # брокер Alor
  api-url: ${ALOR_API_URL:https://api.alor.ru}
  ws-url: ${ALOR_WS_URL:wss://api.alor.ru/ws}
  token: ${ALOR_TOKEN:}            # access token
  refresh-token: ${ALOR_REFRESH_TOKEN:}
  portfolio: ${ALOR_PORTFOLIO:D12345}
  exchange: ${ALOR_EXCHANGE:MOEX}
  ws-orders-enabled: ${ALOR_WS_ORDERS_ENABLED:false}  # WS-primary ордеров (13.8.2)
  ws-order-timeout-ms: ${ALOR_WS_ORDERS_TIMEOUT_MS:10000}

moex:
  base-url: https://iss.moex.com/iss

macro:                             # макро-контекст для фундаментального анализа
  cbr-rate: ${CBR_RATE:16.0}       # ключевая ставка ЦБ (fallback; live нет)
  brent-price: ${BRENT_PRICE:75.0} # нефть Brent (fallback)
  usd-rub: ${USD_RUB:90.0}         # курс USD/RUB (fallback; live с MOEX приоритетнее)
  usd-rub-ticker: USD000UTSTOM     # тикер валютной секции MOEX для live-курса

ml:                                # ML-модуль (roadmap v2.4, раздел 13.11)
  enabled: ${ML_ENABLED:false}     # мастер-флаг: false = эндпоинты датасета/скрининга 404
  dataset:
    timeframe: ${ML_DATASET_TIMEFRAME:MINUTE_10}     # таймфрейм свечей для признаков
    lookback-bars: ${ML_DATASET_LOOKBACK_BARS:30}    # окно признаков (свечей до входа)
    max-rows: ${ML_DATASET_MAX_ROWS:5000}            # лимит строк экспорта
  model:
    path: ${ML_MODEL_PATH:ml/model.cbm}              # файл обученной CatBoost-модели (13.11.3)
  screening:
    top-n: ${ML_SCREENING_TOP_N:5}                   # число лучших тикеров в скрининге (13.11.4)
  trend:
    horizon-bars: ${ML_TREND_HORIZON_BARS:6}         # горизонт прогноза тренда в барах (13.11.7)
    top-n: ${ML_TREND_TOP_N:5}                       # число лучших тикеров в прогнозе тренда (13.11.7)
  filter:
    enabled: ${ML_FILTER_ENABLED:false}              # ML-фильтр входа в торговый цикл (13.11.5)
    threshold: ${ML_FILTER_THRESHOLD:0.5}            # мин. вероятность выигрыша для входа
    trend-gate-enabled: ${ML_FILTER_TREND_GATE_ENABLED:false}  # тренд-гейт входа (13.11.7)
    trend-min-score: ${ML_FILTER_TREND_MIN_SCORE:0.5}          # мин. оценка удержания тренда (13.11.7)

llm:
  api-key: ${KIMI_API_KEY:}        # если пуст — все LLM-вызовы мгновенно fallback (NO_API_KEY)
  base-url: ${KIMI_BASE_URL:https://api.moonshot.cn/v1}
  model: ${KIMI_MODEL:kimi-k3}
  timeout-sec: 30                  # HTTP-таймаут LLM
  max-tokens: 4096
  temperature: 0.15                # температура по умолчанию
  semantic-cache-enabled: ${LLM_SEMANTIC_CACHE:true}
  semantic-cache-ttl-minutes: ${LLM_SEMANTIC_CACHE_TTL:10}
  guardrails-max-price-deviation-percent: 3.0   # max отклонение targetPrice от рынка
  circuit-breaker-enabled: true
  rate-limiter-enabled: true
  retry-enabled: true
  queue-capacity: 64          # ёмкость FIFO-очереди LLM-запросов
  queue-concurrency: 2        # максимум одновременных LLM-вызовов

resilience4j:                      # конфигурация Resilience4j для LLM
  circuitbreaker:
    instances:
      llm:
        registerHealthIndicator: true
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
        waitDurationInOpenState: 20s
        failureRateThreshold: 50
        eventConsumerBufferSize: 10
        recordExceptions:
          - java.io.IOException
          - org.springframework.web.reactive.function.client.WebClientRequestException
          - org.springframework.web.reactive.function.client.WebClientResponseException
  ratelimiter:
    instances:
      llm:
        limitForPeriod: 20         # 20 запросов
        limitRefreshPeriod: 60s    # за 60 секунд
        timeoutDuration: 5s
  retry:
    instances:
      llm:
        maxAttempts: 3
        waitDuration: 1s
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2.0
        maxInterval: 5s
        retryExceptions:
          - java.io.IOException
          - org.springframework.web.reactive.function.client.WebClientRequestException
          - org.springframework.web.reactive.function.client.WebClientResponseException$TooManyRequests
        ignoreExceptions:
          - org.springframework.web.reactive.function.client.WebClientResponseException$BadRequest

trading:
  mode: ${TRADING_MODE:SIMULATION} # SIMULATION | LIVE
  tickers:
    - SBER
    - GAZP
    - LKOH
    - YNDX
    - MGNT
    - NVTK
    - ROSN
    - TATN
    - VTBR
    - ALRS
  bot-interval-ms: ${BOT_INTERVAL_MS:300000}       # цикл бота: 5 мин
  strategy-interval-ms: ${STRATEGY_INTERVAL_MS:600000}  # цикл стратегий: 10 мин
  monitor-interval-ms: ${MONITOR_INTERVAL_MS:600000}    # мониторинг позиций: 10 мин
  max-open-positions-for-new-entry: ${MAX_OPEN_POS:0}   # 0 = НЕ открывать позиции (страховка)
  timeframe: MINUTE_10

risk:
  enabled: true
  max-position-rub: 500000
  max-daily-loss-rub: 50000
  max-open-positions: 5
  max-sector-exposure: 2              # макс. открытых позиций в одном секторе
  max-volatility-percent: 5.0         # ATR% от цены, выше которого вход запрещён
  default-stop-loss-percent: 2.0
  default-take-profit-percent: 4.0
  trailing-stop-enabled: true
  trailing-stop-percent: 1.5
  # Online-калибровка порога уверенности по исходам сделок (roadmap 13.11.8)
  confidence-calibration-enabled: true
  confidence-calibration-days: 14
  confidence-calibration-min-trades: 10
  confidence-calibration-target-win-rate: 0.55
  confidence-calibration-min-threshold: 0.50
  confidence-calibration-max-threshold: 0.85
  confidence-calibration-step: 0.05
  # Confidence-aware позиционный сайзинг (roadmap 13.11.9)
  confidence-sizing-enabled: true
  confidence-sizing-min-factor: 0.5
  confidence-sizing-max-factor: 1.0
  confidence-sizing-ceiling: 0.90
  sectors:                            # справочник ticker -> сектор (sector concentration)
    SBER: FINANCE
    VTBR: FINANCE
    GAZP: ENERGY
    ROSN: ENERGY
    TATN: ENERGY
    LKOH: ENERGY
    NVTK: ENERGY
    YNDX: IT
    MGNT: RETAIL
    ALRS: METALS

logging:
  level:
    com.trading.bot: DEBUG
    org.springframework.r2dbc: DEBUG

security:                            # аутентификация (JWT) — см. SecurityConfig
  auth:
    user: ${AUTH_USER:}              # admin; пусто = отказ старта (дефолтов НЕТ)
    password: ${AUTH_PASSWORD:}      # пароль admin; пусто = отказ старта
  analytics:                         # read-only пользователь (создаётся, если заданы оба)
    user: ${ANALYTICS_USER:}
    password: ${ANALYTICS_PASSWORD:}
  metrics:
    scrape-token: ${METRICS_SCRAPE_TOKEN:}   # Bearer для /actuator/prometheus
  jwt:
    secret: ${JWT_SECRET:}           # HS256-ключ, >= 32 байта; пусто = отказ старта
    access-ttl-minutes: ${JWT_ACCESS_TTL_MINUTES:15}
    refresh-ttl-days: ${JWT_REFRESH_TTL_DAYS:30}
    cookie-secure: ${JWT_COOKIE_SECURE:false}

lockbox:                             # Yandex Lockbox (секреты как env fallback)
  enabled: ${LOCKBOX_ENABLED:false}
  secret-id: ${LOCKBOX_SECRET_ID:}
  iam-token: ${LOCKBOX_IAM_TOKEN:}   # или ключ сервисного аккаунта
  sa-key-json: ${LOCKBOX_SA_KEY_JSON:}
```

## 8.2. Таблица переменных окружения

| Переменная | Default | Required | Описание | Пример |
|---|---|---|---|---|
| `DB_HOST` | `localhost` | да (кроме тестов) | хост PostgreSQL | `postgres` |
| `DB_PORT` | `5432` | | порт PostgreSQL | `5432` |
| `DB_NAME` | `trading_bot` | | имя БД | `trading_bot` |
| `DB_USER` | `trader` | | пользователь БД | `trader` |
| `DB_PASS` | `` | **да** | пароль БД, без дефолта | `s3cr3t` |
| `REDIS_HOST` | `localhost` | | хост Redis | `redis` |
| `REDIS_PORT` | `6379` | | порт Redis | `6379` |
| `ALOR_API_URL` | `https://api.alor.ru` | да (для LIVE) | REST Alor | `https://api.alor.ru` |
| `ALOR_WS_URL` | `wss://api.alor.ru/ws` | да (для LIVE) | WebSocket Alor | `wss://api.alor.ru/ws` |
| `ALOR_TOKEN` | `` | да (для LIVE) | access token Alor | `eyJhbGci...` |
| `ALOR_REFRESH_TOKEN` | `` | нет | refresh token Alor | `r.eyJ...` |
| `ALOR_PORTFOLIO` | `D12345` | да (для LIVE) | номер портфеля | `D12345` |
| `ALOR_EXCHANGE` | `MOEX` | | биржа | `MOEX` |
| `ALOR_WS_ORDERS_ENABLED` | `false` | | WS-primary доставка ордеров, REST — fallback (раздел 13.8.2) | `true` |
| `ALOR_WS_ORDERS_TIMEOUT_MS` | `10000` | | таймаут подтверждения WS-команды, мс | `10000` |
| `KIMI_API_KEY` | `` | да (для LLM) | API-ключ Kimi | `sk-...` |
| `KIMI_BASE_URL` | `https://api.moonshot.cn/v1` | | базовый URL LLM | `https://api.moonshot.cn/v1` |
| `KIMI_MODEL` | `kimi-k3` | | модель | `kimi-k3` |
| `CBR_RATE` | `16.0` | | ставка ЦБ fallback | `16.0` |
| `BRENT_PRICE` | `75.0` | | нефть Brent fallback | `75.0` |
| `USD_RUB` | `90.0` | | курс fallback | `90.0` |
| `LLM_SEMANTIC_CACHE` | `true` | | семантический кэш вкл/выкл | `true` |
| `LLM_SEMANTIC_CACHE_TTL` | `10` | | TTL кэша, мин | `10` |
| `TRADING_MODE` | `SIMULATION` | **да** | SIMULATION / LIVE | `SIMULATION` |
| `BOT_INTERVAL_MS` | `300000` | | интервал бот-цикла | `300000` |
| `STRATEGY_INTERVAL_MS` | `600000` | | интервал стратегий | `600000` |
| `MONITOR_INTERVAL_MS` | `600000` | | интервал мониторинга | `600000` |
| `MAX_OPEN_POS` | `0` | | макс. новых позиций за цикл (0 = не открывать) | `3` |
| `AUTH_USER` | `` | **да** | админ (роль ADMIN), пусто = отказ старта | `admin` |
| `AUTH_PASSWORD` | `` | **да** | пароль админа | `Str0ng!Pass` |
| `ANALYTICS_USER` | `` | | read-only пользователь | `analytics` |
| `ANALYTICS_PASSWORD` | `` | | пароль аналитика | `view-only` |
| `JWT_SECRET` | `` | **да** | HS256-ключ JWT, ≥ 32 байта | `openssl rand -base64 48` |
| `JWT_ACCESS_TTL_MINUTES` | `15` | | TTL access-токена, мин | `15` |
| `JWT_REFRESH_TTL_DAYS` | `30` | | TTL refresh-токена, дней | `30` |
| `JWT_COOKIE_SECURE` | `false` | | `Secure` на refresh-cookie (за HTTPS) | `true` |
| `METRICS_SCRAPE_TOKEN` | `` | да (для Prometheus) | Bearer на `/actuator/prometheus` | `openssl rand -base64 32` |
| `LOCKBOX_ENABLED` | `false` | | читать секреты из Yandex Lockbox | `true` |
| `LOCKBOX_SECRET_ID` | `` | да (при Lockbox) | ID секрета | `e6q...` |
| `LOCKBOX_IAM_TOKEN` | `` | | IAM-токен для Lockbox API | `t1.9e...` |
| `LOCKBOX_SA_KEY_JSON` | `` | | ключ сервисного аккаунта (fallback к IAM) | `{"id":...}` |
| `OUTBOX_RABBITMQ_ENABLED` | `false` | | включать RabbitMQ-транспорт outbox (раздел 13.8.4) | `true` |
| `RABBITMQ_HOST` | `localhost` | да (при OUTBOX_RABBITMQ_ENABLED) | хост RabbitMQ | `rabbitmq` |
| `RABBITMQ_PORT` | `5672` | | порт AMQP | `5672` |
| `RABBITMQ_USER` | `guest` | | пользователь RabbitMQ | `trader` |
| `RABBITMQ_PASSWORD` | `guest` | | пароль RabbitMQ | `s3cr3t` |
| `OUTBOX_RABBITMQ_EXCHANGE` | `trading.outbox` | | exchange для публикации outbox-строк | `trading.outbox` |
| `OUTBOX_RABBITMQ_QUEUE` | `trading.outbox.orders` | | очередь ордеров | `trading.outbox.orders` |
| `OUTBOX_RABBITMQ_ROUTING_KEY` | `order` | | routing key публикации | `order` |
| `ML_ENABLED` | `false` | | включить ML-модуль: эндпоинты экспорта датасета и скрининга (раздел 13.11) | `true` |
| `ML_DATASET_TIMEFRAME` | `MINUTE_10` | | таймфрейм свечей для признаков датасета | `MINUTE_10` |
| `ML_DATASET_LOOKBACK_BARS` | `30` | | окно признаков: свечей до входа | `30` |
| `ML_DATASET_MAX_ROWS` | `5000` | | лимит строк экспорта | `5000` |
| `ML_MODEL_PATH` | `ml/model.cbm` | | путь к файлу обученной CatBoost-модели (13.11.3); нет файла → скрининг 503 | `ml/model.cbm` |
| `ML_SCREENING_TOP_N` | `5` | | число лучших тикеров-кандидатов в скрининге (13.11.4) | `5` |
| `ML_TREND_HORIZON_BARS` | `6` | | горизонт прогноза удержания тренда в барах (интерпретация оценки, 13.11.7) | `6` |
| `ML_TREND_TOP_N` | `5` | | число лучших тикеров в прогнозе тренда (13.11.7) | `5` |
| `ML_FILTER_ENABLED` | `false` | | ML-фильтр входа в торговый цикл: прогноз модели < порога → вход отклоняется (13.11.5) | `true` |
| `ML_FILTER_TREND_GATE_ENABLED` | `false` | | тренд-гейт входа: вход требует оценку удержания тренда >= `ML_FILTER_TREND_MIN_SCORE` (13.11.7) | `true` |
| `ML_FILTER_TREND_MIN_SCORE` | `0.5` | | мин. оценка удержания тренда для входа при включённом тренд-гейте (13.11.7) | `0.6` |
| `ML_FILTER_THRESHOLD` | `0.5` | | минимальная вероятность выигрышного исхода для входа (13.11.5) | `0.55` |
| `OUTBOX_RABBITMQ_DLX` | `trading.outbox.dlx` | | dead-letter exchange | `trading.outbox.dlx` |
| `OUTBOX_RABBITMQ_DLQ` | `trading.outbox.orders.dlq` | | очередь парковки неудачных доставок | `trading.outbox.orders.dlq` |

## 8.3. .env для local development

```dotenv
# PostgreSQL (без дефолтного пароля)
DB_HOST=localhost
DB_PORT=5432
DB_NAME=trading_bot
DB_USER=trader
DB_PASS=change-me-local-db-pass

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# RabbitMQ (опционально, профиль docker compose: rabbitmq; раздел 13.8.4)
OUTBOX_RABBITMQ_ENABLED=false
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USER=guest
RABBITMQ_PASSWORD=guest

# Alor (заполнить для LIVE)
ALOR_API_URL=https://api.alor.ru
ALOR_WS_URL=wss://api.alor.ru/ws
ALOR_TOKEN=
ALOR_REFRESH_TOKEN=
ALOR_PORTFOLIO=D12345
ALOR_EXCHANGE=MOEX

# Kimi LLM
KIMI_API_KEY=
KIMI_BASE_URL=https://api.moonshot.cn/v1
KIMI_MODEL=kimi-k3

# Trading
TRADING_MODE=SIMULATION
MAX_OPEN_POS=0
BOT_INTERVAL_MS=300000
STRATEGY_INTERVAL_MS=600000
MONITOR_INTERVAL_MS=600000

# Auth (JWT) — без дефолтов, обязательны
AUTH_USER=admin
AUTH_PASSWORD=very-strong-password
ANALYTICS_USER=analytics
ANALYTICS_PASSWORD=view-only-password
JWT_SECRET=change-to-32+random-bytes!
METRICS_SCRAPE_TOKEN=change-me-prometheus-token
```

> На Windows переменные можно передать так: `$env:KIMI_API_KEY="sk-..."; $env:TRADING_MODE="SIMULATION"; .\gradlew.bat bootRun`.

## 8.4. Kubernetes Secrets и ConfigMaps

**Secret** (`bot-secrets`):
- `DB_PASS`
- `ALOR_TOKEN`, `ALOR_REFRESH_TOKEN`, `ALOR_PORTFOLIO`
- `KIMI_API_KEY`
- `AUTH_USER`, `AUTH_PASSWORD`, `ANALYTICS_USER`, `ANALYTICS_PASSWORD`
- `JWT_SECRET`, `METRICS_SCRAPE_TOKEN`

**ConfigMap** (`bot-config`):
- `application.yml` (все несекретные значения)
- `prompts/*.yml` (промпты агентов, hot-reload)

Пример монтирования — в разделе 10.

## 8.5. Trading Modes

### SIMULATION

| Параметр | Поведение |
|---|---|
| `TRADING_MODE=SIMULATION` | AlorClient не ходит в Alor: `getMarketSnapshot` возвращает фиктивный снимок (цена 100.0, bid 99.9, ask 100.1, volume 1 000 000); ордера возвращают `sim-<type>-<ticker>-<timestamp>` |
| LLM | работает, если задан `KIMI_API_KEY`; иначе fallback |
| Outbox | пишется, ордера «исполняются» мгновенно (SENT) |
| Позиции | `MAX_OPEN_POS` контролирует открытие |

> Важно: даже в SIMULATION бот **открывает позиции в БД**, если `MAX_OPEN_POS > 0`. Перед боем поставьте `MAX_OPEN_POS=0` и убедитесь, что логика устраивает.

### LIVE

| Параметр | Поведение |
|---|---|
| `TRADING_MODE=LIVE` | реальные запросы к Alor REST/WS |
| Обязательны | `ALOR_TOKEN`, `ALOR_PORTFOLIO`, реальный `KIMI_API_KEY` |
| Проверки | market-ордер блокируется при спреде > 0.5%; slippage метрики пишутся |

**Переключение**: только через Secret + restart pod (см. раздел 10). **Не** менять в рантайме — бот читает конфиг при старте.

## 8.6. Рекомендации

- `max-open-positions-for-new-entry` (`MAX_OPEN_POS`) — это лимит **новых входов за один бот-цикл**, не путать с `risk.max-open-positions` (максимум открытых одновременно).
- Логирование R2DBC (`org.springframework.r2dbc: DEBUG` / `io.r2dbc: DEBUG`) — для диагностики, в проде убрать.
- `spring.datasource.*` (JDBC) нужен ТОЛЬКО для Liquibase-миграций; все запросы приложения идут по `spring.r2dbc.*`. Так как Spring Boot 3.2 не создаёт JDBC `DataSource` при наличии R2DBC `ConnectionFactory`, бин `liquibaseDataSource` объявлен явно в `DatabaseConfig` (см. раздел 6.3).

## 8.7. Приоритет конфигурации (Spring Boot)

Порядок, от высшего к низшему:

1. **Переменные окружения** — `DB_HOST`, `ALOR_TOKEN` и т.д. (именно так переопределяются дефолты из `application.yml`).
2. **`application.yml`** — значения по умолчанию с `${VAR:default}`.
3. **Захардкоженные дефолты** в `@ConfigurationProperties`-классах (например `RiskConfig.maxSectorExposure = 2`), если ничего не задано.

Пример: `risk.max-volatility-percent` отсутствует в env (переменной нет) → берётся `5.0` из `application.yml` → если бы его не было и в yml, взялось бы `5.0` из кода.

**Что НЕ должно быть в конфиге**: токены в git-истории, ключи в открытом виде, реальные данные портфеля. Всё секретное — только env/Secrets.

## 8.8. Проверка конфигурации при старте

Бот при старте логирует ключевые параметры (уровень `INFO`):

```
TRADING_MODE=SIMULATION, MAX_OPEN_POS=0
risk: enabled=true, maxPosition=500000, maxDailyLoss=50000, sectorExposure=2, volatility=5.0%
llm: model=kimi-k3, timeout=30s, cb=true, rl=true, retry=true, cache=true
```

Это позволяет оператору сразу увидеть, в каком режиме поднялся бот. Проверка «не пристрели себе ногу» (требуется JWT, см. раздел 7):

```bash
# получить access-токен
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"'"$AUTH_USER"'","password":"'"$AUTH_PASSWORD"'"}' | jq -r .accessToken)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/settings   # tradingEnabled / riskEnabled
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/risk/daily-pnl
```

## 8.9. Нестандартные сценарии настройки

| Сценарий | Настройка |
|---|---|
| Не торговать совсем (только анализ) | `MAX_OPEN_POS=0` + `risk.enabled=true` |
| Отключить риск-движок | `risk.enabled=false` (все guardrails выключены) |
| Увеличить число позиций | `risk.max-open-positions=8` |
| Ослабить секторную концентрацию | `risk.max-sector-exposure=3` |
| Разрешить волатильные инструменты | `risk.max-volatility-percent=8.0` |
| Добавить тикер в торговлю | добавить в `trading.tickers` + `risk.sectors` (иначе сектор UNKNOWN) |
| Длинный горизонт бэктеста | `GET /api/v1/backtest/{ticker}?days=730` (требует историю свечей) |
| Анализ устойчивости стратегии | `GET /api/v1/backtest/{ticker}/robustness?simulations=2000` (Monte Carlo + стресс-сценарии, раздел 13.7.8; число симуляций — `bt.monte-carlo-simulations`) |
