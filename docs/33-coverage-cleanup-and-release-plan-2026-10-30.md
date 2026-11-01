# План: покрытие тестами → чистка проекта → релизная сборка

**Дата:** 2026-10-30
**Цель:** довести покрытие до **> 90 % по LINE и по BRANCH**, очистить проект от мусора,
собрать новую сборку с подтверждёнными порогами.

## 1. Текущее состояние (обновляется по итерациям)

| Метрика | Значение |
|---|---|
| LINE | 97,03 % (25 219 / 25 994) |
| BRANCH | 89,80 % (9 528 / 10 626) |
| Тестов | 3 577 |
| `engineVersion` | 2026-10-26.2 |

Порог `koverVerify` в `build.gradle.kts`: LINE 90, **BRANCH 70** — финальный шаг поднимет
BRANCH до 90 (ratchet) только когда фактическое покрытие устойчиво выше 90.

## 2. Реестр найденного поведения и дефектов

Фиксируется по мере итераций покрытия. `FIXED` — исправлено кодом (с регрессионным тестом),
`DOC` — поведение задокументировано, решение за пользователем, `DEAD` — недостижимый код.

| ID | Где | Тип | Суть | Статус |
|---|---|---|---|---|
| C-01 | `TradingAccountService.update` | поведение/риск | `null` у `aumRub`, `maxOpenPositions`, `maxDailyLossRub` означает **очистить**, а у `name`/`exchange`/`enabled`/`weight` — **не менять**. Частичный `PATCH` одного поля стирает персональные лимиты аккаунта → лимиты откатываются на глобальные | DOC |
| C-02 | `BacktestRiskSimulator.checkConcentration` | расхождение backtest/live | Направленный знак учитывается только у кандидата: открытые позиции суммируются по модулю. Сбалансированная книга long+short выглядит как 100 % нетто → `DIRECTIONAL_CONCENTRATION` блокирует вход, который live-`RiskManagementService` пропустил бы | DOC |
| C-03 | `AdaptiveRiskService.calculateOptimalPositionSize` | fail-open | Размер считается от `AumProvider.currentAum` (fail-open: при недоступном балансе — конфиг-депозит), тогда как входные гейты используют fail-closed `currentAumChecked`. Вход блокируется выше по конвейеру, но автономные вызовы (API/UI-превью размера) могут показать размер от фиктивного депозита | DOC |
| C-04 | `AdaptiveRiskService.fallbackDiagnostic`, `shouldPauseTrading` | скоуп аккаунта | Используют `analyzeLastNDays(...)` **без** `accountId` (общий пул всех аккаунтов), а калибровка порога — по account-scoped закрытым позициям. Порог-fallback и пауза могут «наследовать» убытки другого аккаунта | DOC |
| C-05 | `EntryFilters` | мёртвый код | 37 из 266 ветвей недостижимы: `rangeStart == null` после проверки `barMinutes >= orbWindowStartMinutes`, `minOfOrNull == null` при непустом срезе, `!ema.isFinite()`, `base.size < rangePeriod` после `minBars >= rangePeriod`, `vwap == null` при непустом окне | DEAD |
| C-06 | `BacktestRiskSimulator.calculateExpectedNet` | мёртвый код | Условие `avgWin <= 0 && avgLoss <= 0` недостижимо: при нулевом `avgWin` Kelly-гейт (шаг раньше) даёт отрицательный размер и блокирует вход с `ZERO_RISK_SIZE` | DEAD |
| C-07 | `PortfolioMomentumEngine` | мёртвый код | Защиты `base < 0` (momentum-окно) и `base.size < rangePeriod` недостижимы по построению индексов; NaN/Infinity-ветви σ не воспроизводятся на конечных данных | DEAD |
| C-08 | `BacktestRiskSimulator` | поведение | `recordClose` получает `cash` уже **после** закрытия и не прибавляет P&L повторно (регресс-тест есть); daily P&L сбрасывается при смене дня, поэтому intraday-убытки не видны rolling-гейтам на следующем дне | DOC |
| C-09 | `LiveStrategyBacktestSignalGenerator.signal` | асимметрия fail-режимов | Сбой расчёта `emaCross`/`volumeSpike`/`squeeze`/`rangeSqueeze` = **HOLD** при `blockOnUnknown`, а сбой `blocksEntry` (session/pullback), `blocksDirection`, `ORB`, `panic`/`macro`/`vwapMr` всегда = «не блокировать». Причина — разные контракты фильтров, но при добавлении нового фильтра легко потерять fail-closed | DOC |
| C-10 | `LiveStrategyBacktestSignalGenerator.signal` (ML) | **дефект (исправлен)** | Сбой `mlDirection.evaluate()` игнорировался (`null`) даже при `mlDirectionBlockOnUnknown=true` → фильтр, объявленный fail-closed, молча пропускал входы. Теперь возвращается HOLD-вердикт (`ML_UNKNOWN`) | FIXED |
| C-11 | `TradingControlService.scheduledForceClose` | латентный | При `!scope.isActive` (shutdown) флаг `forceCloseInFlight` остаётся `true` (ранний `return` до `finally`). После shutdown тиков нет, поэтому эффекта в проде нет; при переиспользовании scope потребовался бы сброс | DOC |
| C-12 | `ExitRules.useAtrStops` | контракт | Стопы по ATR включаются только если **оба** frozen-процента `null` и ATR положителен; иначе заявка получила бы процентный стоп шире риск-бюджета сайзера. Покрыто всеми комбинациями условий | DOC |

