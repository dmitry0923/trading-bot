# Аудит-отчёт: «LLM как источник сигнала» — полное закрытие кода и доков (2026-09-14)

> Ветка: `research/llm-signal-source` (от `research/new-hypothesis`, commit `b2a0229`).
> Дизайн и план — `docs/17-llm-signal-source.md`. Отчёт фиксирует ВСЁ сделанное по аудиту
> LLM-пути: этапы 1–5 плана §17.3 (код) + этап 7 (доки) + риск-аудит R1–R4.
> Верификация: все три гейта зелёные (`./gradlew ktlintCheck test integrationTest`).

## 1. Исходная проблема (зафиксировано аудитом)

Требование пользователя **«решения принимает строго LLM»** на момент начала работы
НЕ выполнялось:

| Место | Было | Значение |
|---|---|---|
| `StrategyRunner.kt` | `signalStrategies = strategies.filterNot { it is AdvisoryOnlyStrategy }` | LLM-контур исключён из конкуренции за сигнал |
| `DiscretionaryStrategy` | `: AdvisoryOnlyStrategy` | полная LLM-цепочка работала только как advisory / A/B-рука |
| `LlmAdvisor` | поправка уверенности ±, VETO только CRITICAL | направление всегда за детерминированными стратегиями |
| Новости | провайдер отсутствовал | компании-специфичных данных/новостей нет |

## 2. Что сделано — этап за этапом

### Этап 1 — Флаг `trading.llm-signal-source` + конкурентный LLM-сигнал

- **`LlmSignalStrategy`** (`application/strategy/LlmSignalStrategy.kt`): полная LLM-цепочка
  tech→fund→strategy→contrarian→arbitrator, участвует в конкуренции за сигнал наравне
  с детерминированными стратегиями через единый `StrategyRunner`.
- **`LlmChainExecutor`** — общий контур цепочки для `LlmSignalStrategy` /
  `DiscretionaryStrategy`.
- Флаг `trading.llm-signal-source` (default `false`) в `TradingConfig` +
  `StrategyRunner`: при false LLM в конкуренцию не попадает (регрессии нет).
- Тесты: `LlmSignalStrategyTest`, регрессия `StrategyRunnerTest`
  («is excluded while source disabled» / «participates when source enabled»).

### Этап 2 — Флаг `trading.llm-signal-only` (+ lineage/cycleId)

- Флаг `llm-signal-only` (default `false`): детерминированные стратегии отключаются,
  LLM — единственный источник сигнала.
- Тесты `StrategyRunnerTest`: «llm signal only keeps single llm strategy»,
  «llm signal only without enable flag falls back to ordinary competition».
- **Lineage (трассировка решений)**: `cycleId` добавлен в `TradeEventService.snapshot()`;
  репозитории получили `findByCycleId` / `findByAggregateIds`; новый
  **`LineageService`** (buildChain + advisor-вердикт + метрики); эндпоинт
  **`GET /api/v1/lineage/{cycleId}`** (ApiController). Тесты: `LineageServiceTest`,
  `TradeEventServiceTest`, интеграционные кейсы.

### Этапы 3–4 — Новостной источник (rg.ru) и интеграция в фундаментальный агент

> Коммит `5bf6794` (ранее, в рамках того же аудита; включён для полноты).

- **`RgRuNewsProvider`** (`infrastructure/news/`): конфиг `news.*` (enabled, base-url,
  api-key, max-items=5, timeout-ms=5000, ttl-minutes=15), Redis-кэш, fail-closed,
  метрики. Тесты `RgRuNewsProviderTest` (хорошая/плохая новость, фильтр по времени,
  лимит, 401, disabled, невалидный JSON).
- Интеграция `issuerNews` в `FundamentalAnalysisAgent` + промпт `fundamental-analysis.yml`;
  fingerprint только стабильные поля. Тесты `FundamentalAnalysisAgentNewsTest`.
- Решение пользователя: в LIVE платная подписка rg.ru; без подписки провайдер молча
  возвращает пустой список — NEUTRAL-база.

### Этап 3 (риск-аудит пути) — R1–R4, fail-closed

- **R1. Риск-паритет**: `StrategyDecision` из LLM-пути НЕ несёт qty/SL/TP (риск-поля —
  только action/target/signalStrength/reasoning); установление размера/стопов — только
  OrderBuilder/риск-слой. Тест делегирования цепочки в `LlmSignalStrategyTest`.
