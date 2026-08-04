# MMVB Trading Bot v2

Production-ready trading bot for Moscow Exchange (MOEX) with AI-driven strategy optimization.

## Architecture

- **Kotlin 1.9.21** + **Spring Boot 3.2.0**
- **R2DBC** (reactive `DatabaseClient`, all repositories `suspend`) + **Liquibase** migrations (JDBC)
- **PostgreSQL** for persistence
- **Redis** for caching
- **Micrometer** + **Prometheus** metrics
- **Docker** + **Docker Compose**
- **React 18** + **TypeScript** + **Vite** dashboard

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
KIMI_API_KEY=your_kimi_api_key
TRADING_MODE=SIMULATION
# Security (Basic Auth for API and Actuator)
AUTH_USER=admin
AUTH_PASSWORD=change-me-now
```

> **Important**: set a strong `AUTH_PASSWORD` before exposing the bot outside
> localhost. The API (`/api/v1/**`) and Actuator endpoints are protected with
> Spring Security Basic Auth; only `/actuator/health` is public (Docker healthcheck).
> In `LIVE` mode startup is rejected when the password is default or shorter than
> 12 characters. The dashboard asks for credentials at runtime and never embeds
> them into the public JavaScript bundle.

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
| `/api/v1/settings` | GET/POST | Bot settings |
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

## Database Schema

Managed by Liquibase. Tables:
- `positions` — open/closed trades
- `strategies` — generated strategies
- `candles` — market data
- `agent_logs` — agent decisions
- `blind_spots` — detected patterns
- `strategy_adjustments` — parameter adjustments

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
| `MAX_OPEN_POS` | 0 in Docker Compose | New-entry kill switch / spot-position cap |
| `MONITOR_INTERVAL_MS` | 10000 | Quote fallback interval and WS staleness threshold |
| `ALOR_TOKEN` | — | Alor API token |
| `KIMI_API_KEY` | — | Kimi LLM API key |
| `AUTH_USER` | admin | Basic Auth username |
| `AUTH_PASSWORD` | change-me-now | Basic Auth password |

## Risk Management

- **Half-Kelly sizing** (`risk.kelly-fraction=0.5`): the Kelly criterion result is
  multiplied by 0.5 (Half-Kelly) before being capped at 50% of the max position.
  Full Kelly is too aggressive on real markets; use 0.25 for Quarter-Kelly.
- **LLM guardrails**: agent outputs are clamped to safe ranges before use
  (e.g. confidence adjustment in [-0.20, +0.20], SL/TP adjustment in [-0.30, +0.30]),
  NaN/Infinity collapse to 0. Signals also pass through `Guardrails`
  (daily loss limit, price deviation, confidence threshold).
- **Daily loss circuit breaker**: one thread-safe daily P&L ledger is shared by
  stocks and futures. When it reaches `max-daily-loss-rub`, the bot halts new
  entries until the next day and restores the state after restart.
- **Runtime settings**: the Settings page immediately controls the trading kill
  switch and risk limits. Risk management cannot be disabled while trading is on.

## CI/CD

`.github/workflows/ci.yml` runs on every push/PR:
- `./gradlew ktlintCheck` (lint, baseline in `config/ktlint/baseline.xml`)
- `./gradlew test` (unit + Testcontainers integration tests)
- `npm run build` (TypeScript + Vite frontend)
- `npm audit` reports zero known frontend vulnerabilities

## Monitoring

Prometheus metrics exposed at `/actuator/prometheus`:
- `strategy.cycle` — strategy cycles
- `bot.cycle` — bot cycles
- `bot.position.opened` — opened positions
- `bot.position.closed` — closed positions
- `adaptive.position_size` — Kelly position size
- `feedback.cache.hit` — feedback cache hits

## License

MIT
