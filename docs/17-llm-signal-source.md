# 17. LLM как источник сигнала (ADR, research)

> Ветка: `research/llm-signal-source`. Статус: **проектирование (design), код НЕ менялся.
> Текущая ветка `research/llm-signal-source` создана от `research/new-hypothesis` (commit `b2a0229`).

## 17.1. Проблема и требование

Пользовательское требование: **«решения должна принимать строго LLM»**.

Фактически в коде (аудит ветки `research/new-hypothesis`):

| Место | Сейчас | Значение |
|---|---|---|
| `StrategyRunner.kt:52` | `signalStrategies = strategies.filterNot { it is AdvisoryOnlyStrategy }` | LLM-контур **исключён из конкуренции за сигнал** (C-001) |
| `DiscretionaryStrategy.kt:54` | `: AdvisoryOnlyStrategy` | полная LLM-цепочка (tech→fund→strategy→contrarian→arbitrator) работает только как advisory / A/B-рука |
| `LlmAdvisor.kt:24-42` | AGREE/NEUTRAL/VETO, VETO только при CRITICAL, поправка уверенности −0.30..+0.15 | советник поверх детерминированного решения, **направление не меняет** |
| `FundamentalAnalysisAgent.kt:56-62` | вход только макро (ставка ЦБ, Brent, USD/RUB) | компаний-специфичных данных, новостей, отчётности **нет** |
| `grep rg.ru / NewsProvider / RSS / новости` | 0 совпадений в `*.kt` | новостной провайдер отсутствует |

Вывод: **требование «строго LLM» сейчас не выполняется** — направление всегда
определяют детерминированные стратегии. Данный документ фиксирует целевое
архитектурное решение и поэтапный план перехода (обратимо, флагом).

## 17.2. Целевая архитектура

### 17.2.1. Принципы (неизменяемые)

1. **Исполнение остаётся детерминированным.** LLM — «мозг» (источник решения о
   направлении), но не «руки»: ордера, риск, лимиты, daily-loss, outbox —
   Kotlin-код (см. `docs/03-llm-pipeline.md`, §3.1). Это не меняется.
2. **Fail-safe при недоступности LLM.** Правило выбирается флагом (ниже):
   - *research/paper (дефолт в SIM)*: LLM недоступен → HOLD (fail-closed);
   - *live до валидации*: fail-open, как сейчас (детерминированный сигнал).
3. **Бюджет латентности.** `trading.advisor-budget-ms` (сейчас ~1 c). LLM-источник
   сигнала не должен ломать order-execution: решение принимается до слоя ордеров,
   бюджет соблюдается через `withTimeout` + корутинную изоляцию.
4. **Прозрачность.** Все решения агентов пишутся в `agent_logs`, вердикт советника —
   `advisor.decision`, каждое решение стратегии — в журнал цикла. Метрики
   `agent.*`, `advisor.*`, `llm.fallback.activated` уже на месте.

### 17.2.2. Включение LLM-цепочки в конкуренцию за сигнал

Предлагаемый дизайн: **новый флаг-конфиг** (например `trading.llm-signal-source=true`),
при котором:

1. `DiscretionaryStrategy` перестаёт быть `AdvisoryOnlyStrategy` только в конфиге
   с флагом — **не удаляя маркер из кода**, чтобы не ломать C-001 в live-default.
   Конкретно: `StrategyRunner.structuredStrategies` фильтрует по `id` +
   конфига-factory ведёт список источников:
   - `Deterministic`: TrendFollowing, Breakout, Scalping, MeanReversion, Grid, CnyRub
     — always;
   - `LLM`: DiscretionaryStrategy — только при `trading.llm-signal-source=true`.
2. `StrategyRunner.runAll` получает `signalStrategies` = union и выбирает победителя
   по сигналу с максимальной силой (как уже реализовано `StrategyRunner.kt:126-133`).
   LLM-решение несёт `signalStrength` от арбитра (0..1) — сопоставимо с детерминированными.
3. **Ти-брейк и приоритет LLM при равенстве**: ти-брейк — порядок регистрации
   (сейчас детерминирован). Для research-режима LLM ставится после детерминированных:
   побеждает только если его сигнал **явно выше**. В фазе «строго LLM»
   детерминированные стратегии отключаются флагом `trading.llm-signal-only=true`.

### 17.2.3. Роль LlmAdvisor остаётся

