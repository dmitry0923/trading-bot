# MMVB AI Trading Bot v2 — Architecture (Production-Grade Platform)

> **Статус стратегии: RESEARCH_ONLY.** Платформа (ордерная архитектура, риск-контур,
> LIVE-guard) — production-grade (по аудиту ~90–92%), но торговая стратегия CNYRUBF
> **не одобрена для LIVE/PAPER** (валидация 2026-09-09, полная история 365д: WFA OOS
> убыточен, consistency 0.5, сделок 20 < 100). Бот работает в research-режиме
> paper; LIVE-входы блокируются `LIVE_TICKERS_ALLOWLIST` (fail-closed).

## LLM как источник сигнала (research, дефолт off)

> Аудит, дизайн и план — `docs/17-llm-signal-source.md`; ветка `research/llm-signal-source`.
> В production флаги дефолтно выключены — детерминированный сигнал не меняется.

Полная LLM-цепочка (tech→fund→strategy→contrarian→arbitrator, `LlmSignalStrategy`) может
участвовать в конкурентном выборе сигнала как **источник решения**, но не как «руки»
(риск-паритет: `StrategyDecision` несёт только action/target/уверенность, qty/SL/TP
назначает OrderBuilder + риск-слой):

| Флаг (env) | Смысл | Fail-closed |
|---|---|---|
| `trading.llm-signal-source` (`TRADING_LLM_SIGNAL_SOURCE`) | LLM в конкуренции за сигнал | LLM недоступен / бюджет / ошибка → HOLD |
| `trading.llm-signal-only` (`TRADING_LLM_SIGNAL_ONLY`) | только LLM-источник (дет. отключаются) | вводится только после A/B против базлайна |
| `trading.llm-signal-shadow` (`TRADING_LLM_SIGNAL_SHADOW`) | LLM-победитель логируется, НЕ исполняется | исполнение остаётся детерминированным |
| `trading.llm-signal-budget-ms` (`TRADING_LLM_SIGNAL_BUDGET_MS`) | жёсткий бюджет цепочки (2000 мс) | превышение → HOLD (`llm.signal.timeout`) |

**Shadow-режим (этап 5, 2026-09-14)**: победа LLM фиксируется в `agent_logs` → Strategy →
lineage (`GET /api/v1/lineage/{cycleId}`), но сигнал НЕ публикуется в order-admission и НЕ
пишется в Redis «последняя стратегия» — вход исполняется только детерминированной
стратегией (метрика `llm.signal.shadow{ticker,strategy}`). Это A/B-наблюдение LLM-winner
vs базлайн на 30д до включения `llm-signal-only`.

**Реализовано на 2026-09-14 (этапы 1–5)**. Блокер: этап 6 (smoke-бэктест «строго LLM»,
`bt.agent.live-strategies=false`) ждёт реальный `LLM_API_KEY` — без ключа агенты на
детерминированных fallback, прогон валиден только как smoke (docs/17 §17.7).

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
cd frontend && npm install && npm run dev
```

## Проверка перед продом

```bash
# 1. Запустить тесты
./gradlew test --tests "com.trading.bot.integration.SelfLearningIntegrationTest"

# 2. Получить JWT (креды из env, без дефолтов)
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"'"$AUTH_USER"'","password":"'"$AUTH_PASSWORD"'"}' | jq -r .accessToken)
AUTH="Authorization: Bearer $TOKEN"

# 3. Проверить метрики (отдельный Bearer-токен Prometheus)
curl -H "Authorization: Bearer $METRICS_SCRAPE_TOKEN" http://localhost:8080/actuator/prometheus | grep adaptive

# 4. Проверить аналитику
curl -H "$AUTH" http://localhost:8080/api/v1/analytics/health

# 5. SIMULATION режим минимум 1 неделю
export TRADING_MODE=SIMULATION
export AUTH_USER=admin AUTH_PASSWORD='<strong>' JWT_SECRET='<32+ random bytes>'
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
