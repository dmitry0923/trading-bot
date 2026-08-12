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

#### Эксперимент (Shadow Mode / A/B)

| Метрика | Тип | Labels | Описание |
|---|---|---|---|
| `experiment.decision.logged` | Counter | `arm, action` | записанные решения (CONTROL/VARIANT × BUY/SELL/HOLD) |
| `experiment.control.executed` / `experiment.control.shadowed` | Counter | — | контрольная рука исполнена / только записана (shadow) |
| `experiment.variant.llm` | Counter | `mode` (LLM/COPY) | вариантный арбитр вызвал LLM или тень контроля |
| `experiment.outcome.marked` | Counter | `arm` | зафиксирован P&L при закрытии позиции |
| `experiment.outcome.pnl_profit` / `experiment.outcome.pnl_loss` | Counter | `arm` | накопленные прибыль/убыток по руке (P&L разбит на два счётчика, т.к. может быть отрицательным) |
| `experiment.outcome.win` | Counter | `arm` | число закрытий с pnl > 0 по руке |

PromQL:

```promql
# Расхождение решений CONTROL vs VARIANT
sum(rate(experiment_decision_logged_total{arm="CONTROL"}[1h])) by (action)
  / sum(rate(experiment_decision_logged_total{arm="VARIANT"}[1h])) by (action)

# Доля реальных LLM-вызовов вариантной руки
rate(experiment_variant_llm_total{mode="LLM"}[1h]) / rate(experiment_variant_llm_total[1h])

# Win rate по руке (закрытые исходы)
sum(increase(experiment_outcome_win_total{arm="CONTROL"}[1h]))
  / clamp_min(sum(increase(experiment_outcome_marked_total{arm="CONTROL"}[1h])), 1)

# Чистый P&L по руке
experiment_outcome_pnl_profit_total{arm="CONTROL"} - experiment_outcome_pnl_loss_total{arm="CONTROL"}
```

#### RAG (анализ ошибок по трейсам)

| Метрика | Тип | Labels | Описание |
|---|---|---|---|
| `rag.refresh` | Counter | `status` (ok/error) | успешные/упавшие переиндексации корпуса |
| `rag.index_size` | Gauge | — | размер корпуса (проиндексировано трейсов) |
| `rag.search` | Counter | — | выполненный поиск по корпусу |
| `rag.latency` | Timer | — | латентность `analyze()`/`analyzeTrace()` |
| `rag.llm.error` | Counter | — | LLM-разбор недоступен, fallback на rule-based |

PromQL:

```promql
# Ошибки переиндексации корпуса (запросов нет — корпус пуст)
increase(rag_refresh_total{status="error"}[1h])

# Размер корпуса
rag_index_size

# Доля LLM-отчётов среди всех поисков (при отсутствии ошибок ≈ 1)
1 - increase(rag_llm_error_total[1h]) / increase(rag_search_total[1h])

# p95 латентность RAG-анализа
histogram_quantile(0.95, sum(rate(rag_latency_seconds_bucket[5m])) by (le))
```

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
| `alor.ws.orders.connected` / `.disconnected` | Counter | — | подключения/обрывы канала ордеров (13.8.2) |
| `alor.ws.orders.sent` | Counter | `type` | WS-команды (limit/stop/take-profit/cancel) |
| `alor.ws.orders.confirmed` / `.rejected` | Counter | `type` | подтверждения/отказы по WS |
| `alor.ws.orders.uncertain` | Counter | `type, reason` | таймауты/сбои отправки (UNCERTAIN) |
| `alor.ws.orders.fallback` | Counter | `type, reason` | переключение на REST (WS недоступен до отправки) |

#### ML-датасет (roadmap v2.4, раздел 13.11)

| Метрика | Тип | Labels | Описание |
|---|---|---|---|
| `ml.dataset.export` | Counter | `mode` | вызовы экспорта (OK/DISABLED) |
| `ml.dataset.export.rows` | Gauge | — | строки в последнем экспорте |
| `ml.dataset.export.skipped` | Gauge | — | позиции без данных свечей (пропущены) |
| `ml.dataset.export.positions` | Gauge | — | всего закрытых позиций в последнем экспорте |
| `ml.screening` | Counter | `status` | вызовы скрининга (OK) |
| `ml.screening.candidates` | Gauge | — | кандидатов в последнем скрининге |
| `ml.screening.skipped` | Gauge | — | тикеры без данных свечей (пропущены) |


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

### A/B Experiment (`grafana/dashboards/experiment-ab.json`)

Dashboard «Trading Bot - A/B Experiment» (uid `experiment-ab`) автоматически
провижинится через `grafana/provisioning/` (datasource Prometheus + file provider,
папка «Trading Bot»). Панели:

| Панель | Запрос (суть) |
|---|---|
| Net P&L by arm (cumulative) | `experiment_outcome_pnl_profit_total{arm} - experiment_outcome_pnl_loss_total{arm}` |
| Net P&L delta per interval | `sum(increase(pnl_profit)) - sum(increase(pnl_loss))` по руке |
| Profit vs Loss by arm | bar, `sum(increase(...[$__range])) by (arm)` |
| Win rate by arm | `increase(outcome_win) / clamp_min(increase(outcome_marked), 1)` |
| Variant arm: LLM vs copy | pie, `experiment_variant_llm_total{mode=LLM\|COPY}` |
| Decisions rate by arm/action | stacked bar, `sum(rate(decision_logged)) by (arm, action)` |
| Action distribution by arm | bar, по `$__range` |
| Control arm execution | pie, executed vs shadowed |
| Experiment status | stat: decisions, outcomes marked, net P&L CONTROL/VARIANT |

Локальный запуск: `docker compose up -d grafana` → http://127.0.0.1:3000
(admin / `$GRAFANA_ADMIN_PASSWORD`), dashboard уже в папке «Trading Bot».

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

Spring Boot пишет в консоль (stdout, формат для Docker/k8s) в JSON через
`logstash-logback-encoder` (LogbackEncoder). JSON отключается профилем
`!json-logs-off`. Каждая запись содержит MDC-поля:

```json
{"@timestamp":"2026-08-03T10:00:03.123Z","level":"INFO","logger":"com.trading.bot.agent.ArbitratorAgent",
 "message":"Agent 5 FINAL: BUY @ 280.5 conf=0.68 override=null",
 "trace_id":"01J0...","cycle_id":"01J0...","ticker":"SBER","agent":"ArbitratorAgent",
 "application":"mmvb-trading-bot-v2"}
```

- `trace_id` = `cycle_id` — единый UUID (UuidV7), создаётся в `StrategyService.run()`
  и пробрасывается через MDC-контекст корутин во все агенты и LLM-вызовы.
- `ticker` — в циклах тикера; `agent` — в LLM-клиенте (`ResilientLlmClient.complete()`).
- MDC восстанавливается после запуска цикла (`TraceContext`), дочерние корутины
  наследуют контекст через `TraceContext.mdcContext()`.

### Уровни

| Уровень | Где используется |
|---|---|
| `DEBUG` | детали агентов, HTTP-запросы, R2DBC SQL |
| `INFO` | открытие/закрытие позиций, решения арбитра, метки фаз |
| `WARN` | fallback LLM, WIDE_SPREAD, reconnect WS, ошибки обновления токена |
| `ERROR` | исключения циклов, parse-ошибки, сбои ордеров |

Текущая конфигурация: `com.trading.bot: DEBUG`, `org.springframework.r2dbc: DEBUG`.

### Correlation ID

- `trace_id`/`cycle_id` (UuidV7) создаётся в `StrategyService.run()` на каждый цикл стратегий и пробрасывается через все агенты в `AgentLog.cycleId`.
- Для META-агента `cycleId="META"`.
- Трассировка сделки: `cycleId` → стратегия (`strategies.cycle_id`) → позиция (`positions.cycle_id`) → `positions` P&L → закрытие (`PositionClosedEvent.cycleId`).
- Исполнение ордеров (`TradingBotService`): `trace_id` наследуется из `strategy.cycleId`
  в event-хендлерах (`onStrategyGenerated`/`onEntrySignal`), при мониторинге позиций —
  из `position.cycleId`, поэтому JSON-логи входа/закрытия/реконсиляции привязаны к циклу.

### Трейс-хранилище (S3/MinIO, Phase 2)

Полные промпт/ответы LLM-вызовов сохраняются в объектное хранилище
(`trace-storage.*`, MinIO по умолчанию) ключом
`<trace_id>/<agent>/<createdAt>-<uuid>.json`, ссылка — в `agent_logs.storage_key`.
Включается env `TRACE_STORAGE_ENABLED=true` + `MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET`
(бакет `llm-traces`). В `docker-compose.yml` включено по умолчанию (`TRACE_STORAGE_ENABLED:-true`).

Запись асинхронная: `AsyncTraceStorage` возвращает ключ сразу (очередь с буфером
`trace-storage.async-buffer-size`, при переполнении — синхронный fallback), метрики
`trace.write.async{queued|written|failed|sync_fallback}`, `trace.buffer.size`.
Retention: `trace-storage.retention-days` (0 — выкл.) настраивает S3 lifecycle
expiration на бакет один раз при первом обращении.

Чтение трейсов для расследования инцидентов (без RAG/LLM):
`GET /api/v1/traces?key=<storage_key>` — один трейс,
`GET /api/v1/traces?cycleId=<cycle>` — все вызовы агентов цикла,
`GET /api/v1/traces?limit=N` — последние по бакету.

### Эксперимент (Shadow Mode / Decision-level A/B, Phase 3)