`LlmAdvisor` (второй LLM-вызов после выбора направления) сохраняется как
guardrail: VETO при CRITICAL блокирует вход. Это уже fail-safe слой поверх
любого источника сигнала.

### 17.2.4. Данные по эмитентам (фундаментальный вход)

Требование: фундаментальному агенту нужны не только макро, но и данные по
конкретным эмитентам. Источник по выбору пользователя — **https://rg.ru**.

Проверка доступа (перепроверка 2026-09-11, несколько клиентов):

| Клиент | `GET https://rg.ru/` | `GET https://rg.ru/rss/` |
|---|---|---|
| `webfetch` (opencode) | 401 | 401 |
| PowerShell `Invoke-WebRequest` (UA Chrome 126) | 401 | 401 |
| `curl.exe -L` (UA: главная/www, `https://www.rg.ru/`) | 401, тело 7637 B | — |

Тело ответа 401 (не заголовок, а HTML-страница) — **CAPTCHA-страница системы
`qauth`**: «Введите текст с картинки», поле `captcha-input` + токен, IP-отпечаток
(`Ваш IP-адрес: …`). Вывод: **rg.ru закрыт анти-бот капчей для всех прямых HTTP
клиентов без браузерного JS-стека и решателя капчи**; RSS-лента доступна только
в браузере человека. Машинная интеграция `rg.ru` как программного источника
новостей в текущем виде **невозможна без обхода капчи (не делаем)** или без
приобретения платного API/доступа.

Альтернативы для фундаментального входа (обсудить с пользователем):
- макро-контекст остаётся (ставка ЦБ, Brent, USD/RUB через `MacroContextService`);
- MOEX ISS **не отдаёт** корпоративные новости (проверено в ходе проекта);
- другие источники без капчи (напр. ленты ТАСС/Интерфакс/страница эмитента)
  — требуют отдельного решения пользователя.

Интерфейс в коде (реализован, `infrastructure/news/`):

```kotlin
interface IssuerDataProvider {
    /** Кэшированные на TTL новости по тикеру (заголовок, url, дата, отрывок). */
    suspend fun newsFor(ticker: String, hours: Int): List<NewsItem>
}
```

### 17.2.4.1. Статус реализации (2026-09-11)

Источник — **rg.ru, платная подписка в LIVE** (решение пользователя). `RgRuNewsProvider`
реализован и покрыт тестами:
- конфиг `news.*` (`NewsConfig`): `enabled` (default false), `base-url` (плейсхолдеры
  `{ticker}`/`{hours}`), `api-key` (заголовок Authorization), `max-items=5`, `timeout-ms=5000`,
  `ttl-minutes=15`; env `NEWS_ENABLED`/`NEWS_BASE_URL`/`NEWS_API_KEY`.
- Redis-кэш `news:rgru:{ticker}:{hours}` (TTL `ttl-minutes`); метрики
  `news.cache.hit/miss`, `news.provider.items`, `news.provider.disabled/unauthorized/error`.
- **Fail-closed**: 401/403 (нет/просрочена подписка), таймаут, невалидный JSON → пустой список;
  агент выдаёт NEUTRAL-базу, вход не блокируется и не задерживается.
- Тесты `RgRuNewsProviderTest` (JDK HttpServer, мок HTTP): парсинг хорошей новости, фильтр
  по окну времени, лимит `maxItems` + порядок по свежести, 401 → пусто + метрика, disabled,
  невалидный JSON. `FundamentalAnalysisAgentNewsTest`: дайджест попадает в переменную `issuerNews`
  для сценариев «хорошая» (дивиденды) и «плохая» (убыток) новость + NEUTRAL-база без провайдера.

Интеграция: `FundamentalAnalysisAgent` получает `newsFor(ticker)` и добавляет
дайджест в переменные промпта `fundamental-analysis.yml` (новая переменная
`issuerNews`), включая в fingerprint только stable-поля (число новостей,
даты, заголовки) — иначе semantic cache аннулируется каждую минуту.

### 17.2.5. Бэктест LLM-пути

Уже есть инфраструктура: `AgentBacktestSignalGenerator` включается профилем
`backtest` (`application-backtest.yml`, `bt.agent.enabled=true`), по умолчанию
выбран `LiveStrategyBacktestSignalGenerator` (`bt.agent.live-strategies=true`).
Для «строго LLM» в бэктесте: `bt.agent.enabled=true` + `bt.agent.live-strategies=false`.
Минусы — стоимость и скорость (сэмплинг каждые `sample-every=20` баров, кэш
fingerprint). Без реального LLM-ключа агенты падают на детерминированные fallback —
бэктест валиден только как smoke, не как калибровка edge.