> Правило: `DOC`-пункты **не меняют** опубликованные research-числа и live-поведение
> без отдельного решения. `DEAD`-пункты чистятся на этапе 3 (либо удаляются, либо
> помечаются причиной недостижимости).

## 3. Чистка проекта (этап после достижения 90 % BRANCH)

**Статус 2026-11-01:** шаг 2 (мёртвый production-код) выполнен, шаг 3 — частично
(`println`/`System.out`/`TODO`/`FIXME` в `src/main` отсутствуют); шаги 4–6 (конфиги,
крупные данные, зависимости) не начинались. Итог чистки и новая research-сборка —
`docs/34-code-cleanup-and-research-restart-2026-11-01.md`.

Порядок — от безопасного к рискованному, каждый пункт отдельным коммитом:

1. **Тестовый код:** неиспользуемые импорты/хелперы, дублирующиеся фикстуры, «пустые»
   утверждения (`assertEquals(x, x)`), тесты-близнецы.
2. **Мёртвый production-код:** ветви из C-05/C-06/C-07 — удалить или заменить на
   раннее утверждение с комментарием (меняет только структуру, не поведение).
3. **Отладочные артефакты:** `println`/`System.out` в `src/main`, временные файлы,
   скрипты-однодневки без упоминания в docs, дубликаты research-скриптов.
4. **Конфигурация:** устаревшие ключи `application.yml`/`RiskConfig` без потребителей,
   мёртвые миграции (Liquibase-контрольные суммы **не трогать**), дубликаты `docs/`.
5. **Крупные данные:** `data/samples/*`, `artifacts/*` — оставить только то, на что
   ссылаются docs; остальное вынести/удалить по согласованию.
6. **Зависимости:** неиспользуемые Gradle-зависимости, устаревшие версии
   (проверять отдельно: любое обновление = новый прогон полного набора тестов).

Критерий этапа: `./gradlew clean build` зелёный, дифф не меняет поведения
(число тестов не падает, research-артефакты не пересчитываются).

## 3a. Достижимость 90 % BRANCH: анализ остатка (2026-11-01)

Замер после итерации 2026-11-01 (вечер): **BRANCH 89,80 % (9 528 / 10 626)**, LINE 97,03 %,
тестов 3 577, падений 0. До цели не хватает **36** покрытых ветвей.

Структура остатка (по данным `build/reports/kover/report.xml`):

- 1 082 непокрытых ветвей; **~400** из них находятся на строках с `?:`.
- Крупнейшие остатки: `BacktestEngine` 44, `PortfolioMomentumEngine` 30, `EntryFilters` 21,
  `LiveStrategyBacktestSignalGenerator` 21, `DrawdownProtectionService` 18, `DashboardSseService` 15,
  `AlorFuturesClient` 14, `PositionRepository` 14, `BacktestRiskSimulator` 14,
  `ProtectionOrderManager` 14.

**Часть остатка структурно недостижима (DEAD) — подтверждено кодом и схемой БД:**

1. `PositionRepository` (13 строк, 27 ветвей): `row.get("...", X) ?: default` для колонок,
   объявленных `NOT NULL DEFAULT` (`variation_margin`, `pending_entry`, `realized_pnl`,
   `cumulative_sl_fill_qty`, `funding_unknown` и т.д., миграции 019+). SQL не может вернуть
   `NULL` → правая ветвь `?:` не исполняется ни при каких данных. Фильтры исключать нельзя
   по правилам задачи — фиксируется как DEAD.
2. `BacktestEngine`: `pos0.stopLoss ?: return` / `pos0.takeProfit ?: return` — `openPosition`
   всегда строит оба уровня через `stopPrice`/`takePrice` (возвращают `BigDecimal`, не null);
   `fundingWindowTarget` ветвь `else -> null` недостижима (после `inMarket`-проверки условия
   покрывают весь диапазон).
