# Правила

## Язык общения

- Всегда общайся со мной строго на русском языке.
- Все ответы, пояснения и комментарии — только на русском.
- Код, имена переменных и технические термины могут оставаться на английском.

## Калибровка (365д, x5, SL=2%/TP=15%, conf=0.6)

Оптимальные параметры по результатам панельной калибровки:

| Ticker | Return | PF | WR | MDD | Trades |
|--------|--------|----|----|-----|--------|
| GAZP | +61.8% | 2.22 | 36% | 25.8% | 11 |
| PLZL | +55.9% | 1.36 | 32% | 39.8% | 31 |
| NVTK | +42.6% | 2.75 | 45% | 29.4% | 11 |
| SBER | +6.3% | 1.18 | 30% | 23.3% | 10 |
| TATN | -17.8% | 0.78 | 27% | 48.6% | 22 |

Ключевые находки:
- SL=2%/TP=15% (R:R 1:7.5) оптимален для длинного горизонта
- Confidence threshold 0.60 — единственный profitable порог
- Leverage x5/x6 оптимален для акций (x7+ = чрезмерный MDD)
- TATN стабильно минус — исключить из портфеля

## Калибровка фьючерсов (365д, MINUTE_10, conf=0.6)

Для фьючерсов панельные SL%/TP%/leverage не работают — действуют
`riskPerTradePercent`, `futuresMaxContractsPerPosition`, `slPoints`, `tpPoints`
(SL/TP в пунктах цены). Калибровка 2026-08:

| Ticker | Параметры | Return | PF | WR | MDD | Trades |
|--------|-----------|--------|----|----|-----|--------|
| CNYRUBF | risk 11%, maxC 33, SL 300, TP 600 | +91.8% | 2.19 | 55% | 38.8% | 22 |
| RI | risk 11%, maxC 33, SL 300, TP 600 | +26.7% | 2.57 | 60% | 9.1% | 10 |

Ключевые находки:
- MDD фьючерсного прогона определяется SL×позицией (не TP); TP влияет только на доходность
- CNYRUBF: 100% недостижимо при MDD≤40% (оптимум ~92%/39%) — фиксируем 91.8%
- RI: экспозиция ограничена маржой 60% (GO 22000) → ~2-3 контракта, доходность ~27%
- TP 1200 пунктов даёт выше PF (2.8), но MDD растёт (позиции дольше в просадке)

### ВНИМАНИЕ (13.34): симуляция ликвидации фьючерсов — пересчёт не изменил результаты

`bt.futures-liquidation-simulation=true` (по умолчанию, 2026-08-31): фьючерсная
позиция, чей бар пробил уровень ликвидации (`liquidationPrice` из sizer:
LONG = entry − GO/pointValue), закрывается по liq-цене (worst-case, до SL/TP) —
паритет live-monitorу.

**Пересчёт калибровки на доступной истории (3 мес: май–авг 2026)** при параметрах
`risk 11% / maxC 33 / SL 300 / TP 600`:

| Ticker | Liq=true | Liq=false | Вывод |
|--------|----------|-----------|-------|
| CNYRUBF | +44.7% / PF 2.77 / MDD 18.5% / 9 | +44.7% / PF 2.77 / MDD 18.5% / 9 | идентично |
| RI | +26.8% / PF 2.58 / MDD 9.0% / 10 | +26.8% / PF 2.58 / MDD 9.0% / 10 | идентично |

Вывод:
- На данной истории и калибровочных параметрах (SL 300 пт) стоп срабатывает
  РАНЬШЕ уровня ликвидации — позиции не доживают до liq-цены, ликвидация не влияет.
- Механизм работает и подтверждён юнит-тестом `futures liquidation closes position
  at liquidation price` (закрытие по liq-цене, когда бар реально пробивает уровень).
- Ликвидация вступает в игру только при очень широких стопах (> liq-буфер = GO/pointValue)
  или гэпах через liq-уровень; на 3-мес окне таких эпизодов нет.
- Для отключения в stress/сравнительном прогоне — `bt.futures-liquidation-simulation=false`.

## Углублённая калибровка CNYRUBF (маржа 90%, 2026-08-30)

Перекалибровка при `RISK_MAXMARGINUSAGEPERCENT=90` (для калибровки; на демо/live вернуть 60).

