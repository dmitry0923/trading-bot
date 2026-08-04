# 9. Мониторинг и алертинг

## 9.1. Prometheus метрики

Бот экспортирует метрики через Micrometer на `/actuator/prometheus`. Все метрики имеют label `application="mmvb-trading-bot-v2"`.

### Кастомные метрики (полный перечень)

#### Бот (торговля)

| Метрика | Тип | Labels | Описание |
|---|---|---|---|
| `bot.cycle` | Counter | — | количество циклов бота |
| `bot.cycle.error` | Counter | — | ошибки цикла |
| `bot.halted.daily_loss` | Counter | — | остановка из-за дневного лимита |
| `bot.risk.reject` | Counter | `ticker` | отклонение RiskEngine при входе |
| `bot.order.failed` | Counter | `ticker` | неудача ордера при открытии |
| `bot.position.opened` | Counter | `ticker, direction` | открытые позиции |
| `bot.position.closed` | Counter | `ticker, reason` | закрытые позиции (reason: STOP_LOSS/TAKE_PROFIT/TRAILING_STOP/STRATEGY_CLOSE/EXECUTION_FILL) |
| `bot.pnl` | Gauge | `ticker` | P&L последней закрытой позиции |
| `bot.monitor.error` | Counter | `ticker` | ошибки мониторинга позиций |
| `bot.ws.fill_applied` | Counter | `ticker` | сколько раз WS-fill применён к позиции |

#### Стратегии

| Метрика | Тип | Labels | Описание |
|---|---|---|---|
| `strategy.cycle` | Counter | — | запущенные циклы стратегий |
| `strategy.pause` | Counter | `ticker` | meta-агент рекомендовал паузу |
| `strategy.skipped` | Counter | `ticker` | тикер пропущен (пауза) |
| `strategy.saved` | Counter | `ticker, action` | сохранённые стратегии |
| `strategy.error` | Counter | `ticker` | ошибки цикла по тикеру |
| `strategy.feedback.error` | Counter | `ticker` | ошибки генерации feedback |
| `strategy.agent.parse.error` | Counter | `ticker` | parse-ошибки ответа StrategyAgent |

#### Агенты

| Метрика | Тип | Labels | Описание |
|---|---|---|---|
| `agent.technical.decision` | Counter | `action` | вердикты теха (BULLISH/BEARISH/NEUTRAL/INSUFFICIENT_DATA) |
| `agent.fundamental.decision` | Counter | `action` | вердикты фундамента |
| `agent.strategy.decision` | Counter | `action` | draft-решения (BUY/SELL/HOLD) |
| `agent.contrarian.decision` | Counter | `riskLevel` | риски контрариана |
| `agent.arbitrator.decision` | Counter | `action` | финальные решения |
| `agent.arbitrator.parse.error` | Counter | — | parse-ошибки арбитра |
| `arbitrator.deterministic.override` | Counter | `reason` | детерминированные overrides (CRITICAL_CHALLENGE, RISK_CONTEXT_PAUSE, DAILY_LOSS_LIMIT, LOW_DRAFT_CONFIDENCE, RISK_CRITICAL, LOW_CONFIDENCE, ZERO_QUANTITY, PRICE_DEVIATION) |

#### Feedback / адаптивный риск

| Метрика | Тип | Labels | Описание |
|---|---|---|---|
| `feedback.rule_based` | Counter | `ticker, reason` | rule-based feedback вместо LLM |
| `feedback.cache.hit` / `feedback.cache.miss` | Counter | `ticker` | кэш feedback |
| `feedback.llm.error` | Counter | `ticker` | ошибки LLM в feedback |
| `adaptive.position_size` | Gauge | `ticker` | Kelly-размер позиции |
| `adaptive.drawdown_recovery` | Gauge | — | 1.0 если recovery-режим |
| `adaptive.pause` | Gauge | `ticker` | 1.0 если пауза |

#### LLM

| Метрика | Тип | Labels | Описание |
|---|---|---|---|
| `llm.latency` | Timer | `agent` | латентность LLM-вызовов |
| `llm.tokens.used` | Counter | `agent, model` | суммарные токены (мониторинг стоимости) |
| `llm.fallback.activated` | Counter | `agent, reason` | fallback (NO_API_KEY, CALL_ERROR) |
| `llm.cache.hit` / `llm.cache.miss` / `llm.cache.error` | Counter | `agent` | семантический кэш |