3. `PortfolioMomentumEngine`: `notionals[idx] ?: 0.0` и `alloc[idx] ?: 0.0` — карты строятся
   для всех элементов `selection.longs + selection.shorts`, ключ всегда присутствует.
4. `MoexClient`: `marketdata.path("data").path(0) ?: return null` — Jackson `path()` не
   возвращает `null` (возвращает `MissingNode`), проверка недостижима.
5. `MoexOrderLogParser`: `bboBucketsCrossed` недостижим — «агрессивные» заявки (ask ниже
   текущего bid / bid выше текущего ask) отбрасываются ДО аккумулятора, поэтому carried
   bid/ask не могут пересечься. Подтверждено тестом: строка ask=99 при bid=100 даёт
   `aggressive=1`, `crossed=0`.
6. `WsOrderTransport`/`DecisionEngine`: часть ветвей требует одновременного `LEASE_LOST` и
   реального `DistributedLockService`; мок сервиса меняет способ проверки, а не поведение —
   требует отдельного интеграционного сценария.

**Вывод:** 90 % BRANCH на текущем коде достижимо только если (а) часть DEAD-ветвей удалить
из production-кода либо заменить на `require`/`check` с сохранением поведения, либо
(б) разрешить фиксировать DEAD-ветви в знаменателе (фильтр/исключение), что текущими
правилами запрещено. Без этого потолок оценивается существенно ниже 90 %; точная доля
DEAD-ветвей требует отдельного пофайлового аудита (реестр C-01…C-12 в §2).

## 3b. Итерация «до 90 %» (2026-11-01, вечер): 88,90 % → 89,80 %

Добавлено 41 тест (3 536 → 3 577), +82 покрытые ветви. Что сработало:

- **`PositionRepository.toPosition`** (27 → 14): маппер сделан `internal`, добавлен unit-тест
  на `Row` с `NULL` в nullable-колонках и с заполненными значениями — закрыл дефолт-ветви
  `?: 0/false/ZERO` без БД.
- **`ApiController`**: `trading/status` с пустым/заполненным `TradingStatus` (reason/source/
  detail/blockedAt/ticker), `withdraw`/`deposit` без `amount`, clearing с `date=null` и датой.
- **`ProtectionOrderManager`**: флаги `slPendingReplace`/`slCancelPending`/`tpPendingReplace`/
  `tpCancelPending`, активный outbox TP, `reconcile` для не-OPEN позиции.
- **`BacktestEngine`**: mock-`MeterRegistry` (counter=null) для ML/MTF/funding-window/veto/risk
  путей, funding-veto с неизвестной датой и SELL.
- **`IndicatorCalculator`**: BEARISH-ветвь MACD, ADX на падении/коротком окне, Keltner/range-squeeze
  на плоском диапазоне, `isSqueeze` при нулевых ценах.
- **`DecisionEngine`**: null-снапшот → targetPrice, gate-блокировки без значения (netEV/fundingRub).
- **Прочее**: `FundingSnapshotService` (refreshed snapshot, LIVE без снапшота),
  `ConfiguredFundingProvider`, `DegenerateCaseGuard` (неизвестный тикер), `VolatilityIndexService`
  (кеш TTL), `MonthlyTuningScheduler` (период уже в `strategy_versions`), `ClearingService`
  (неизвестный инвестор/аккаунт, пустой пул), `ProfitForecastService` (пустая история, один день,
  сделки без `closedAt`), `MoexFundingProvider` (пустые/пустые-строки data), `BacktestTradeRecord.toMap`.

Остаток (36 ветвей до цели) — это защитные fail-closed ветви (`?:` на NOT NULL-колонках,
NaN/Infinity-guard'ы) и сетевые пути (WS-reconnect, rate-limit/circuit-breaker декораторы,
`.7z`-ветка парсера без `org.tukaani:xz`). Их покрытие требует либо удаления DEAD-кода
(меняет инварианты), либо фейковых WS/HTTP-серверов — отдельный цикл.

## 4. Релизная сборка (этап 4)

1. `build.gradle.kts`: `koverVerify` — LINE ≥ 90 **и** BRANCH ≥ 90.
2. Полный прогон: `test + integrationTest + ktlintCheck + koverVerify` (зелёные).
3. `./gradlew clean bootJar` — артефакт с версией; зафиксировать:
   - версию и `engineVersion`;
   - фактические LINE/BRANCH (из `build/reports/kover/report.xml`);
   - число тестов;
   - SHA-256 артефакта.
4. Записать результаты в этот документ (раздел 5) и в `docs/28` (журнал итераций).

## 5. Результат сборки

_Заполняется на этапе 4._
