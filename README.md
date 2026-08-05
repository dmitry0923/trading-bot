# MMVB Trading Bot v2

Production-ready trading bot for Moscow Exchange (MOEX) with AI-driven strategy optimization.

- Ведёт собственную статистику (реальные закрытые сделки), строит **прогноз прибыли**
  для инвесторов и **автоматически рассчитывает клиринг** (вывод средств) на дату выхода.
- **Гибкое подключение LLM**: по умолчанию агрегатор RouterAI (`routerai.ru`),
  переключение на Kimi / DeepSeek / Qwen через UI или env.
- **Мультиинструменты + мультитаймфреймы** (10m / 1h / 1d).
- **Единый флаг торговли** и принудительное закрытие позиций — сейчас или по времени.
- Отдельные UI-пользователи: **ADMIN** (настройки) и **ANALYTICS** (только просмотр).
- UUIDv7 для всех новых записей, CI с автодеплоем на Yandex Cloud.

## Architecture

- **Kotlin 1.9.21** + **Spring Boot 3.2.0**
- **R2DBC** (reactive `DatabaseClient`, all repositories `suspend`) + **Liquibase** migrations (JDBC)
- **PostgreSQL** for persistence
- **Redis** for caching
- **Micrometer** + **Prometheus** metrics
- **Docker** + **Docker Compose**

## Quick Start

### 1. Prerequisites

- Docker & Docker Compose
- Java 21 (for local development)
- Gradle 8.x (wrapper included)

### 2. Environment Setup

Create `.env` file:

```bash
ALOR_TOKEN=your_alor_token
ALOR_REFRESH_TOKEN=your_refresh_token
ALOR_PORTFOLIO=D12345
# LLM: по умолчанию RouterAI (агрегатор). Для прямых провайдеров:
LLM_PROVIDER=ROUTER_AI
LLM_API_KEY=your_routerai_api_key
# ROUTER_AI_BASE_URL=https://routerai.ru/api/v1
# ROUTER_AI_MODEL=auto
# KIMI_API_KEY=...
# DEEPSEEK_API_KEY=...
# QWEN_API_KEY=...
TRADING_MODE=SIMULATION
# Security (Basic Auth for API and Actuator)
AUTH_USER=admin
AUTH_PASSWORD=change-me-now
# Отдельный пользователь аналитики (только просмотр)
ANALYTICS_USER=analytics
ANALYTICS_PASSWORD=analytics-view-only
```

> **Important**: set a strong `AUTH_PASSWORD` before exposing the bot outside
> localhost. The API (`/api/v1/**`) and Actuator endpoints are protected with
> Spring Security Basic Auth; only `/actuator/health` is public (Docker healthcheck).
> Изменение настроек (POST `/api/v1/settings`) доступно только роли ADMIN.

### 3. Run with Docker Compose

```bash
docker-compose up -d
```

Services:
- Dashboard (frontend, nginx): http://localhost:80
- App API: http://localhost:8080 (requires Basic Auth)
- Health check: http://localhost:8080/actuator/health
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000

### 4. Local Development

```bash
# Start dependencies
docker-compose up -d postgres redis

# Run app
./gradlew bootRun

# Run tests
./gradlew clean check
```

## API Endpoints