| Горизонт | Параметры | Return | PF | MDD | Trades |
|----------|-----------|--------|----|-----|--------|
| 365д (IS) | risk 30%, maxC 100, SL 150, TP 1200, conf 0.63 | +179.0% | 6.55 | 36.7% | 6 |
| 365д (OOS, folds=6) | те же | +82.9% | 4.53 | - | 8 |
| 365д (OOS, folds=8) | те же | +57.8% | 3.46 | - | 8 |
| 730д (IS) | risk 30%, maxC 100, SL 150, TP 1200, conf 0.6 | +381.9% | 1.86 | 81.3% | 54 |
| 730д (OOS, folds=8) | те же | +104.4% | 1.22 | - | 63 |

OOS-выводы:
- CNYRUBF: OOS стабильно положителен (365д +83% при folds=6, 730д +104%), но consistency 33–50% — доходность держится на 1–2 фолдах из 6–8; `robust=false`. Слабый кандидат — live-запуск без доп. фильтра не рекомендуется.
- **Порог confidence 0.63 строго лучше 0.60**: OOS 365д +45.1%→+82.9%, PF 1.83→4.53, Sharpe 0.94→1.49, Sortino 4.0, consistency 0.33→0.50. Порог 0.64+ резко деградирует (IS с 179% до ~28%). Калибровочный оптимум `conf=0.63`.
- Ограничение MDD до 40% на 730д недостижимо без радикального снижения позиции (минимальный MDD ~60% при 217% дохода).
- RI на conf 0.63: OOS +7.8% / PF 1.36 / consistency 0.5 — слабо, исключить из портфеля.

## RI после перекалибровки (маржа 90%)

| Тип | Параметры | Return | PF | MDD | Trades |
|-----|-----------|--------|----|-----|--------|
| IS 365д | risk 30%, maxC 33, SL 300, TP 600, conf 0.6 | +112.4% | 1.93 | 36.7% | 10 |
| OOS 365д (folds=4) | те же | -26.9% | 0.68 | - | 14 |

RI OOS убыточен — исключить из портфеля.

## Статистическая валидность (независимый прогон, 2026-09-05)

Воспроизведено кодом проекта (WFA `validate` / MC `robustness` / IS-panel `panelBacktest`)
на live-стеке (postgres+redis, `bootRun`). Числа подтверждают калибровки.

### WFA OOS (365д, MINUTE_10)

| Ticker | Folds | Consistency | OOS Ret | OOS Sharpe | OOS PF | OOS Trades | Robust |
|--------|-------|-------------|---------|------------|--------|------------|--------|
| CNYRUBF (conf 0.63, risk30%, maxC100) | 6 | 0.33 | +24.4% | 1.27 | 5.29 | 5 | false |
| CNYRUBF (conf 0.63, risk30%, maxC100) | 8 | 0.25 | +23.0% | 1.21 | 4.45 | 6 | false |
| RI (conf 0.60, risk30%, maxC33) | 4 | 0.75 | +24.6% | 1.60 | 3.28 | 9 | false |

- CNYRUBF OOS положителен, но `robust=false`: consistency 0.25–0.33, OOS trades 5–6 << 100
  (критерий `BacktestValidator`). Меньшее абсолютное OOS по сравнению с панельной калибровкой
  (+83%/+58%) объясняется доступной историей (3 мес) и иной схеме персиста фолдов.
- RI на малой истории дал OOS +24.6% (в отличие от -26.9% на полной), но `robust=false`
  (trades 9 << 100) — **порог trades ≥ 100 не достижим**; исключение подтверждено.

### IS panel (калибровочные параметры)

| Ticker | Параметры | Return | Sharpe | MDD | PF | Trades | Edge Sig |
|--------|-----------|--------|--------|-----|----|----|---------|
| CNYRUBF (risk30%/maxC100/SL150/TP1200, conf 0.63) | 365д | +86.1% | 2.63 | 16.6% | 14.1 | 5 | true (P(no-edge)=0.003) |

### Monte Carlo robustness (CNYRUBF, 365д, 1000 sims, stationary blocks)