#### Alor

| Метрика | Тип | Labels | Описание |
|---|---|---|---|
| `alor.api.latency` | Timer | `operation` | латентность вызовов (getQuotes/placeLimitOrder/verifyOrder) |
| `alor.quotes.ok` / `alor.quotes.error` | Counter | `ticker` | успех/ошибка получения котировок |
| `alor.order.placed` | Counter | `type, status` | размещённые ордера |
| `alor.order.error` | Counter | `side, type` | ошибки ордеров |
| `alor.order.blocked` | Counter | `reason` | заблокированные (WIDE_SPREAD) |
| `alor.ws.execution_received` | Counter | — | принятые WS-исполнения |
| `alor.ws.reconnect` | Counter | — | переподключения WS |
| `alor.ws.error` / `alor.ws.closed` | Counter | — | ошибки/закрытия WS |
| `alor.ws.disconnected` | Counter | `reason` | отключение (MAX_ATTEMPTS) |

#### Outbox

| Метрика | Тип | Labels | Описание |
|---|---|---|---|
| `outbox.saved` | Counter | `type` | созданные строки outbox |
| `outbox.sent` | Counter | `type` | успешно отправленные |
| `outbox.failed` | Counter | `type` | упавшие |

#### Slippage

| Метрика | Тип | Labels | Описание |
|---|---|---|---|
| `trade.slippage.rub` | Counter | — | суммарное проскальзывание в рублях |

#### API

| Метрика | Тип | Labels |
|---|---|---|
| `api.trigger.strategy`, `api.trigger.bot` | Counter | — |
| `api.analytics.trade-stats` | Counter | — |
| `api.analytics.adaptive-params` | Counter | `ticker` |
| `api.analytics.blind-spots`, `api.analytics.adjustments` | Counter | — |
| `api.analytics.time-pattern` | Counter | `ticker` |

#### Макро

| Метрика | Тип | Labels |
|---|---|---|
| `macro.usd_rub`, `macro.cbr_rate`, `macro.brent` | Gauge | — |
| `macro.usd_rub.live` | Counter | `status` (OK/FALLBACK) |

### Примеры PromQL-запросов

```promql
# Чистый P&L по всем тикерам (gauge последних значений)
sum(bot_pnl)

# Открытые позиции за последний час
sum(increase(bot_position_opened[1h])) - sum(increase(bot_position_closed[1h]))

# Доля HOLD-решений арбитра
sum(rate(agent_arbitrator_decision{action="HOLD"}[1h]))
  / sum(rate(agent_arbitrator_decision[1h]))

# Дневной лимит убытка близок? (сигнал)
max_over_time(alert_on_daily_loss[1d])

# Стоимость LLM (токены) за 24ч
sum(increase(llm_tokens_used[24h]))

# Hit rate семантического кэша
sum(rate(llm_cache_hit[1h])) / (sum(rate(llm_cache_hit[1h])) + sum(rate(llm_cache_miss[1h])))

# Латентность LLM p95
histogram_quantile(0.95, sum(rate(llm_latency_seconds_bucket[5m])) by (le))

# Паузы стратегий
sum(rate(strategy_pause[1h]))

# WS disconnected (бот не получает исполнения)
increase(alor_ws_disconnected_total[1h])

# Проскальзывание
increase(trade_slippage_rub_total[24h])
```

## 9.2. Grafana Dashboard

### Структура (проект)

| Dashboard | Панели |
|---|---|
| **Overview** | открытые позиции, P&L, циклы/мин, ошибки циклов, аптайм |
| **Trading Performance** | win rate по тикерам (heatmap), profit factor, распределение по причинам закрытия, drawdown |
| **LLM Performance** | latency p50/p95, tokens/sec, cache hit rate, fallback rate, стоимость/день |
| **Risk** | daily PnL vs лимит, open positions vs max, paused tickers, deterministic overrides по причинам |
| **Infrastructure** | JVM heap, CPU/RAM пода, PostgreSQL connections, Redis hits |

### Ключевые панели Risk-dashboard