All `/api/v1/**` endpoints require **Basic Auth** (`AUTH_USER` / `AUTH_PASSWORD`):

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/settings` | GET/POST | Bot settings (POST — только ADMIN) |
| `/api/v1/me` | GET | Текущий пользователь и роли |
| `/api/v1/llm/providers` | GET | Доступные LLM-провайдеры и активный |
| `/api/v1/trading/status` | GET | Флаг торговли, force-close, открытые позиции |
| `/api/v1/trading/enable` / `disable` | POST | Единый флаг включения/выключения торговли |
| `/api/v1/trading/force-close` | POST | Принудительное закрытие всех позиций сейчас |
| `/api/v1/trading/force-close-at?time=HH:mm` | POST | Плановое закрытие по времени |
| `/api/v1/trading/force-close-cancel` | POST | Отмена планового закрытия |
| `/api/v1/investors` | GET/POST | Список / создание инвесторов |
| `/api/v1/investors/{id}` | GET | Инвестор + счёт + P&L |
| `/api/v1/investors/{id}/deposit` | POST | Депозит |
| `/api/v1/investors/{id}/withdraw` | POST | Вывод |
| `/api/v1/investors/{id}/transactions` | GET | Транзакции инвестора |
| `/api/v1/investors/allocations` | GET | Аллокации |
| `/api/v1/forecast?horizonDays=&capitalBase=` | GET | Прогноз прибыли на реальной статистике |
| `/api/v1/clearing/quote?investorId=&date=` | GET | Расчёт клиринга (доля + P&L + прогноз) |
| `/api/v1/clearing/settle?investorId=&date=` | POST | Исполнение клиринга |
| `/api/v1/clearing/pool` | GET | Статистика пула инвесторов |
| `/api/v1/strategies` | GET | Last 50 strategies |
| `/api/v1/positions` | GET | Open positions |
| `/api/v1/positions/all` | GET | All positions |
| `/api/v1/logs` | GET | Last 100 agent logs |
| `/api/v1/analytics/trade-stats` | GET | Trade statistics |
| `/api/v1/analytics/blind-spots` | GET | Active blind spots |
| `/api/v1/analytics/adjustments` | GET | Strategy adjustments |
| `/api/v1/analytics/health` | GET | Analytics health |
| `/api/v1/strategy/trigger` | POST | Manual strategy cycle |
| `/api/v1/bot/trigger` | POST | Manual bot cycle |

## Investor Clearing

Робот самостоятельно ведёт расчёты с инвесторами на основе собственной статистики:

1. **Доля инвестора** = баланс / суммарный внесённый капитал пула.
2. **Атрибутированный P&L** = доля × реализованный P&L пула (реальные закрытые сделки).
3. **Прогнозная компонента** = баланс × ожидаемая доходность на дни до вывода
   (из `ProfitForecastService` — дневная доходность закрытых сделок, 95% ДИ, годовая ×252).
4. **Сумма вывода** = баланс + атрибутированный P&L + прогнозная компонента.

Исполнение клиринга фиксируется транзакцией `CLEARING` в `investor_transactions`.

## LLM Providers

Активный провайдер переключается через UI (Settings) или env:

| Provider | Default base URL | Default model |
|----------|------------------|---------------|
| `ROUTER_AI` | `https://routerai.ru/api/v1` | `auto` (авто-роутинг) |
| `KIMI` | `https://api.moonshot.cn/v1` | `kimi-k3` |
| `DEEPSEEK` | `https://api.deepseek.com/v1` | `deepseek-chat` |
| `QWEN` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |

Приоритет настроек: значения из UI (перезаписывают env). Клиент устойчив
(Circuit Breaker / Rate Limiter / Retry / очередь запросов / semantic cache / fallback).

## Multi-timeframe

Каждая стратегия хранит свой `timeframe` (`MINUTE_10`, `HOUR_1`, `DAY_1`).
Активный набор таймфреймов управляется через настройки (`settings.timeframes`),
fallback: `trading.timeframes` → `trading.timeframe`.

## Database Schema

Managed by Liquibase. Tables:
- `positions` — open/closed trades
- `strategies` — generated strategies (with `timeframe`)
- `candles` — market data
- `agent_logs` — agent decisions
- `blind_spots` — detected patterns
- `strategy_adjustments` — parameter adjustments
- `investors`, `investor_accounts`, `investor_transactions`, `investor_allocations` — инвесторы и клиринг
- `bot_settings` — персистентные настройки бота (JSON-блоб по ключу `global`)

## Configuration

