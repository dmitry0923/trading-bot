# 7. API интерфейс

Все REST-endpoints находятся в `ApiController` (`/api/v1`). Корень `@CrossOrigin(origins = ["*"])`. Ответы — JSON.

> **Авторизация**: self-issued JWT (Spring Security resource server). Все `/api/v1/*` требуют `Authorization: Bearer <accessToken>`; роли: `ADMIN` (полный доступ), `ANALYTICS` (только чтение). Публичны только `/actuator/health` и `/api/v1/auth/login`. Endpoint'ы `/actuator/prometheus` закрыты отдельным Bearer-токеном `METRICS_SCRAPE_TOKEN` (см. `ScrapeTokenFilter`).

## 7.1. Auth

Access-токен выдаётся на 15 мин, refresh — на 30 дней (httpOnly cookie). Refresh ротируется при каждом использовании; повторное использование ротированного токена отзывает всю сессию.

| Метод | Path | Назначение |
|---|---|---|
| POST | `/api/v1/auth/login` | вход, выдаёт `accessToken` (JSON) + `refreshToken` (httpOnly cookie) |
| POST | `/api/v1/auth/refresh` | ротация пары по refresh-cookie |
| POST | `/api/v1/auth/logout` | отзыв refresh-токена |

**POST /api/v1/auth/login**:

```bash
curl -c cookies.txt -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<AUTH_PASSWORD>"}'
# → 200 {"accessToken":"<jwt>"}; refresh-токен сохранён в cookies.txt (httpOnly)
```

**POST /api/v1/auth/refresh**:

```bash
curl -b cookies.txt -c cookies.txt -X POST http://localhost:8080/api/v1/auth/refresh
# → 200 {"accessToken":"<new-jwt>"}; refresh-cookie ротирован
```

**POST /api/v1/auth/logout** — отзывает refresh-токен:

```bash
curl -b cookies.txt -c cookies.txt -X POST http://localhost:8080/api/v1/auth/logout
```

Неверные креды → `401 {"error":"invalid_credentials"}`; просроченный/невалидный access → `401` от resource server.

## 7.2. Сводная таблица

| Метод | Path | Назначение | Статус |
|---|---|---|---|
| GET | `/api/v1/settings` | текущие настройки бота | ✅ |
| POST | `/api/v1/settings` | обновить настройки | ✅ |
| GET | `/api/v1/strategies` | последние 50 стратегий | ✅ |
| GET | `/api/v1/strategies/{ticker}` | стратегия по тикеру (Redis → БД) | ✅ |
| GET | `/api/v1/positions` | открытые позиции | ✅ |
| GET | `/api/v1/positions/all` | все позиции | ✅ |
| GET | `/api/v1/logs` | последние 100 agent-логов | ✅ |
| GET | `/api/v1/risk/daily-pnl` | текущий дневной P&L | ✅ |
| POST | `/api/v1/strategy/trigger` | ручной запуск цикла стратегий | ✅ |
| POST | `/api/v1/bot/trigger` | ручной запуск бот-цикла | ✅ |
| GET | `/api/v1/analytics/trade-stats` | статистика сделок за N дней | ✅ |
| GET | `/api/v1/analytics/adaptive-params/{ticker}` | адаптивные параметры | ✅ |
| GET | `/api/v1/analytics/blind-spots` | активные слепые зоны | ✅ |
| GET | `/api/v1/analytics/adjustments` | история корректировок | ✅ |
| GET | `/api/v1/analytics/time-pattern/{ticker}` | win rate по часам | ✅ |
| GET | `/api/v1/analytics/health` | health аналитики | ✅ |
| GET | `/api/v1/backtest/{ticker}` | бэктест тикера за N дней | ✅ |
| POST | `/api/v1/bot/emergency-stop` | аварийная остановка | 🔜 запланирован |
| GET | `/actuator/health` | health Spring Boot | ✅ |
| GET | `/actuator/prometheus` | метрики Prometheus | ✅ |

## 7.2. Спецификация endpoint'ов

### GET /api/v1/settings

Возвращает текущие настройки.

**Response 200**:
```json
{
  "tradingEnabled": true,
  "riskEnabled": true,
  "maxPositionRub": 500000,
  "maxDailyLossRub": 50000
}
```

### POST /api/v1/settings

Обновляет настройки (хранятся в памяти `SettingsService`).

**Request body**:
```json
{
  "tradingEnabled": true,
  "riskEnabled": true,
  "maxPositionRub": 300000,
  "maxDailyLossRub": 20000
}
```

**Response 200**: те же поля, что в запросе.

### GET /api/v1/strategies

Последние 50 стратегий (`strategyRepository.findTop50ByOrderByCreatedAtDesc`).