## 17.3. План внедрения (research, обратимо)

| № | Этап | Файлы | Критерий приёма |
|---|---|---|---|
| 1 | Флаг `trading.llm-signal-source` (default false) + DI-фабрика sources-list | `StrategyRunner.kt`, `TradingConfig.kt`, `StrategyConfig`/`ServiceConfig` | существующие юнит-тесты зелёные; при false поведение идентично (регрессия по `StrategyRunnerTest`) |
| 2 | `trading.llm-signal-only` (default false): детерминированные отключаются, LLM — единственный источник | `StrategyRunner.kt` | тест: при true `DiscretionaryStrategy` единственный в `signalStrategies` |
| 3 | `IssuerDataProvider` интерфейс + `RgRuNewsProvider` (fetch с retry+TTL, 401-safe) | новый `infrastructure/news/` + тесты на мок HTTP | ✅ `RgRuNewsProviderTest` (2026-09-11): новости, фильтр по времени, лимит, 401, disabled, невалидный JSON |
| 4 | Интеграция новостей в `FundamentalAnalysisAgent` + промпт (переменная `issuerNews`) | `FundamentalAnalysisAgent.kt`, `prompts/fundamental-analysis.yml`, тесты парсинга | ✅ `FundamentalAnalysisAgentNewsTest` (хорошая/плохая новость, NEUTRAL-база); fingerprint стабилен |
| 5 | Shadow-режим: LLM сигнал логируется, но не исполняется (метрика `strategy.runner.winner`) | `StrategyRunner.kt` (+флаг `shadow`) | в SIM на live-данных: LLM-победитель в логах, ордера нет |
| 6 | Бэктест «строго LLM» smoke (`bt.agent.live-strategies=false`) | `application-backtest.yml` + README | прогон `/backtest/panel` завершается, результаты в `research/` |
| 7 | Док: обновить `README_PRODUCTION_ARCHITECTURE.md`, `docs/03-llm-pipeline.md`, AGENTS.md | доки | пересчитать все 3 проверки (test+int+ktlint) |

## 17.4. Риски и открытые вопросы

| Риск/вопрос | Уровень | Митигация / решение |
|---|---|---|
| LLM-источник в live без валидации | **HIGH** | в live по умолчанию флаг выключен (дет. сигнал). Включение — только после deployment-gate + минимум в SIM |
| Внешний ключ LLM отсутствует (`.env`: `LLM_API_KEY` пуст) | **HIGH** | бэктест LLM-пути сейчас = deterministic fallback (smoke). Для валидации edge нужен ключ (RouterAI/Moonshot/DeepSeek/Qwen, `application.yml:100-111`) |
| rg.ru **недоступен публично** — анти-бот CAPTCHA (`qauth`) на все GET, RSS в браузере | **HIGH** (публичный доступ не работает) | **Решение принято (2026-09-11): в LIVE будет платная подписка rg.ru.** `RgRuNewsProvider` реализован под неё; без подписки (`news.enabled=false`/пустой ключ) провайдер возвращает пустой список — NEUTRAL-база, ничего не ломается. Зафиксировано в §17.2.4.1 |
| Смысл флага «строго LLM» (отключает качество дет. стратегий) | MEDIUM | вводится только после A/B в shadow (этап 5) — сравнение LLM-winner vs детерминированный на 30д |
| LLM-латентность в цикле | MEDIUM | бюджет `advisorBudgetMs` + `withTimeout`; DiscretionaryStrategy уже параллельный (tech+fund сначала) |
| `sampleEvery=20` vs `1` в бэктесте | LOW | изменить на 1 для точной калибровки (цена — токены); отдельная настройка |
| semantic cache контаминация live/backtest | LOW | namespace `backtest` уже изолирован (`AgentBacktestSignalGenerator`), не менять |

## 17.5. Синхронизация требований

- «Решения принимает строго LLM» → этап 2 (`llm-signal-only=true`) в research,
  live-одобрение только после позитивной валидации и A/B против дет. базлайна.
- «LLM должен видеть новости» → этап 3–4 (rg.ru, фильтр маппинга тикер→эмитент).
- «Не менять frozen production» → вся работа в ветке `research/llm-signal-source`,
  в `main`/production флаги дефолтно выключены.

## 17.6. Что НЕ входит в этап «зафиксировать архитектуру»

