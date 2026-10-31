# План: покрытие тестами → чистка проекта → релизная сборка

**Дата:** 2026-10-30
**Цель:** довести покрытие до **> 90 % по LINE и по BRANCH**, очистить проект от мусора,
собрать новую сборку с подтверждёнными порогами.

## 1. Текущее состояние (обновляется по итерациям)

| Метрика | Значение |
|---|---|
| LINE | 93,73 % (24 365 / 25 994) |
| BRANCH | 78,35 % (8 325 / 10 626) |
| Тестов | 2 871 |
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
