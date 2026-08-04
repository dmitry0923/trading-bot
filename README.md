# MMVB Trading Bot v2

Production-ready trading bot for Moscow Exchange (MOEX) with AI-driven strategy optimization.

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
KIMI_API_KEY=your_kimi_api_key
TRADING_MODE=SIMULATION
```

### 3. Run with Docker Compose

```bash
docker-compose up -d
```

Services:
- App: http://localhost:8080
- Prometheus metrics: http://localhost:8080/actuator/prometheus
- Health check: http://localhost:8080/actuator/health

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
| `ALOR_TOKEN` | — | Alor API token |
| `KIMI_API_KEY` | — | Kimi LLM API key |

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