**Response 200**:
```json
[
  {
    "id": 12,
    "ticker": "SBER",
    "action": "BUY",
    "targetPrice": 280.5,
    "quantity": 10,
    "stopLoss": 274.9,
    "takeProfit": 291.7,
    "trailingStop": false,
    "confidence": 0.68,
    "reasoning": "... | Meta: confAdj=0.0, SL/TP adapted, atr=2.1",
    "rawJson": "{...}",
    "cycleId": "8f1c...",
    "validUntil": "2026-08-03T10:10:00",
    "createdAt": "2026-08-03T10:00:00"
  }
]
```

### GET /api/v1/strategies/{ticker}

Сначала Redis (`strategy:<ticker>`, TTL 15 мин), затем последняя из БД.

**Response 200**: объект `Strategy` (как выше). **404**: отсутствует (возвращается пустой объект при отсутствии — Spring вернёт null).

### GET /api/v1/positions

Только OPEN.

**Response 200**:
```json
[
  {
    "id": 3,
    "ticker": "SBER",
    "direction": "LONG",
    "quantity": 10,
    "entryPrice": 280.5,
    "currentPrice": 281.3,
    "closePrice": null,
    "stopLoss": 274.9,
    "takeProfit": 291.7,
    "trailingStopPrice": 274.9,
    "pnl": 8.0,
    "status": "OPEN",
    "alorOrderId": "100500",
    "closeReason": null,
    "openedAt": "2026-08-03T09:30:00",
    "closedAt": null
  }
]
```

### GET /api/v1/positions/all

Все позиции, включая закрытые.

### GET /api/v1/logs

Последние 100 `agent_logs` (для отладки конвейера).

**Response 200**:
```json
[
  {
    "id": 555,
    "cycleId": "8f1c...",
    "agentName": "Agent-5-Arbitrator",
    "ticker": "SBER",
    "action": "BUY",
    "confidence": 0.68,
    "reasoning": "...",
    "rawOutput": "{\"action\":\"BUY\",...}",
    "latencyMs": 1240,
    "tokensUsed": 2200,
    "isCached": false,
    "overrideReason": null,
    "createdAt": "2026-08-03T10:00:03"
  }
]
```

### GET /api/v1/risk/daily-pnl

**Response 200**: `{"dailyPnl": -1250.0}` (BigDecimal).

### POST /api/v1/strategy/trigger

Ручной запуск `StrategyService.runStrategyCycle()`. Метрика `api.trigger.strategy`.

**Response 200**: 200 OK (пустое тело).

> Цикл выполняется в фоне (`scope.launch`), HTTP не блокируется.

### POST /api/v1/bot/trigger

Ручной запуск `TradingBotService.runBotCycle()`. Метрика `api.trigger.bot`.

**Response 200**: 200 OK.

### GET /api/v1/analytics/trade-stats?days=14

**Query**: `days` (default 14).

**Response 200**: `Map<String, TradeStats>` по тикерам:
```json
{
  "SBER": {
    "ticker": "SBER",
    "totalTrades": 23,
    "winningTrades": 14,
    "losingTrades": 9,
    "winRate": 0.6087,
    "avgWin": 1450.0,
    "avgLoss": 810.0,
    "profitFactor": 2.06,
    "maxConsecutiveLosses": 3,
    "avgHoldTimeMinutes": 95,
    "slHitRate": 0.39,
    "tpHitRate": 0.26,
    "strategyCloseRate": 0.22,
    "bestEntryHour": 11,
    "worstEntryHour": 14,
    "blindSpots": [
      {
        "conditionPattern": "Stop-Loss hit rate > 60% for SBER",
        "lossRate": 0.67,
        "occurrenceCount": 6,
        "recommendation": "Increase ATR multiplier for stop-loss or review entry points"
      }
    ]
  }
}
```

Пустой ответ: `{}`.

### GET /api/v1/analytics/adaptive-params/{ticker}

**Response 200**:
```json
{
  "ticker": "SBER",
  "confidenceThreshold": 0.6,
  "maxPositionRub": 250000.0,
  "isInRecovery": false,
  "shouldPause": false
}
```

### GET /api/v1/analytics/blind-spots

**Response 200**: `List<BlindSpotEntity>` (только active):
```json
[
  {
    "id": 4,
    "ticker": "SBER",
    "conditionPattern": "Stop-Loss hit rate > 60% for SBER",
    "lossRate": 0.67,
    "occurrenceCount": 6,
    "recommendation": "Increase ATR multiplier for stop-loss or review entry points",
    "isActive": true,
    "detectedAt": "2026-08-01T18:00:00",
    "resolvedAt": null
  }
]
```

### GET /api/v1/analytics/adjustments?ticker=SBER