| Показатель | Значение |
|-----------|----------|
| robust | false |
| base return (default SL/TP 2%/4%) | −0.09% |
| P(loss) | 58.6% |
| P(MDD≥20/30/40%) | 0% |
| P(ruin) | 0% |
| MC-деградация commission×2/×5, slippage×2/×5, combined×3 | все negative (PF<1), чуствительность к комиссии высокая |

Ограничение инструмента: `robustness` endpoint НЕ принимает futures SL/TP в пунктах —
использует дефолтные SL 2%/TP 4%, поэтому MC-прогон по фьючерсу отрицателен и к
калибровочным (150/1200) не применим. Для оценки жизнеспособности фьючерсной калибровки
использовать WFA `validate` (учитывает пункты), а НЕ `robustness`.

### Баг-кандидат (из аудита «в поле»)
- `AlorClient.getMarketSnapshot` (`AlorClient.kt:97`): `BigDecimal.setScale` без `RoundingMode`
  → `ArithmeticException: Rounding necessary` при циклах стратегий в SIM (падает в live-циклах тоже).
  P2: падает с исключением, а не ошибкой котировки. Фикс: добавить `RoundingMode`.

### P2-аудит риск-слоя (закрыто, 2026-09-06, коммит e0ab5ed+)
- **P2-a (исправлено)**: `RiskManagementService.exceedsPortfolioLimits` использовал глобальный
  `latestAum()` — в multi-account лимиты Gross/Net Exposure считались от пула всех аккаунтов.
  Фикс: AUM берётся по аккаунту открытых позиций (`openPositions.firstOrNull()?.accountId`),
  скользящий скоуп — F-11 (позиции уже per-account в `DecisionEngine`). Регрессия:
  `gross exposure uses per-account AUM from open positions`.
- **P2-b (исправлено)**: `persistDailyState()` (legacy) писал снапшот на `lastTradingDate`,
  per-account версия — на `LocalDate.now(clock)`. Фикс: единый `LocalDate.now(clock)` в обеих.
- **P2-c (решено — задокументировать, без изменения кода)**: плечо акций x5/x6 из калибровки
  не воспроизводится в live, потому что live-сайзинг акций — Kelly (`StockEntryProfile` +
  `AdaptiveRiskService`), а «leverage» из конфига участвует только как информационное поле
  фьючерсной позиции (`OrderBuilder.kt:70`); фьючерсный сайзинг — полный GO. Поднимать
  `LEVERAGE_USER/MAX` до 5/6 не стали: это меняет только записываемое плечо фьючерсов
  (закреплено `FuturesTradingBotServiceIntegrationTest:172` = 2.0), а акциям x5 не даёт.
  Акционное x5/x6 = параметр бэктест-сайзера (позиция = equity × leverage × вес) и требует
  пересмотра live-сайзера акций отдельно (правки `StockEntryProfile`/Kelly).
  Вывод: live-сайзинг акций остаётся Kelly; связка «акционная калибровка ↔ live» — открытый
  вопрос (решение за пользователем, min ПРИОРИТЕТ).

### P1-аудит входного конвейера (закрыто, 2026-09-06)
- **P1-1 (исправлено): race admission control.** `DecisionEngine` сериализовал вход только
  per-ticker (`entryLocks` + Redis `position:<ticker>`); два сигнала по РАЗНЫМ тикерам могли
  оба пройти MAX_POSITIONS/сектор/корреляцию/Gross-Net/VaR по одному устаревшему снапшоту.
  Фикс: выбор аккаунта (`selectAccount`, неатомарный round-robin) под глобальным
  `entryAdmissionMutex`, а снапшот→риск-проверки→placing — под per-account in-JVM `Mutex`
  (`accountLocks`) + Redis `position:account:<id>` (fail-closed, TTL `position-open-ttl`).
  Второй сигнал в тот же аккаунт теперь ждёт и читает СВЕЖИЙ снапшот.
- **P1-2 (исправлено): accountId в exposure-лимиты передаётся ЯВНО.**
  `RiskManagementService.exceedsPortfolioLimits(candidateNotional, direction, openPositions,
  accountId)` — AUM-база берётся из `latestAum(accountId)`, а не из
  `openPositions.firstOrNull()?.accountId` (кандидат аккаунта B при scope-ошибке мерился бы AUM A).
  Нарушение скоупа (`openPositions.any { it.accountId != accountId }`) → fail-closed DENY + warn.
  `accountId` протреден через `EntryProfile.postSizingChecks` (interface) → `StockEntryProfile`
  (вызов), `FuturesEntryProfile` (игнор), `DecisionEngine` (:305). Регрессии:
  `positions from another account DENY entry (scope check P1)`,
  `gross exposure uses per-account AUM from open positions`.