See `application.yml` for all options. Key env vars:

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | trading_bot | Database name |
| `DB_USER` | trader | DB username |
| `DB_PASS` | trader | DB password |
| `REDIS_HOST` | localhost | Redis host |
| `REDIS_PORT` | 6379 | Redis port |
| `TRADING_MODE` | SIMULATION | SIMULATION or LIVE |
| `ALOR_TOKEN` | — | Alor API token |
| `LLM_PROVIDER` | ROUTER_AI | ROUTER_AI, KIMI, DEEPSEEK, QWEN |
| `LLM_API_KEY` | — | API key активного LLM-провайдера |
| `ROUTER_AI_BASE_URL` | https://routerai.ru/api/v1 | RouterAI endpoint |
| `ROUTER_AI_MODEL` | auto | RouterAI model |
| `KIMI_BASE_URL` / `KIMI_MODEL` | moonshot.cn / kimi-k3 | Kimi endpoint |
| `DEEPSEEK_BASE_URL` / `DEEPSEEK_MODEL` | deepseek.com / deepseek-chat | DeepSeek endpoint |
| `QWEN_BASE_URL` / `QWEN_MODEL` | dashscope / qwen-plus | Qwen endpoint |
| `AUTH_USER` | admin | Basic Auth username |
| `AUTH_PASSWORD` | change-me-now | Basic Auth password |
| `ANALYTICS_USER` | analytics | Аналитик (только просмотр) |
| `ANALYTICS_PASSWORD` | analytics-view-only | Пароль аналитика |

## Risk Management

- **Half-Kelly sizing** (`risk.kelly-fraction=0.5`): the Kelly criterion result is
  multiplied by 0.5 (Half-Kelly) before being capped at 50% of the max position.
  Full Kelly is too aggressive on real markets; use 0.25 for Quarter-Kelly.
- **LLM guardrails**: agent outputs are clamped to safe ranges before use
  (e.g. confidence adjustment in [-0.20, +0.20], SL/TP adjustment in [-0.30, +0.30]),
  NaN/Infinity collapse to 0. Signals also pass through `Guardrails`
  (daily loss limit, price deviation, confidence threshold).
- **Daily loss circuit breaker**: when the daily P&L reaches `max-daily-loss-rub`,
  the bot halts new entries until the next day.
- **Единый флаг торговли** (`/api/v1/trading/enable|disable`) блокирует все входы
  на уровне `TradingGate` — как для акций, так и для фьючерсов.
- **Force close**: мгновенный (`/api/v1/trading/force-close`) или по времени
  (`forceCloseTime` + `forceCloseEnabled`, проверка каждую минуту, Europe/Moscow).

## CI/CD

`.github/workflows/ci.yml` runs on every push/PR:
- `./gradlew ktlintCheck` (lint, baseline in `config/ktlint/baseline.xml`)
- `./gradlew test` (unit + Testcontainers integration tests)
- `./gradlew koverVerify` (coverage gate, min 50%, `koverReport` is uploaded as artifact)
- `npm run test` + `npm run build` (Vitest + TypeScript/Vite frontend)

Dеплой — job `deploy` в том же `ci.yml`, после merge в `main`/`master` (и только если
прошли все тесты, `needs: [backend, frontend]`):
1. Образы backend (`trading-bot-app`) и frontend (`trading-bot-frontend`) собираются
   и пушатся в **Yandex Container Registry** (`cr.yandex`).
2. По SSH на VM (`/opt/trading-bot`) пишется `.env` с runtime-секретами, затем
   `docker compose -f docker-compose.yml -f docker-compose.prod.yml pull && up -d`.

Требуемые GitHub Secrets:
- `YC_FOLDER_ID`, `YC_REGISTRY_ID`, `YC_SA_JSON` (ключ сервисного аккаунта с правами на YCR)
- `VM_HOST`, `VM_USER`, `VM_SSH_KEY` (SSH-доступ к VM)
- `AUTH_USER`, `AUTH_PASSWORD`, `ANALYTICS_USER`, `ANALYTICS_PASSWORD`
- `ALOR_TOKEN`, `ALOR_REFRESH_TOKEN`, `LLM_PROVIDER`, `LLM_API_KEY`, `TRADING_MODE`

## Monitoring

Prometheus metrics exposed at `/actuator/prometheus`:
- `strategy.cycle` — strategy cycles
- `bot.cycle` — bot cycles
- `bot.position.opened` — opened positions
- `bot.position.closed` — closed positions
- `adaptive.position_size` — Kelly position size
- `feedback.cache.hit` — feedback cache hits
- `llm.latency`, `llm.tokens.used`, `llm.fallback.activated` — LLM-метрики
- `trading.control.force_close`, `trading.control.toggle` — управление торговлей

## License

MIT