**Query**: `ticker` (необязателен).

**Response 200**:
```json
[
  {
    "id": 7,
    "ticker": "SBER",
    "adjustmentType": "CONFIDENCE",
    "oldValue": 0.0,
    "newValue": 0.15,
    "triggeredBy": "META_AGENT",
    "reason": "Win Rate 33%, PF 0.82",
    "createdAt": "2026-08-03T10:00:00"
  }
]
```

### GET /api/v1/analytics/time-pattern/{ticker}?days=30

**Response 200**:
```json
{
  "ticker": "SBER",
  "hourlyWinRates": {
    "10": 0.5,
    "11": 0.75,
    "14": 0.2,
    "18": 0.6
  }
}
```

### GET /api/v1/analytics/health

Сводный health адаптивной системы.

**Response 200**:
```json
{
  "totalTickersAnalyzed": 10,
  "totalTradesLast7Days": 31,
  "averageWinRate": "54.31%",
  "pausedTickers": ["NVTK"],
  "timestamp": "2026-08-03T10:05:12"
}
```

`pausedTickers` — тикеры с `maxConsecutiveLosses >= 4`.

### GET /api/v1/backtest/{ticker}?days=365

Запускает `BacktestEngine.run(ticker, days)` (см. раздел 11) на исторических свечах из PostgreSQL. Метрика `api.backtest` (тег `ticker`).

**Query**: `days` (default 365). Исполнение синхронное (прогон по свечам, обычно секунды).

**Response 200**:
```json
{
  "ticker": "SBER",
  "totalReturn": 0.1234,
  "sharpeRatio": 1.41,
  "maxDrawdown": 0.081,
  "winRate": 0.5421,
  "profitFactor": 1.87,
  "totalTrades": 152,
  "avgHoldBars": 0.0,
  "equityCurve": [100000, 100320.5, 100150.2, 101005.7],
  "monthlyReturns": {}
}
```

При недостатке свечей (< 32) возвращаются нулевые метрики, `totalTrades = 0`.

### POST /api/v1/bot/emergency-stop (запланирован)

> **Статус**: в текущей версии endpoint отсутствует. Проект (раздел 5.8):

**Request body**: `{"reason": "manual", "liquidate": true}` (liquidate — закрыть ли позиции).

**Response 200**: `{"stopped": true, "positionsLiquidated": 2}`.

## 7.3. Ошибки

Spring Boot DefaultErrorAttributes:

**404 — не найдено**:
```json
{
  "timestamp": "2026-08-03T10:05:12.123Z",
  "status": 404,
  "error": "Not Found",
  "path": "/api/v1/unknown"
}
```

**500 — внутренняя ошибка**:
```json
{
  "timestamp": "2026-08-03T10:05:12.123Z",
  "status": 500,
  "error": "Internal Server Error",
  "path": "/api/v1/positions"
}
```

**400 — невалидное тело** (POST /settings с плохим JSON): стандартный 400 от Spring.

## 7.4. Actuator

| Endpoint | Описание |
|---|---|
| `/actuator/health` | статус приложения (UP/DOWN), включает health-circuitbreaker (CB `llm`) |
| `/actuator/prometheus` | метрики в формате Prometheus (Micrometer) |
| `/actuator/metrics`, `/actuator/info` | также доступны |

Прометей-эндпоинт включён: `management.endpoints.web.exposure.include: health,info,metrics,prometheus` и защищён Bearer-токеном `METRICS_SCRAPE_TOKEN`.

## 7.5. Примеры curl

```bash
AUTH_URL=http://localhost:8080/api/v1/auth

# Вход → токены (admin или analytics)
TOKEN=$(curl -s -X POST "$AUTH_URL/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"'"$AUTH_USER"'","password":"'"$AUTH_PASSWORD"'"}' | jq -r .accessToken)
AUTH="Authorization: Bearer $TOKEN"

# Открытые позиции
curl -H "$AUTH" http://localhost:8080/api/v1/positions

# Статистика за 30 дней
curl -H "$AUTH" "http://localhost:8080/api/v1/analytics/trade-stats?days=30"

# Ручной цикл стратегий
curl -H "$AUTH" -X POST http://localhost:8080/api/v1/strategy/trigger

# Дневной P&L
curl -H "$AUTH" http://localhost:8080/api/v1/risk/daily-pnl

# Бэктест SBER за год
curl -H "$AUTH" "http://localhost:8080/api/v1/backtest/SBER?days=365"

# Метрики (отдельный Bearer-токен Prometheus)
curl -H "Authorization: Bearer $METRICS_SCRAPE_TOKEN" http://localhost:8080/actuator/prometheus | grep -E "llm_|bot_|strategy_"
```