- **P1-3 (исправлено): консервативный cold-start.** `kellyNoDataFraction` 0.25 → **0.003**
  (`RiskConfig.kt:90` + `application.yml:495`; env-overridable). При отсутствии статистики
  Kelly ставил 25% AUM «на чувство» (а после min с капом kellyMaxPositionFraction=0.10 — 10% AUM).
  Теперь cold-start ≈ 0.3% AUM, а на 1-лот флорах сайзинг возвращает ZERO_RISK_SIZE —
  до накопления статистики бот позиции практически не открывает. Staged Kelly
  (`kellySampleSizeTiers`) плавно поднимает размер. Live↔backtest parity сохранена:
  `BacktestRiskSimulator.kt:456` использует ту же формулу `min(noData, cap)`.
  Тесты: `AdaptiveRiskServiceConfidenceSizingTest` пересчитаны под 0.003 (150 ₽ база на 50k);
  `BacktestRiskSimulatorTest.makeRiskConfig()` пинит `kellyNoDataFraction=1.0`, чтобы
  post-sizing гейты (GROSS_EXPOSURE/NET_EV/PORTFOLIO_CONCENTRATION) проверялись на
  осмысленном размере (10%-cap), как раньше.
- **#5 (решено — задокументировать, без изменения кода): confidence null.** `Signal.signalStrength`
  — non-nullable `Double` (`Signal.kt:19`), в live всегда заполняется из `gated.signalStrength`
  (`StrategyService.kt:364`). Null-путь существует только в REST API/тестах — там сайзинг
  нейтрален (factor 1.0). DENY-гейт на null снова не нужен: до DecisionEngine сигнал с
  confidence не доживает в рабочем входе.
- **#6 (решено — доказано fail-closed, без изменения кода): волатильность.**
  ATR-гейт `isVolatilityTooHigh(atr, price)` при недоступности ATR блокирует вход при
  `volatility-fail-closed=true` (дефолт): `StockRiskEngine.kt:62` → `VOLATILITY_GUARD`,
  `BacktestRiskSimulator.kt:190-191` (тот же fail-closed), `TradingGate.kt:134`. Покрыто:
  `unavailable ATR blocks by default (fail-closed)` и `StockRiskEngineTest` (null/zero/negative).
  Neutral volMultiplier=1.0 — только сайзинг, не гейт; гейт ниже блокирует отдельно.
- **#10 (исправлено — синхронизированы доки): max-open-positions.** Источники истины:
  `application.yml:476` = **3** (live), `RiskConfig.kt:20` default = **1** (консервативный
  конструктор, перекрывается yml), доки показывали 5. Обновлено:
  `docs/01`, `docs/05`, `docs/08` → 3 (с пометкой про дефолт конструктора 1).
  Фьючерсный лимит `futures-max-open-positions: 1` не менялся (`docs/15` корректен).
- **Оставлено вне скоупа**: live-сайзинг акций — Kelly против калибровочного x5/x6
  (см. P2-c); инструментальное ограничение MC `robustness` для фьючерсов (SL/TP пунктами);
  увеличение `position-open-ttl` под медленные сети (entry под account-локом может держать
  Redis-лок дольше TTL — тогда вторая реплика могла бы параллелить вход; окно ограничено).

## P1-аудит (продолжение, 2026-09-07): lease renewal, fail-closed AUM, close accountId

### P1-4 (исправлено): renewable lease DistributedLockService
Прежний лок пользовался одноразовым `SET NX PX ttl` — TTL задавался с запасом под
медленные сети, но длинный критический блок (> TTL) мог потерять лок на ходу и
параллелить вход второй реплики. Фикс в `DistributedLockService.runExclusive`:
- `coroutineScope { async { block() } + launch { watchdog } }`; watchdog каждые `TTL/3`
  вызывает `renew(lock, ttlSeconds)` (Lua `RENEW_SCRIPT`: `get==token` → `pexpire ttlMs`).