- **Daily PnL** — gauge `bot_pnl` суммарно + линия лимита −50 000.
- **Overrides** — stacked bar `arbitrator_deterministic_override` по `reason`.
- **Позиции** — timeseries `bot_position_opened - bot_position_closed`.

## 9.3. Alertmanager rules

### Критические (page)

| Alert | PromQL | Аннотация |
|---|---|---|
| `BotDown` | `up{job="mmvb-trading-bot"} == 0` (или `absent(up{job=...})` 5 мин) | бот не отвечает |
| `DailyLossLimitReached` | `increase(bot_halted_daily_loss_total[5m]) > 0` | достигнут дневной лимит убытка — остановлен |
| `LLMCircuitBreakerOpen` | `resilience4j_circuitbreaker_state{name="llm"} == 2` (OPEN) 5 мин | LLM недоступен, бот работает на fallback |
| `EmergencyStop` | флаг из Redis/метрики | ручной/авто emergency stop |
| `OutboxStuck` | `increase(outbox_saved_total[1h]) > 0 AND increase(outbox_sent_total[1h]) == 0` | ордера не уходят в Alor |

### Warning

| Alert | PromQL |
|---|---|
| `LLMLatencySpike` | p95(`llm_latency_seconds`) > 20 s за 15 мин |
| `LLMCacheMissHigh` | cache hit rate < 30% за 30 мин |
| `HighSlippage` | `increase(trade_slippage_rub_total[1h]) > 1000` |
| `WsReconnectLoop` | `increase(alor_ws_reconnect_total[1h]) > 10` |
| `TradingPaused` | `sum(strategy_pause) > 0` за 1ч |
| `PositionRiskExposure` | открытых позиций >= 80% от max-open-positions |
| `ConsecutiveLosses` | `feedback` статистика: maxConsecutiveLosses >= 4 |

### Каналы доставки

- **Telegram** — webhook receiver (критические + warning).
- **Email** — summary daily.
- **PagerDuty** — только критические (BotDown, DailyLossLimitReached).

## 9.4. Логирование

### Структура

Spring Boot по умолчанию пишет в консоль (лог-формат для Docker/k8s — stdout). Целевой формат — JSON через logback appender (roadmap):

```json
{"ts":"2026-08-03T10:00:03.123Z","level":"INFO","logger":"com.trading.bot.agent.ArbitratorAgent",
 "message":"Agent 5 FINAL: BUY @ 280.5 conf=0.68 override=null",
 "cycleId":"8f1c-...","ticker":"SBER"}
```

### Уровни

| Уровень | Где используется |
|---|---|
| `DEBUG` | детали агентов, HTTP-запросы, R2DBC SQL |
| `INFO` | открытие/закрытие позиций, решения арбитра, метки фаз |
| `WARN` | fallback LLM, WIDE_SPREAD, reconnect WS, ошибки обновления токена |
| `ERROR` | исключения циклов, parse-ошибки, сбои ордеров |

Текущая конфигурация: `com.trading.bot: DEBUG`, `org.springframework.r2dbc: DEBUG`.

### Correlation ID

- `cycleId` (UUID) создаётся в `StrategyService.run()` на каждый цикл стратегий и пробрасывается через все агенты в `AgentLog.cycleId`.
- Для META-агента `cycleId="META"`.
- Трассировка сделки: `cycleId` → стратегия (`strategies.cycle_id`) → позиция (по тикеру) → `positions` P&L.

### Диагностика по логам

```bash
# Все решения одного цикла
docker compose logs app | grep "8f1c-..."

# Все решения по тикеру
docker compose logs app | grep "SBER" | grep "FINAL"

# Fallback LLM
docker compose logs app | grep -i "fallback\|unavailable"
```

## 9.5. Метрики новых подсистем

### Risk guard (sector / volatility)

| Метрика | Тип | Описание |
|---|---|---|
| `risk.reject` | Counter | tag `ticker` — любое отклонение RiskEngine (включая sector concentration) |
| `risk.sector.exceeded` | Counter | tag `sector` — срабатывание секторного лимита |
| `risk.volatility.blocked` | Counter | tag `ticker` — HOLD из-за ATR% > max-volatility-percent |
| `risk.volatility.checked` | Counter | tag `ticker` — сколько раз проверена волатильность |

