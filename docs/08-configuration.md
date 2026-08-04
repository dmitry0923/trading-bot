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
    password: ${DB_PASS:trader}
    driver-class-name: org.postgresql.Driver
  liquibase:
    enabled: true
    change-log: classpath:db/changelog/db.changelog-master.yaml
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    timeout: 5s

alor:                              # брокер Alor
  api-url: ${ALOR_API_URL:https://api.alor.ru}
  ws-url: ${ALOR_WS_URL:wss://api.alor.ru/ws}
  token: ${ALOR_TOKEN:}            # access token
  refresh-token: ${ALOR_REFRESH_TOKEN:}
  portfolio: ${ALOR_PORTFOLIO:D12345}
  exchange: ${ALOR_EXCHANGE:MOEX}

moex:
  base-url: https://iss.moex.com/iss

macro:                             # макро-контекст для фундаментального анализа
  cbr-rate: ${CBR_RATE:16.0}       # ключевая ставка ЦБ (fallback; live нет)
  brent-price: ${BRENT_PRICE:75.0} # нефть Brent (fallback)
  usd-rub: ${USD_RUB:90.0}         # курс USD/RUB (fallback; live с MOEX приоритетнее)
  usd-rub-ticker: USD000UTSTOM     # тикер валютной секции MOEX для live-курса

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
    org.springframework.jdbc: DEBUG
```

## 8.2. Таблица переменных окружения

| Переменная | Default | Required | Описание | Пример |
|---|---|---|---|---|
| `DB_HOST` | `localhost` | да (кроме тестов) | хост PostgreSQL | `postgres` |
| `DB_PORT` | `5432` | | порт PostgreSQL | `5432` |
| `DB_NAME` | `trading_bot` | | имя БД | `trading_bot` |
| `DB_USER` | `trader` | | пользователь БД | `trader` |
| `DB_PASS` | `trader` | | пароль БД | `s3cr3t` |
| `REDIS_HOST` | `localhost` | | хост Redis | `redis` |
| `REDIS_PORT` | `6379` | | порт Redis | `6379` |
| `ALOR_API_URL` | `https://api.alor.ru` | да (для LIVE) | REST Alor | `https://api.alor.ru` |
| `ALOR_WS_URL` | `wss://api.alor.ru/ws` | да (для LIVE) | WebSocket Alor | `wss://api.alor.ru/ws` |
| `ALOR_TOKEN` | `` | да (для LIVE) | access token Alor | `eyJhbGci...` |
| `ALOR_REFRESH_TOKEN` | `` | нет | refresh token Alor | `r.eyJ...` |
| `ALOR_PORTFOLIO` | `D12345` | да (для LIVE) | номер портфеля | `D12345` |
| `ALOR_EXCHANGE` | `MOEX` | | биржа | `MOEX` |
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

## 8.3. .env для local development

```dotenv
# PostgreSQL
DB_HOST=localhost
DB_PORT=5432
DB_NAME=trading_bot
DB_USER=trader
DB_PASS=trader

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

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
```

> На Windows переменные можно передать так: `$env:KIMI_API_KEY="sk-..."; $env:TRADING_MODE="SIMULATION"; .\gradlew.bat bootRun`.

## 8.4. Kubernetes Secrets и ConfigMaps

**Secret** (`bot-secrets`):
- `DB_PASS`
- `ALOR_TOKEN`, `ALOR_REFRESH_TOKEN`, `ALOR_PORTFOLIO`
- `KIMI_API_KEY`

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
- Логирование JDBC (`org.springframework.jdbc: DEBUG`) — для диагностики, в проде убрать.

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

Это позволяет оператору сразу увидеть, в каком режиме поднялся бот. Проверка «не пристрели себе ногу»:

```bash
curl http://localhost:8080/api/v1/settings   # tradingEnabled / riskEnabled
curl http://localhost:8080/api/v1/risk/daily-pnl
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