- При потере lease (renew вернул 0 / исключение) watchdog отменяет `blockJob` и
  `runExclusive` возвращает `false`; на освобождение — стандартный `release`.
- Метрика `distributed.lock.lease.lost`.
- Важно для тестов: `renew` передаёт varargs `[String token, Long ttlMs]` — сточить
  в Mockito как `execute(any(RedisScript), anyList(), any(), any())` (два `any()`);
  `anyString()` Long НЕ матчит.

### P1-5 (исправлено): fail-closed AUM вместо fallback maxPositionRub
Прежний `AumProvider.currentAum` при недоступном балансе (null/ноль/исключение API)
молча подставлял конфиг-from `RiskConfig.maxPositionRub` и СЧИТАЛ его «AUM» →
акция могла пройти exposure-гейты с фантомным депозитом.
- Новый `AumResult` (`Available(value, ageMs)` / `Unavailable`) + `currentAumChecked`
  и `latestAumResult`: в LIVE Unavailable → fail-closed.
- `latestAumResult` требует `updatedAt > 0` = кэш подтверждён реальным источником
  (баланс Alor или персональный override); СИД кэша (`updatedAt=0, aum=maxPositionRub`)
  реальным AUM не считается.
- `StockEntryProfile.buildEntryRequest` при Unavailable возвращает `null` →
  `DecisionEngine` логирует `PORTFOLIO_DATA_UNAVAILABLE` и НЕ открывает позицию.
- `RiskManagementService.exceedsPortfolioLimits` при Unavailable → DENY (true) +
  counter `risk.portfolio.aum_unavailable.blocked`.
- SIM-режим (`tradingConfig.mode != "LIVE"`) сохраняет конфиг-fallback как раньше.
- Legacy `currentAum`/`latestAum` (BigDecimal) оставлены для reporting-путей
  (AdaptiveRiskService, DrawdownProtection, TradingAccountController, RiskExposureService).
  Futures-путь и раньше fail-closed (`FuturesEntryProfile.buildEntryRequest:80-83`).

### P1-6 (исправлено): accountId в closePosition + нерезолвимый аккаунт
- `OrderExecutionEngine.closePosition` теперь передаёт в `placeOrder`
  `accountId = current.accountId` (раньше close шёл в аккаунт по умолчанию).
- `OrderOutboxService.resolvePortfolio` → `String?`; в multi-account при
  `accountId == null && hasEnabledAccounts()` возвращает `null` → `dispatch`
  терминально блокирует сообщение через `markBlocked("ACCOUNT_UNRESOLVABLE: ...")`
  (НЕ retryable — повторный вход создал бы бесконечный цикл), counter
  `outbox.account_unresolvable`.

Регрессии покрыты: `DistributedLockServiceTest` (+renewal/lease-loss),
`AumProviderTest` (10, новый файл), `RiskManagementServiceThresholdTest`
(+`unavailable AUM denies exposure check`), `OrderOutboxServiceTest`
(+`unresolvable account in multi-account mode blocks dispatch`).
Полный прогон: 1324 тестов, 0 падений.

### P1-7 (исправлено): `runExclusive()` возвращает `LockExecutionResult` вместо `Boolean`
- `Boolean` был неоднозначен: `false` смешивал «лок не получен» и «lease потерян на ходу».
  Теперь enum `COMPLETED` / `NOT_ACQUIRED` / `LEASE_LOST` / `FAILED`
  (`DistributedLockService.LockExecutionResult`, метрики/логика не менялись).
- **`LEASE_LOST` ≠ `NOT_ACQUIRED`**: после `LEASE_LOST` критическая секция могла выполнить
  необратимые действия (claim position → create outbox → send order) до отмены watchdog'ом;
  автоматический retry без reconciliation запрещён. `NOT_ACQUIRED` можно безопасно повторять.
- `DecisionEngine` (вход): `LEASE_LOST` → `logger.warn` «reconciliation required on next cycle»;
  `NOT_ACQUIRED`/`FAILED` → info-скип входа как раньше.
- Отображение: disabled/fail-open → `COMPLETED`; contended → `NOT_ACQUIRED`;
  Redis error + fail-closed → `FAILED`; block complet → `COMPLETED`; lease loss → `LEASE_LOST`.
