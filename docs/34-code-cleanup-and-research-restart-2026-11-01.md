# Чистка кода и research-сборка (2026-11-01)

**Контекст:** завершён цикл покрытия тестами (`docs/33`). Перед новым раундом
исследований кодовая база очищена от мёртвого кода и предупреждений компилятора,
собрана новая сборка. Документ фиксирует ровно то, что изменено, и то, что
сознательно оставлено, — чтобы новый baseline можно было воспроизвести.

## 1. Метод

1. **Покрытие как детектор мёртвого кода.** Kover по каждому классу, непокрытые
   ветви разбирались вручную; всё, что недостижимо по инвариантам, занесено в
   `docs/33` §2 (C-05, C-06, C-07) как `DEAD`.
2. **Компилятор.** `./gradlew compileKotlin --rerun-tasks` — собраны все
   предупреждения `w:` (41 штука) и разобраны по одному.
3. **Статический аудит:** неиспользуемые `private fun`/`private val`,
   `TODO`/`FIXME`, `println`/`System.out`, `@Deprecated`-методы с нулём
   production-вызовов.

## 2. Что изменено

| # | Файл | Изменение | Тип | Эффект |
|---|---|---|---|---|
| 1 | `tuning/MonthlyTuningScheduler.kt` | удалён `currentPeriod()` (deprecated, 0 вызовов в main); тест переведён на `targetPeriod()` | мёртвый код | тест теперь проверяет **тот** период, который реально запускает cron (предыдущий месяц) |
| 2 | `backtest/BacktestEngine.kt` | удалён параметр `closePosition(applySlippage)` и недостижимая ветка `else` (`SimulatedExecution.Fill(price)`) | мёртвая ветвь | ни один вызов не передавал `false` — поведение идентично |
| 3 | `backtest/BacktestEngine.kt` | `useRiskGates: Boolean` → `riskGates: BacktestRiskSimulator?` (smart-cast), убраны два `!!`; `liqPos.liquidationPrice!!` → локальная `liqPrice`; убран `.toDouble()` у уже-`Double` | предупреждения | то же поведение, меньше мест для NPE |
| 4 | `backtest/FinalHoldoutValidator.kt` | три `backtestConfig?.` заменены прямыми обращениями (параметр non-null) | мёртвые ветви | убраны мёртвые null-ветви в holdout-пути research |
| 5 | `backtest/pairs/PairsBacktestEngine.kt` | `pendingExitReason!!` → локальная `exitReason` | предупреждение | убран `!!` на `var` |
| 6 | `application/decision/DecisionEngine.kt` | пустая ветка `Allowed -> Unit` заменена комментарием | мусор | без изменений поведения |
| 7 | `infrastructure/llm/LlmBudgetService.kt` | убран лишний `as? List<Any?>` и `@Suppress("UNCHECKED_CAST")`; убран `.toDouble()` у `MILLION` | предупреждения | тип уже был `List<Any?>?` |
| 8 | `infrastructure/llm/LlmResponseValidator.kt` | `number.toDouble()` → `number` | предупреждение | `number` уже `Double` |
| 9 | `service/StrategyService.kt`, `tuning/TuningMetricsProvider.kt` | убраны `?.` на non-null (`regime`, `realizedPnl`) | предупреждения | без изменений |
| 10 | `repository/FrozenStrategyRepository.kt`, `repository/DeploymentApprovalRepository.kt` | `java.lang.Double/Integer` **оставлены** (R2DBC `row.get`/`bindNull` требует boxed-типы), предупреждение подавлено `@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")` | обоснование | suppression вместо неверного «исправления» |
| 11 | `client/AlorClient.kt`, `client/RestOrderTransport.kt` | **P0: самоссылка в Resilience4j-цепочке.** `call = registry....decorateSuspendFunction { call() }` захватывал мутабельную переменную по ссылке → каждый декоратор вызывал сам себя → `StackOverflowError`. При дефолтах `ALOR_RETRY_ENABLED/ALOR_RATE_LIMITER_ENABLED/ALOR_CIRCUIT_BREAKER_ENABLED=true` это ломало **любой** REST-запрос `AlorClient` и **любую** заявку `RestOrderTransport` в SIM/LIVE. Исправлено immutable-цепочкой (`val inner = call`), как уже было в `ResilientLlmClient`/`RgRuNewsProvider`; регресс-тесты `alor client resilience decorators keep market data delivery working`, `rest order transport resilience decorators preserve a successful delivery` | **дефект (исправлен)** | транспорт больше не падает при включённых декораторах |

**Итог:** `compileKotlin` — **41 предупреждение → 0**.

## 3. Что признано мёртвым и оставлено осознанно

Defensive-guards не удалялись: они защищают от вырожденных данных, и их удаление
превратило бы деградацию данных в падение или во вход по мусорной цене.

- `EntryFilters.orbDirection`: `rangeStart == null` недостижим (бар сам проходит
  порог `barMinutes >= orbWindowStartMinutes`), но `maxOfOrNull/minOfOrNull == null`
  достижим при `orbWindowBars = 0` — оставлено.
