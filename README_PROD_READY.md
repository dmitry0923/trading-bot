# MMVB AI Trading Bot v2 — Self-Learning (Production Ready)

## Что нового в v2

### 🧠 Self-Learning Engine
- **TradeAnalysisService** — анализирует все закрытые сделки, считает Win Rate, Profit Factor, Sharpe, выявляет временные паттерны
- **PerformanceFeedbackAgent (Agent 8)** — LLM-агент, который анализирует статистику и выдаёт корректировки для агентов 1-5
- **AdaptiveRiskService** — динамический риск-менеджмент:
  - Kelly Criterion для оптимального размера позиции
  - ATR-based адаптивные стоп-лоссы и тейк-профиты
  - Динамический порог confidence (0.55–0.80)
  - Автопауза при серии убытков ≥ 4

### 📊 Метрики и мониторинг
- Micrometer + Prometheus endpoint (`/actuator/prometheus`)
- Кастомные метрики: `bot.position.opened`, `strategy.cycle`, `adaptive.pause`, `feedback.cache.hit`

### 🎨 React UI
- Новая вкладка «Аналитика» с:
  - Heatmap статистики по тикерам
  - Слепые зоны (blind spots)
  - История корректировок
  - Health check адаптивной системы

## Быстрый старт

### 1. Миграция БД
```bash
psql -U trader -d trading_bot -f scripts/migration_v2_selflearning.sql
```

### 2. Скопировать файлы
```bash
# Kotlin backend
cp src/main/kotlin/com/trading/bot/model/*.kt your-project/src/main/kotlin/com/trading/bot/model/
cp src/main/kotlin/com/trading/bot/repository/*.kt your-project/src/main/kotlin/com/trading/bot/repository/
cp src/main/kotlin/com/trading/bot/service/*.kt your-project/src/main/kotlin/com/trading/bot/service/
cp src/main/kotlin/com/trading/bot/agent/*.kt your-project/src/main/kotlin/com/trading/bot/agent/
cp src/main/kotlin/com/trading/bot/controller/*.kt your-project/src/main/kotlin/com/trading/bot/controller/

# React frontend
cp frontend/src/pages/AnalyticsPage.js your-project/frontend/src/pages/
cp frontend/src/App.js your-project/frontend/src/

# Configs
cp src/main/resources/application.yml your-project/src/main/resources/
cp build.gradle.kts your-project/
```

### 3. Пересобрать
```bash
./gradlew bootRun
```

### 4. UI
```bash
cd frontend && npm ci && npm start
```

## Проверка перед продом

```bash
# 1. Запустить тесты
./gradlew test --tests "com.trading.bot.integration.SelfLearningIntegrationTest"

# 2. Проверить метрики
curl http://localhost:8080/actuator/prometheus | grep adaptive

# 3. Проверить аналитику
curl http://localhost:8080/api/v1/analytics/health

# 4. SIMULATION режим минимум 1 неделю
export TRADING_MODE=SIMULATION
./gradlew bootRun
```

## API Endpoints

| Endpoint | Описание |
|---|---|
| `GET /api/v1/analytics/trade-stats?days=14` | Статистика по всем тикерам |
| `GET /api/v1/analytics/adaptive-params/{ticker}` | Адаптивные параметры |
| `GET /api/v1/analytics/blind-spots` | Активные слепые зоны |
| `GET /api/v1/analytics/adjustments` | История корректировок |
| `GET /api/v1/analytics/time-pattern/{ticker}` | Win Rate по часам |
| `GET /api/v1/analytics/health` | Health check системы |
| `GET /actuator/prometheus` | Prometheus метрики |

## Архитектура обратной связи

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Закрытые       │────→│ TradeAnalysis    │────→│ AdaptiveRisk    │
│  позиции (БД)   │     │ Service          │     │ Service         │
└─────────────────┘     └──────────────────┘     └─────────────────┘
                              │                           │
                              ▼                           ▼
                       ┌──────────────────┐     ┌─────────────────┐
                       │ Performance      │     │ Kelly Criterion │
                       │ FeedbackAgent    │     │ ATR-based SL/TP │
                       │ (LLM Meta-Agent) │     │ Dynamic conf    │
                       └──────────────────┘     └─────────────────┘
                              │
                              ▼
                       ┌──────────────────┐
                       │ Redis Cache      │
                       │ (feedback TTL    │
                       │  60 min)         │
                       └──────────────────┘
                              │
                              ▼
                       ┌──────────────────┐
                       │ StrategyService  │
                       │ (adaptive params │
                       │  injected)       │
                       └──────────────────┘
```

## Graceful Degradation

- Если LLM недоступен — используются дефолтные параметры, торговля не останавливается
- Если Redis недоступен — feedback генерируется каждый цикл (без кэша)
- Если аналитика пуста (нет сделок) — используются базовые параметры из `application.yml`

## Лицензия
MIT License