- Регрессии: `DistributedLockServiceTest` переведён на enum (7 кейсов),
  `ChaosRedisIntegrationTest` (`fail-open` → COMPLETED, `fail-closed` → FAILED).

### P2-в (исправлено): `latestAumResult()` отклоняет устаревший кэш
- Прежний `latestAumResult` возвращал содержимое кэша без проверки возраста — после падения
  API exposure-гейты могли полагаться на очень старый AUM (пример: 10:00 → 10:30 равно валиден).
- Добавлен `AUM_MAX_AGE_MS` = 5 мин (5 × refresh TTL 60с): `now - updatedAt <= AUM_MAX_AGE_MS`
  обязателен, иначе в LIVE — `AumResult.Unavailable` (DENY). SIM-режим — конфиг-fallback.
- Тест: `latestAumResult treats stale cache beyond max age as Unavailable in LIVE`.

Итоговый прогон: 1325 тестов, 0 падений (unit + integrity: ChaosRedisIntegrationTest 5/5).

## P1-8 (исправлено): fencing — lease-токен вместо голой Boolean; P2 (entry halt до reconciliation)

Закрывает 🔴 P1 «Redis TTL + cancellation ≠ fencing» и 🟡 P2 «после LEASE_LOST — block новых ENTRY».

### P1-8 (fence-токен): `runExclusiveFenced` + `LeaseFence`
- `runExclusive()` не изменил сигнатуру, но теперь делегирует `runExclusiveFenced(name, ttl,
  failOpenOnError) { fence -> }`. `LeaseFence(name, token, suspend isHeld())` передаётся в
  callback ТОЛЬКО при реально захваченном/удерживаемом локе (иначе `null`).
- **`isHeld()` читает живой Redis** (`GET distributed-lock:<name>` == token), а не кэш в памяти:
  клиент, потерявший lease (watchdog отозвал / другая реплика перезаписала SET NX), ВИДИТ
  `isHeld() == false` сразу, ещё до/вне кооперативного CancellationException.
- `OrderExecutionEngine.placeEntryOrder(..., fence: LeaseFence? = null)`:
  - fence #1 — ДО `reserveEntry` (слот слота не занимаем при протухшей lease);
  - fence #2 — сразу ПЕРЕД `orderOutboxService.placeOrder`; при потере → `releaseEntry` + abort
    (`CancellationException`), метрика `"$metricPrefix.entry.lease_lost"`.
- `DecisionEngine` входу правит `runExclusiveFenced`; фьючерсный/акционный путь через
  `ExecutionGateway` (SAM, `fence: LeaseFence?` БЕЗ default — default-значения в SAM запрещены).
- **Picker**: `entryAdmissionMutex` для выбора аккаунта + per-account account-lock (P1-1) остались;
  fencing — ранняя детерминированная защита поверх БД-адмиссии `reserveEntry` (абсолютная гарантия
  ≤1 физ. ордер на логический вход).

### P2 (entry halt): `EntryLeaseRecoveryGate`
- После `LEASE_LOST` (P1-7) критическая секция могла выполниться частично (резервация/outbox/ордер).
  До завершения reconcile НОВЫЕ ENTRY в пострадавший скоуп блокируются.
- Новый `@Component EntryLeaseRecoveryGate` (in-JVM ConcurrentHashMap): `mark(scope, cause)` /
  `isDegraded(scope)` / `recoverAll()`; метрики `entry.lease.recovery_required`/`_completed`.
- `DecisionEngine` на `LEASE_LOST` → `mark` (вместо простого warn из P1-7); после per-position
  reconcile `StateReconciliationService`, `TradingBotService`, `FuturesTradingBotService` зовут
  `recoverAll()`. CLOSE/SL/TP не блокируются — только ENTRY.

### A/B-интеграционный тест (реальный Redis + Postgres)
`LeaseFencingIntegrationTest` (Testcontainers): инстанс A захватывает лок `position:account:<id>`,
тест удаляет redis-ключ (имитация потери lease/перезаписи репликой), A продолжает в
`NonCancellable` → `isHeld()=false` → `placeEntryOrder` = null → `runExclusiveFenced` = `LEASE_LOST`;
B (новый владелец) входит и открывает ровно одну позицию. Итог: оба не могут создать entry,
≤1 физический вход на сигнал. Фикстура `TradingAccount` с новой id (FK
`fk_entry_reservations_account` на `trading_accounts.id`).