- **R2. Бюджет латентности**: `trading.llm-signal-budget-ms = 2000` (env
  `TRADING_LLM_SIGNAL_BUDGET_MS`); `LlmSignalStrategy.evaluate` под `withTimeout` →
  при превышении fail-closed **HOLD** + метрика `llm.signal.timeout`. Тест с
  `Thread.sleep` против малого бюджета.
- **R3. Фикс StackOverflow**: `ResilientLlmClient.decoratedCall` переведён на
  immutable-цепочку `breaker(rateLimiter(retry(block)))` по `val`. Регресс-тест
  `ResilientLlmClientTest` с JDK HTTP-сервером и всеми декораторами.
- **R4. Fail-closed**: LLM недоступен / бюджет исчерпан / ошибка агента → HOLD;
  лимиты и исполнение не расширяются; `LlmAdvisor` сохранён как fail-safe слой.
- Сопутствующее: `LlmBudgetService.reserve()` (Locale.ROOT / toNumber) + регресс-тест;
  `LlmSingleFlight`, `LlmResponseValidator`, `LlmConfig`, `SemanticCache`,
  `TraceContext` доработки.
- Документация: `docs/17 §17.8` (R1–R5).

### Этап 5 — Shadow-режим (LLM логируется, но НЕ исполняется)

Цель: A/B-наблюдение LLM-winner vs детерминированный базлайн на 30д БЕЗ риска,
до включения `llm-signal-only`.

| Изменение | Файл |
|---|---|
| Флаг `trading.llm-signal-shadow` (env `TRADING_LLM_SIGNAL_SHADOW`, default `false`) | `TradingConfig.kt` |
| `StrategyResult.shadowed: Boolean = false` | `StrategyRunner.kt` |
| Логика: победа `LlmSignalStrategy.ID` + флаг → `shadowed=true`, лог `SHADOW(LLM)`, метрика `llm.signal.shadow{ticker,strategy}` | `StrategyRunner.runAll()` |
| Shadow-победитель НЕ пишется в Redis «последняя стратегия» | `StrategyService` |
| Shadow-победитель НЕ публикуется в order-admission (ордер не создаётся) | `StrategyService` |
| 3 теста: shadow on → shadowed; shadow off → executable; дет-победитель → не shadowed | `StrategyRunnerTest` |

### Этап 7 — Синхронизация документации

| Документ | Правка |
|---|---|
| `docs/17-llm-signal-source.md` | Шапка: «реализовано (research)»; §17.3 этап 5 → ✅; §17.7.2 команды запуска; §17.8 новая R5 (shadow) |
| `README_PRODUCTION_ARCHITECTURE.md` | Секция «LLM как источник сигнала»: флаги, fail-closed, shadow, блокер этапа 6 |
| `docs/03-llm-pipeline.md` | Новый §3.6 «LLM как источник сигнала» (advisory vs сигнальный путь) |
| `AGENTS.md` | Каталог аудитов (строки этапов 3 и 5), раздел LLM |

## 3. Верификация

- Полный прогон после кода: `./gradlew ktlintCheck test integrationTest` —
  **BUILD SUCCESSFUL** (integrationTest ~15m22s).
- После doc-правок: все таски UP-TO-DATE (код не менялся) — зелёно.
- После P1/P2 (round 2): `ktlintFormat`+`ktlintCheck` ✅, `test` ✅ (1467),
  `integrationTest` ✅ (101 PASSED + 1 SKIPPED, 15m27s).
- Нюанс Jackson 3: `ObjectNode`/`DecimalNode.valueOf` из `tools.jackson.databind.node`;
  `isString`/`asString` вместо `isTextual`/`asText`; `set` без generic-параметра.

## 4. Коммиты

| Commit | Содержимое |
|---|---|
| `5bf6794` | RgRuNewsProvider + issuerNews в FundamentalAnalysisAgent + LIVE runbook (этапы 3–4) |
| `f821463` | LLM как источник сигнала (этапы 1–5) + риск-аудит пути R1–R4 + lineage + shadow + docs; 48 файлов, +3160/−147 |
| `9c00435` | Правки первого code-review: single-flight owner-token + Lua release; shadow = research vs execution winner; budget = prompt-estimate + maxTokens; contrarian fail-closed (`llm.chain.contrarian_unavailable`); schema-fail-closed для strategy/arbitrator; fenced-json до schema; RgRuNewsProvider naive→Moscow + drop undated; technical multi-TF/orderbook; CNYRUBF FX-контекст; 23 файла, +803/−149 |