> Отклонение по сектору видно и в логе `WARN`: `Sector concentration exceeded: 2 open in sector FINANCE >= max 2`.

PromQL:

```promql
# Частота блокировок по секторам
sum(rate(risk_sector_exceeded[1h])) by (sector)

# Доля отказов риска к числу проверок
sum(rate(risk_reject[1h])) / sum(rate(bot_cycle[1h]))
```

### Event-driven слой

| Метрика | Тип | Описание |
|---|---|---|
| `event.published` | Counter | tag `type` (PriceChanged/StrategyGenerated/EntrySignal/ExecutionReport) |
| `event.handled` | Counter | tag `type` — количество обработок |
| `event.handler.error` | Counter | tag `type` — ошибки обработчиков |

PromQL:

```promql
# Потери событий (published != handled) — сигнал к отладке
sum(increase(event_published[1h])) by (type)
  - ignoring(type) sum(increase(event_handled[1h])) by (type)
```

### Backtest

| Метрика | Тип | Описание |
|---|---|---|
| `api.backtest` | Counter | tag `ticker` — вызовы бэктеста |
| `bt.total_trades` | Gauge | tag `ticker` — сделки последнего прогона |
| `bt.sharpe` / `bt.max_drawdown` / `bt.profit_factor` | Gauge | tag `ticker` |
| `bt.pass` | Counter | tag `ticker, result` (PASS/REJECT) |

PromQL:

```promql
# Какие тикеры прошли критерии приёма
bt_pass_total{result="PASS"}
```

## 9.6. Мониторинг P&L и рисков

### Связь метрик и реальных денег

| Вопрос | Метрика/запрос |
|---|---|
| Сколько сейчас в позициях? | `bot_position_opened_total - bot_position_closed_total` |
| Итоговый P&L | `sum(bot_pnl)` |
| Дневной P&L | `GET /api/v1/risk/daily-pnl` (в памяти) |
| Близко ли к лимиту? | gauge-прокси: `bot_halted_daily_loss_total` при срабатывании |
| Проскальзывание | `increase(trade_slippage_rub_total[24h])` |

> **Ограничение**: дневной P&L хранится в памяти сервиса (раздел 5.6), поэтому в Prometheus он не виден как timeseries. До реализации БД-хранения контролируйте его через REST `GET /api/v1/risk/daily-pnl`.

## 9.7. Настройка сборки

### Prometheus (scrape config)

```yaml
scrape_configs:
  - job_name: mmvb-trading-bot
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['localhost:8080']
```

Метрики Micrometer автоматически получают суффиксы: counter → `_total`, timer → `_seconds` + `_seconds_bucket`.

### Правило для демо (докер-compose)

Локально:

```bash
docker run -p 9090:9090 -v $PWD/prometheus.yml:/etc/prometheus/prometheus.yml prom/prometheus
```

### Grafana datasource

- Prometheus `http://localhost:9090`.
- Dashboards: раздел 9.2 (Overview / Trading Performance / LLM / Risk / Infrastructure).

## 9.8. SLO / бюджет ошибок

| SLO | Цель | Измеритель |
|---|---|---|
| Аптайм | 99.5% | `up` + alert BotDown |
| Латентность LLM p95 | < 20 c | `llm_latency_seconds` |
| Cache hit rate | > 60% | PromQL раздела 9.1 |
| Доля fallback | < 5% вызовов | `llm_fallback_activated_total / llm_tokens_used_total` |
| WS-доставка | 0 «вечных» разрывов | `alor_ws_disconnected_total` |
| Потеря событий | 0 | `event_published` vs `event_handled` |

## 9.9. Чеклист запуска мониторинга

- [ ] Prometheus собирает `/actuator/prometheus`
- [ ] Grafana подключена, dashboard «Overview» показывает позиции
- [ ] Alertmanager настроен: Telegram + email
- [ ] Тест алерта `BotDown` (остановить под на 5 мин)
- [ ] Тест алерта `DailyLossLimitReached` (временно `max-daily-loss-rub=0`)
- [ ] Проверены PromQL из раздела 9.1 (нет NaN/пустых серий)
- [ ] `GET /api/v1/risk/daily-pnl` в мониторинге как текстовый probe