Ledger решений — таблица `experiment_decisions` (руки CONTROL/VARIANT на каждый
цикл, см. раздел roadmap 13.18). Включение — через BotSettings
(`experimentEnabled`, `experimentId`, `experimentRolloutPercent`, `variantPromptVersion`)
или env `EXPERIMENT_ENABLED`/`EXPERIMENT_VARIANT_PROMPT_VERSION`.

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
| `api.backtest.panel` | Counter | панельный бэктест (несколько тикеров) |
| `api.backtest.results` | Counter | tag `ticker` — чтение истории прогонов |
| `api.backtest.validate` | Counter | tag `ticker` — walk-forward валидации |
| `bt.total_trades` | Gauge | tag `ticker` — сделки последнего прогона |
| `bt.sharpe` / `bt.max_drawdown` / `bt.profit_factor` | Gauge | tag `ticker` |
| `bt_pass_total` | Counter | tag `result` (PASS/REJECT) — итог каждого прогона/валидации, сравнение итераций |
| `backtest.agent.evaluations` | Counter | tag `agent` — LLM-вызовы агентного генератора сигналов (11.8.1) |
| `backtest.agent.signal` | Counter | tag `signal` (BUY/SELL/HOLD) — сигналы агентного режима |

PromQL:

```promql
# Какие тикеры прошли критерии приёма
bt_pass_total{result="PASS"}
```

### RAG-анализ ошибок (Phase 4)

Анализ первопричины ошибок LLM-агентов по сохранённым трейсам (S3/MinIO, Phase 2):

- **Корпус** — `TraceCorpusIndex` индексирует последние трейсы локальным TF-IDF
  (`TraceEmbedder`, без внешнего vector DB): токенизация EN+RU, стоп-слова,
  idf-веса, косинусная близость. Переиндексация — на `ApplicationReadyEvent`
  и по расписанию `rag.refresh-interval-ms` (метрика `rag.refresh`).
- **Запрос** — по тексту ошибки/симптома или по конкретному трейсу извлекаются
  топ-K похожих (`rag.max-results`, порог `rag.similarity-threshold`).
- **Разбор** — если `rag.llm-enabled=true`, LLM (`rag-analyzer` prompt) строит
  `root_cause/evidence/recommendations/confidence` на извлечённых трейсах как
  контексте (retrieval-augmented, `temperature=0.2`); при недоступности LLM —
  rule-based сводка (агент/fallback/cache/ошибки/латентность). Пайплайн
  best-effort — сбой хранилища/LLM не влияет на торговлю.

API:

| Endpoint | Описание |
|---|---|
| `GET /api/v1/rag/status` | включён ли RAG, размер корпуса, `lastRefresh`, LLM |
| `POST /api/v1/rag/refresh` | принудительная переиндексация корпуса |
| `POST /api/v1/rag/analyze` | `{"query": "...", "ticker": "...", "k": n}` — анализ ошибки |
| `POST /api/v1/rag/analyze-trace` | `{"storageKey": "...", "k": n}` — анализ конкретного трейса |

Включение — env `RAG_ENABLED=true` (+ `RAG_LLM_ENABLED` для LLM-разбора, по умолчанию
`true`). При выключенном RAG `analyze()` возвращает `mode="DISABLED"` без поиска.

## 9.6. Мониторинг P&L и рисков

### Связь метрик и реальных денег

| Вопрос | Метрика/запрос |
|---|---|
| Сколько сейчас в позициях? | `bot_position_opened_total - bot_position_closed_total` |
| Итоговый P&L | `sum(bot_pnl)` |
| Дневной P&L | `GET /api/v1/risk/daily-pnl` (из `daily_risk_snapshot`) |
| Дневной P&L — история | `GET /api/v1/risk/daily-pnl-history?days=30` |
| Близко ли к лимиту? | gauge-прокси: `bot_halted_daily_loss_total` при срабатывании |
| Проскальзывание | `increase(trade_slippage_rub_total[24h])` |

> Дневной P&L персистится в `daily_risk_snapshot` (раздел 6.6): в Prometheus он по-прежнему не виден как timeseries, но историю можно вытащить через REST `GET /api/v1/risk/daily-pnl-history`.

## 9.7. Настройка сборки

### Prometheus (scrape config)

Эндпоинт `/actuator/prometheus` закрыт Bearer-токеном `METRICS_SCRAPE_TOKEN`
(см. `ScrapeTokenFilter`). Конфиг в репозитории — `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: mmvb-trading-bot
    metrics_path: /actuator/prometheus
    authorization:
      type: Bearer
      credentials: ${METRICS_SCRAPE_TOKEN}
    static_configs:
      - targets: ['app:8080']
```

Метрики Micrometer автоматически получают суффиксы: counter → `_total`, timer → `_seconds` + `_seconds_bucket`.

Ручная проверка:

```bash
curl -H "Authorization: Bearer $METRICS_SCRAPE_TOKEN" http://localhost:8080/actuator/prometheus | grep -E "bot_|llm_"
```

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