Запушены в `origin/research/llm-signal-source`.

## 4a. Второй code-review (P1/P2, финальный технический revision перед Stage 6)

Ревизоры подтвердили закрытие всех замечаний round 1 (8.5/10) и выявили
финальный набор правок:

### P1 — `oneOf` НЕ был реализован в `DefaultJsonSchemaValidator` (главный дефект)

`targetPrice: {}` проходил schema-проверку (валидатор не знает `oneOf`), затем парсер
молча fallback на `snapshot.currentPrice` → невалидный `BUY`. Исправлено **вариантом B**
(простая схема + строгая нормализация, БЕЗ fallback):

- Схема `STRATEGY_DECISION`: `"targetPrice": {"type":"number","minimum":0.0}`
  (`oneOf`/`pattern` удалены — валидатор их не обрабатывает).
- Новый `normalizeTargetPrice(json, objectMapper)` — конвертирует строковое число
  `"73.25"` → `73.25` ДО валидации; нечисловая строка / объект / null остаются как есть →
  schema reject → fail-closed **HOLD**, `llm.schema.rejected`.
- Применено в `StrategyAgent` и `ArbitratorAgent` (валидация и парсинг — по normalized).
- Тесты: `rejects object targetPrice via schema`, `rejects non-numeric targetPrice string
  via schema` (StrategyAgentTest), string-normalization-кейсы (уже существовавшие).

### P2 — остальные правки

| Пункт | Правка |
|---|---|
| `maximum: 1.0` у `signalStrength` | Вернут в схему (контракт 0..1); parser `coerceIn()` оставлен как последняя линия обороны. Тесты `coerces out of range signal strength` → `rejects out-of-range signal strength via schema` (+1.7/3.0 → HOLD). |
| Точность fingerprint orderbook | `OBI` → 4 знака (`bd4`: 0.2110 ≠ 0.2140); **spread в bps** от мид-цены (`spreadBps`, 2 знака) — и в отпечатке, и в промпте (`{{spreadBps}}`). bid/ask/microprice — 2 знака. |
| Fundamental prompt | Убрано «фундаментальный аналитик российского фондового рынка» / «новости по эмитенту» → «аналитик рынков (акции, фьючерсы, валюты)»; для FX/futures: центробанки (ЦБ РФ, PBoC), торговые потоки; для акций: эмитентские новости. Обе line — и system, и user_template (default/conservative/aggressive). |

### Вердикт второго раунда

- Схема-контракт стратегии теперь максимально простая и честная: только то, что валидатор
  реально умеет проверять (`type`/`enum`/`required`/`additionalProperties`/`min`/`max`).
  Невалидное значение `targetPrice`/`signalStrength` ВСЕГДА → HOLD, никакого тихого
  fallback-на-цену.
- `LLM production readiness` ≈ 8.5–9/10; кодовая часть LLM-pipeline считается закрытой.

## 5. Итог аудита

- **Код-часть аудита ЗАКРЫТА** (этапы 1–5 плана §17.3 + риск-аудит R1–R4 + этап 7 доки).
- Требование «решения принимает строго LLM» реализовано как **обратимый research-путь**:
  все флаги дефолтно off, в production детерминированный сигнал не меняется; live-вход
  LLM-сигнала только после позитивной валидации + A/B в shadow.
- **Fail-closed гарантирован**: недоступность/таймаут/ошибка LLM → HOLD; shadow не
  создаёт ордеров и не расширяет лимиты.

## 6. Осталось (вне кода)

| Пункт | Блокер |
|---|---|
| Этап 6: smoke-бэктест «строго LLM» (`bt.agent.live-strategies=false`) | реальный `LLM_API_KEY` в `.env` (без ключа агенты на детерминированных fallback, прогон только smoke) |
| 30-дневный A/B против базлайна (shadow) | SIM-окружение + LLM-ключ |

## 7. Артефакты вне коммитов

- `jwt.token`, `bootrun.err` — НЕ коммитились (токен/лог, cf. `.gitignore`).
- `scripts/load_stock_history.ps1` — донакачка свечей MOEX ISS (включён в `f821463`).