- `EntryFilters.macroTrendDirection`: `!ema.isFinite()` недостижим на конечных
  `BigDecimal`, но `ema <= 0` достижим (нулевые цены) — проверка оставлена целиком.
- `EntryFilters.panicReversalDirection`: `!rsi.isFinite()` недостижим
  (`IndicatorCalculator.rsi` возвращает `50.0`/`100.0`/конечное число).
- `EntryFilters.vwapMrDirection`: `vwap == null` недостижим после проверки
  `window.isEmpty()` — оставлено как контракт на будущее.
- `PositionRepository`: `?: default` для `NOT NULL DEFAULT`-колонок (27 ветвей) —
  схема БД гарантирует не-null, но fallback дешевле, чем падение на старых строках.
- `BacktestEngine`: ветви `stopLoss == null` / `takeProfit == null` — легитимны для
  стратегий без стопов (не мёртвые).
- `MoexOrderLogParser.bboBucketsCrossed` — счётчик статистики, используется в отчётах.

## 4. Что сознательно НЕ менялось

| Пункт | Почему |
|---|---|
| Склейка фьючерсных контрактов, back-adjustment (`candles.contract`) | Меняет данные БД и все ряды; отдельный этап с перезагрузкой Si/RI |
| Блокирующий R2DBC `.block()` в `DailyRiskSnapshotRepository` | Hot-path уже использует `*Await`-варианты; оставшиеся вызовы вне event loop (offload в IO/фон). Перевод на suspend — отдельный рефакторинг с риском для drawdown-пути |
| MDC в корутинах | Наблюдаемость, не влияет на торговлю; требует аккуратного `MDCContext()` во всех launch |
| `runBlocking` в halt-путях | Durability важнее latency: halt обязан завершиться до выхода |
| Бэкап БД перед авто-миграциями | Ops-процедура (вне кода приложения) |
| Пустой фолд возвращает формальный `BacktestResult` | Требует нового поля `dataInsufficient` + изменения агрегации WFA; вынесено в открытые пункты |

## 5. Сборка и проверки

- `engineVersion` остаётся **`2026-10-26.2`**: все правки семантически нейтральны
  (удалён мёртвый код, сняты предупреждения), P&L-логика не менялась.
- `test` + `integrationTest` + `ktlintCheck` — зелёные (см. §6).
- Покрытие: LINE 97,11 %, BRANCH **90,14 %** (9 564 / 10 626) — цель 90 % достигнута,
  тестов 3 604, падений 0.
- Артефакт: `build/libs/trading-bot-2.0.0.jar` (имя не менялось, чтобы не ломать
  research-скрипты, завязанные на путь), размер 126 848 786 байт,
  SHA-256 `9AA0E10402F845E166F03EA5BC28FFEC99B58E9AD70ED4823DC0010E8827DB66`.
  Сборка пересобрана **после** фикса P0 Resilience4j (см. §2, п. 11).

## 6. Рестарт исследований: да, начинаем заново

**Ответ на вопрос «все исследования нужно начать заново?» — да, все численные
результаты стратегий.** Причина не в этой чистке (она нейтральна), а в аудите
2026-10-25/26 (`docs/28` §2): исправления P&L-семантики движка
(`engineVersion 2026-10-26.1` и `.2`) аннулировали прежние числа, что и
зафиксировано в `docs/29` (RESEARCH RESET).

Порядок нового раунда:

1. **Данные валидны:** свечи (MINUTE_10/HOUR_1/DAY_1), `funding_history`
   (SWAPRATE), ставки ЦБ/RUONIA, forward-микроструктура — не пересчитываются.
2. **Числа невалидны:** все таблицы «Калибровки», docs/17/20/24/25/26/27 и
   `artifacts/*wfa*/*gate*/*holdout*/*robustness*` — INVALID (provisional).
3. **Стартовая точка:** baseline на исправленном движке —
   `artifacts/baseline_engine_2026-10-26_2.json` (CNYRUBF 730д, maxC=1, Kelly):
   Return **+0,026 %**, PF 1,012, Sharpe 0,054, 82 сделки, **P(noEdge)=0,486**,
   `passable=false`. Это и есть «нулевая гипотеза» нового раунда: edge не обнаружен.
4. **Протокол:** новый предрегистрированный протокол (`docs/30` §7) —
   IS/OOS-разделение фиксируется до прогона, вердикт только по OOS/holdout,
   гейты §4 (OOS PF ≥ 1,30; P(noEdge) < 0,05; ≥ 30 OOS-сделок; dev-consistency ≥ 0,60).
5. **Дисциплина артефактов:** каждый результат обязан содержать `engineVersion`
   и commit SHA; артефакты со старой версией движка не сравниваются с новыми.

## 7. Открытые пункты после чистки

- Пустой фолд (`candles` пустой после фильтрации) возвращает формальный
  `BacktestResult` без признака невалидности и участвует в агрегации WFA —
  добавить `dataInsufficient` и исключать такие фолды (меняет агрегацию,
  поэтому отдельным предрегистрированным шагом).
- Склейка фьючерсных контрактов (Si/RI) с back-adjustment — блокирует
  исследование на непрерывных фьючерсных рядах.
- MDC/наблюдаемость в корутинах — не влияет на торговлю.