- Изменение кода не требуется для фиксации дизайна (этот документ).
- Реальный LLM-ключ — окружение, не код.
- **Источник новостей: rg.ru с платной подпиской в live** (решение пользователя 2026-09-11).
  `RgRuNewsProvider` + конфиг `news.*` + интеграция `issuerNews` + тесты реализованы.
  Публичный доступ заблокирован CAPTCHA — работает только подписка (§17.2.4, §17.2.4.1).
- Разбор полнотекста новостей — за рамками research-этапа (только заголовки +
  отрывок). Полнотекст — отдельный P1-пункт.

## 17.7. Как запускать LLM-путь в research (конкретные команды, 2026-09-11)

Требует postgres+redis (`docker compose up -d postgres redis`), `AUTH_USER`/`AUTH_PASSWORD`
в `.env` и собранный jar. Все прогоны — **smoke до появления реального `LLM_API_KEY`**
(без ключа агенты на детерминированных fallback).

### 17.7.1. Smoke бэктеста «строго LLM» (детерминированный fallback)

```bash
# Docker-инфраструктура (TimescaleDB + Redis)
docker compose up -d postgres redis

# jar
.\gradlew.bat bootJar

# flag: выключить детерминированные live-стратегии из конкуренции.
# bt.agent.enabled=true + bt.agent.live-strategies=false → генератор
# AgentBacktestSignalGenerator (полная LLM-цепочка tech→fund→strategy→contrarian→arbitrator).
# Без этого флага default live-strategies=true — LLM не гоняется.
# --bt.adaptive-confidence-threshold=0.60 — deployment-gate берёт ТОЛЬКО отсюда.
java -jar build\libs\trading-bot-2.0.0.jar `
  --spring.profiles.active=backtest `
  --bt.agent.enabled=true `
  --bt.agent.live-strategies=false `
  --bt.agent.sample-every=20 `
  --bt.adaptive-confidence-threshold=0.60 `
  --spring.mvc.async.request-timeout=600000
```

Затем в отдельном окне (basic-auth из `.env`):

```bash
# донакачка истории MOEX ISS (если пусто):
#   curl -u "user:pass" "http://localhost:8080/api/v1/backtest/CNYRUBF?loadHistory=true&days=365"

# панель (smoke): проходит ли вообще LLM-конвейер в бэктесте
curl -s -u "user:pass" -X POST http://localhost:8080/api/v1/backtest/panel `
  -H "Content-Type: application/json" `
  -d '{"tickers":["CNYRUBF"],"days":365,"timeframe":"MINUTE_10","loadHistory":false,
       "slPoints":150,"tpPoints":600,"adaptiveConfidenceThreshold":0.60}'

# WFA-валидация (deployment-gate): см. scripts/research_wfa_cnyrubf.ps1
.\scripts\research_wfa_cnyrubf.ps1 -Days 365 -Folds 6 -Conf 0.60
```

**Ограничение сейчас**: без `LLM_API_KEY` это только проверка конвейера/кэша —
температура 0.0, namespace `backtest`, `sample-every=20`; НЕ калибровка edge.
Появление ключа в `.env` → `bt.agent.*` подхватывается без пересборки.

### 17.7.2. Включение LLM как источника сигнала (этапы 1-2, флаги)

Флаги `trading.llm-signal-source` / `trading.llm-signal-only` пока **не реализованы**
в `StrategyRunner` (этап 1-2 плана, §17.3). После реализации:

```bash
java -jar build\libs\trading-bot-2.0.0.jar `
  --spring.profiles.active=backtest `
  --bt.agent.enabled=true `
  --trading.llm-signal-source=true `
  --bt.adaptive-confidence-threshold=0.60
```

Этап 5 (shadow): `--trading.llm-signal-source=true --trading.llm-signal-shadow=true`
на SIM (`TRADING_MODE=SIMULATION`) — LLM-победитель логируется
(`strategy.runner.winner`), ордера нет.

### 17.7.3. Новости эмитентов (rg.ru, реализовано, §17.2.4.1)

```bash
java -jar build\libs\trading-bot-2.0.0.jar `
  --spring.profiles.active=backtest `
  --news.enabled=true `
  --news.base-url="https://sub.rg.ru/news/{ticker}?hours={hours}" `
  --news.api-key="$env:NEWS_API_KEY"
```

Без подписки (`news.enabled=false` или пустой `api-key`) провайдер молча отдаёт
пустой список → фундаментальный агент работает по макро (NEUTRAL-база).