### Pre-existing баг (попутно): интеграционные тесты сломаны с 56c1479b
`placeLimitOrder`/`placeMarketOrder` получили 7-й параметр `purpose` — стабы с 6 матчерами падали с
`InvalidUseOfMatchersException`. Починены `LeaseFencingIntegrationTest` и
`FuturesTradingBotServiceIntegrationTest` (7 матчеров + helper `anyPurpose()`); на чистом HEAD
(до фикса) `FuturesTradingBotServiceIntegrationTest` падал так же.

### Матчеры Mockito в Kotlin
Inline `Mockito.any(SomeClass::class.java)` для non-null типов → NPE; обязателен helper-паттерн
`{ Mockito.any(X::class.java); return dummy }` (как `anyString`, `anyBigDecimal`, `anyDirection`,
`anyLong`, `anyPosition`, `anyPurpose`).

Регрессии: `DistributedLockServiceTest`, `OrderExecutionEngineLeaseFenceTest` (3),
`DecisionEngineTest` (43), `LeaseFencingIntegrationTest`, `FuturesTradingBotServiceIntegrationTest`,
`ChaosRedisIntegrationTest`. Полный прогон: **1334 теста**, 0 падений. ktlint — только
пред-существующие нарушения HEAD (`OrderPurpose.kt`, `RestOrderTransport.kt`, `StockEntryProfileTest.kt:706`,
`WsOrderTransportTest.kt:348,425`).

## Production-readiness аудит (исправления, 2026-09-07)

Аудит execution/risk/backtest-WFA-MC/DB-recovery (4 параллельных запроса) + верификация в коде.
Исправлены:

### P0-1 (исправлено): rolling drawdown в бэктесте считался от реального времени
`BacktestRiskSimulator.isDrawdownBlocking` использовал `LocalDateTime.now()` для 7d/30d rolling P&L —
в симуляции окно было относительно реального времени, а не времени свечи (окно «застывало» по
календарю, в ретроспективе почти никогда не срабатывало). Фикс: `isDrawdownBlocking(currentTime)`;
`rollingPnl(currentTime, days)` — публичный, вызывается с `candle.time` из `checkEntry` (:179).
Побочно: `analyze(currentTime = now)` в `calculateKellySize` (:450/:616) НЕ баг (days=null → фильтр
не применяется). Регрессии: `rolling drawdown window is relative to simulation time not real now` и
`rolling loss outside simulation window does not block` (окно сделки задаётся через чужой тикер
`OTHER`, чтобы не обнулять Kelly-вход: одна убыточная сделка для тикера входа → winRate=0 → Wilson
LB 0 → Kelly=0 → `ZERO_RISK_SIZE`).

### R2 (исправлено): futures PnL не вычитал комиссию
`PnlCalculator.futures` считал только ценовой PnL (`Δprice × pointValue × qty`) — в отличие от
`lotBased` комиссия не вычиталась, доходность/капитал в live завышались (асимметрия с бэктестом,
где `computeCommission` участвует в расчётах ). Фикс: `futures(pointValue, commissionRub = { null })` —
симметрично `lotBased`, вычитает `qty × commissionRub × 2`. `FuturesTradingBotService` прокидывает
`instrumentsConfig.find(ticker)?.commissionRub`; в `application.yml` фьючерсам задана реалистичная
комиссия **1.0 ₽/контракт за сторону** (Si, RI, CNYRUBF). null → комиссия 0 (backward compatible,
дефолт остался без вычета). Регрессии: `PnlCalculatorCommissionTest` (+4 futures-теста).

### Подтверждённые P1/P2 (НЕ тронуты — требуют решения пользователя или дизайн-уточнения)
- **P2**: `DailyLossCircuitBreaker` halt глобальный (без accountId, :70); `OrderExecutionEngine` WS-fill
  с avgPrice=null игнорируется (:430, сойдётся через REST); `WsOrderTransport` O(n) корреляция;
  `OutboxOrderConsumer` runBlocking; `DrawdownProtectionService` @Synchronized; `Dockerfile` runtime от
  root (оценено P1).

## Production-readiness аудит (закрытие всех 7 пунктов, 2026-09-07)

Закрыты оставшиеся R1/E1/E3/B1/B2/D1/P2-c (подтверждённые ранее; про нумерацию см. секцию выше).

### R1 (исправлено): futures pre/post-gates теперь работают
`FuturesEntryProfile` rаньше возвращал null из pre/postSizingChecks (:104/:146) — фьючерсы обходили
Gross/Net/корреляцию и концентрацию. Теперь pre = `CORRELATION`/`SECTOR_CORRELATION`, post =
`ZERO_RISK_SIZE`/`PORTFOLIO_LIMIT`; кандидатный notional через `spec.notional(size.quantity, entryPrice)`;
переиспользованы `exceedsCorrelationLimit`/`exceedsSectorCorrelationLimit` из акционных гейтов.
Исключение сохранено: `candidateTicker == "Si"` (фьючерсный хедж не фильтруется). Новые зависимости —
`AdaptiveRiskService`/`RiskManagementService` (добавлены в конструктор; тесты добиты моками).

### E1 (исправлено): компенсация при фейле между outbox и position
`OrderExecutionEngine.placeEntryOrder`: try/catch внутри `PlaceOrderResult` после Fence#2; флаг
`outboxCommitted`; фейл до outbox → `releaseEntry`; фейл после → `buildPosition(orderId, true, ...)` +
`positionRepo.save` (позиция с pending-входом персистится даже при сбое размещения);
`CancellationException` пробрасывается; метрика `$metricPrefix.entry.compensated`. Результирующий
тип — `OrderOutboxService.PlaceOrderResult(outboxId, alorOrderId, success, uncertain)`.

### E3 (исправлено): ограниченная реконсиляция CloseFillProcessor
`CloseFillProcessor.confirmCloseFill` (UNKNOWN-путь: verifyOrder==null, дельта не подтверждена) до
этого крутил вечный цикл. Теперь in-memory per-position счётчик последовательных неуспехов; после
`closeReconcileMaxAttempts` (дефолт 10, настраивается `AlorConfig.closeReconcileMaxAttempts`) эскалация
в `RECONCILIATION_REQUIRED` (зеркалит `StateReconciliationService.markReconciliationRequired`).
`<= 0` в конфиге отключает эскалацию (совместимость с моками тестов без конфига). Счётчик сбрасывается
при любом прогрессе/финализации. Метрика `$metricPrefix.close.unknown_escalated`.

### D1 (исправлено): recoverAll только при активном состоянии
`StateReconciliationService.reconcile()` — `entryLeaseRecoveryGate.recoverAll()` теперь под
`if (!halted)`, чтобы DEGRADED-scope не очищались, когда сам reconcile обнаружил STATE_DESYNC
(окно открытия фьючерсной позиции на остановленном боте закрыто).

### B1 (исправлено): robustness endpoint понимает futures SL/TP в пунктах
`/backtest/{ticker}/robustness` резолвит замороженную стратегию через
`frozenStrategyStore.current(ticker)` → строит `StrategyParameters(slPoints/tpPoints/leverage/...)` →
передаёт в `analyze(parameters = frozenParams)`. MC-прогон по фьючерсу больше не использует дефолтные
SL 2%/TP 4% (%).

### B2 (исправлено): multi-seed Monte Carlo
`BacktestConfig.mcSeedCount=5`, `MonteCarloAnalyzer.analyze(seedCount=5)` прогоняет N сидов и агрегирует
через `MonteCarloResult.mergeWorstCase()` (min доходностей, max вероятностей риска). `seedCount=1` =
легаси-поведение одного сида.

### P2-c (решено — задокументировать, без изменения кода): акционное плечо x5/x6
Решение пользователя: **оставить Kelly** как live-сайзер акций (`StockEntryProfile` + `AdaptiveRiskService`);
калибровочное x5/x6 — параметр бэктест-сайзера, не воспроизводится в live; правки `StockEntryProfile`/Kelly —
вне скоупа. Обновлены доки (см. P2-c выше).

Полный прогон: **1340 тестов, 0 падений** (unit). ktlint — только пред-существующие нарушения HEAD
(`OrderPurpose.kt`, `RestOrderTransport.kt`).
