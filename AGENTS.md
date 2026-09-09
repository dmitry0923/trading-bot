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

## Статвалидация CNYRUBF/RI (независимый прогон, 2026-09-09)

Прогон на live-стеке (postgres+redis, `java -jar` с `--spring.mvc.async.request-timeout=600000` —
дефолтный async-таймаут контейнера ~30s резал WFA/gate по `AsyncRequestTimeoutException`).
История в БД: **июн–сен 2026 (~3 мес)**, MINUTE_10, **с новой моделью издержек**
(брокер 1.0 + биржа 0.5 ₽/контракт/сторона, slippage, funding — коммит `0b32f4d`).
WFA-тюнинг SL/TP — по сетке `futuresGrid` (max TP 600), не калибровочные 150/1200.

### WFA `validate` (365д, MINUTE_10)

| Ticker | conf | Folds | Consistency | OOS Ret | OOS Sharpe | OOS PF | OOS Trades | Edge Sig |
|--------|------|-------|-------------|---------|------------|--------|------------|----------|
| CNYRUBF (risk30%, maxC100) | 0.63 | 6 | 0.167 | +3.56% | 0.26 | 1.195 | 6 | нет |
| CNYRUBF (risk30%, maxC100) | **0.60** | 6 | **0.667** | **+77.4%** | 1.91 | **11.66** | 11 | **да (P=0.0035)** |
| CNYRUBF (risk30%, maxC100) | 0.65 | 6 | 0.167 | +3.96% | 0.28 | 1.22 | 5 | нет |
| CNYRUBF (risk30%, maxC100) | 0.63 | 8 | 0.375 | +29.8% | 1.35 | 4.11 | 6 | нет (P=0.09) |
| RI (risk30%, maxC33) | 0.60 | 4 | 0.75 | +60.8% | 1.71 | 3.88 | 9 | да (P=0.049) |
| RI (risk30%, maxC33) | 0.60 | 6 | 0.50 | **−8.7%** | −0.34 | 0.70 | 11 | нет |
| RI (risk30%, maxC33) | 0.63 | 6 | 0.667 | +44.4% | 2.62 | 53.4 | 5 | да (P=0.003) |

- **Conf 0.60 на текущей истории (с издержками) заметно сильнее 0.63 для CNYRUBF**
  (OOS +77.4% / PF 11.66 / consistency 0.667 / edge sig против +3.56% / 0.167 / insign).
  Калибровочный вывод «0.63 strictly better» (2026-08-30) сделан на 365д панели БЕЗ новой
  модели издержек и на другой истории — на 3-мес окне с издержками он НЕ воспроизводится.
  Это повод для повторной чувствительности conf на полной истории после донакачки БД.
- **Conf 0.65 резко деградирует** (OOS +3.96%, consistency 0.167) — согласуется с калибровкой
  «0.64+ режет доходность».
- **RI нестабилен**: OOS скачет от −8.7% до +60.8% при смене folds/conf — подтверждено исключение.

### IS-panel по сетке SL/TP (conf 0.63 для CNYRUBF / 0.60 для RI)

| Сетка | CNYRUBF Ret | CNYRUBF PF | CNYRUBF Trades | RI Ret | RI PF | RI Trades |
|-------|-------------|------------|----------------|--------|-------|-----------|
| 25/50 | −2.5% | 0.72 | 7 | −0.9% | 0.88 | 13 |
| 50/100 | −5.7% | 0.68 | 7 | +5.0% | 1.42 | 12 |
| 100/200 | +7.5% | 1.37 | 6 | −0.0% | 1.00 | 12 |
| 150/300 | +92.8% | 7.94 | 6 | −2.6% | 0.94 | 12 |
| 300/600 | +171.4% | 14.3 | 6 | +47.7% | 2.07 | 9 |
| 150/1200 (kalib) | +174.6% | 13.2 | 5 | – | – | – |

- **CNYRUBF IS-доходность растёт с шириной TP**: TP 600 → +171.4% ≈ TP 1200 → +174.6%;
  узкие сетки (25/50, 50/100, 100/200) убыточны. Трендовый характер — широкие стопы обязательны.
- RI профитен IS только на 300/600 (+47.7%), остальные ~0.

### Deployment-gate (research mode, `LIVE_ALLOWED` недостижим)

| Сценарий | Verdict | Прошло | Провалено |
|----------|---------|--------|-----------|
| CNYRUBF conf 0.63 folds=6 | REJECTED | robustness (p5=+23.8%) | backtest, walk_forward, consistency 0.167, edge, holdout 1 |
| CNYRUBF conf 0.63 folds=8 | REJECTED | robustness (p5=+26.8%) | consistency 0.125, oosTrades 5, holdout 1 |
| CNYRUBF conf **0.60** folds=6 | REJECTED | **consistency 0.667, edge (P=0.026), holdout (2)** | backtest (MDD 37.4%), **robustness (p5=−4.1%, pLoss 6.4%)**, oosTrades 8 << 100 |
| RI conf **0.60** folds=4 | REJECTED | holdout | consistency 0.25, edge (P=0.693), **robustness (p5=−24.7%, pLoss 19.5%)**, oosTrades 9 << 100 |

- Блокер для ВСЕХ сценариев — **тонкая выборка**: 8–11 OOS-сделок против порога 100,
  holdout 1–2 против 30 (ожидаемо на 3-мес истории; `minBarsForSignal=30`).
- CNYRUBF с conf 0.60 проходит consistency/edge/holdout, но падает по **MC-робастности**
  (p5=−4.1%) — доходность хрупка к перестановкам/порядку сделок. НЕ go live.
- CNYRUBF robustness параметры — из WFA-тюнинга (сетка, max TP 600), НЕ калибровочные
  150/1200; standalone `/robustness` вне gate нерепрезентативен (нет frozen-записи — деградирует
  к SL 2%/TP 4%), поэтому MC оценивался ТОЛЬКО внутри deployment-gate.
- **Оговорка по gate**: confidence берётся из конфига `--bt.adaptive-confidence-threshold`,
  request-параметр НЕ принимается (защита от leakage holdout); для сравнения 0.63/0.60
  запускался отдельный bootRun. Edge-проверка `probabilityOfNoEdge` 0.026–0.474 — хрупкая,
  на 5–11 сделках CI-широкие (oostrades мала) — не трактовать как доказанный edge.
- Итог: **live для CNYRUBF/RI НЕ одобрен**; frozen-стратегия не заморожена. Conf 0.60 —
  технически «сильнее» по консистентности/edge, но MC-хрупкость + тонкая выборка —
  снова NO-GO. Перед повторной валидацией — донакачка истории ≥ 6–12 мес.

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
`InvalidUseOfMatchersException`. **Закрыто 2026-09-08**: починены ВСЕ интеграционные суиты добавлением
7-го матчера + helper `anyPurpose()`:
- `LeaseFencingIntegrationTest`, `FuturesTradingBotServiceIntegrationTest` (раньше);
- `InvestorClearingIntegrationTest` (:95, :312), `RabbitMqTransportIntegrationTest` (:86, :120) (сейчас).
Полный `integrationTest`: **100 тестов, 0 падений, 1 skipped**.

### Матчеры Mockito в Kotlin
Inline `Mockito.any(SomeClass::class.java)` для non-null типов → NPE; обязателен helper-паттерн
`{ Mockito.any(X::class.java); return dummy }` (как `anyString`, `anyBigDecimal`, `anyDirection`,
`anyLong`, `anyPosition`, `anyPurpose`).

Регрессии: `DistributedLockServiceTest`, `OrderExecutionEngineLeaseFenceTest` (3),
`DecisionEngineTest` (43), `LeaseFencingIntegrationTest`, `FuturesTradingBotServiceIntegrationTest`,
`ChaosRedisIntegrationTest`. Полный прогон: **1334 теста**, 0 падений. На тот момент ktlint-остатки —
пред-существующие нарушения HEAD (`OrderPurpose.kt`, `RestOrderTransport.kt`, `StockEntryProfileTest.kt:706`,
`WsOrderTransportTest.kt:348,425`) — закрыты отдельно (см. ниже).

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
`ZERO_RISK_SIZE`/`PORTFOLIO_LIMIT`; переиспользованы `exceedsCorrelationLimit`/`exceedsSectorCorrelationLimit`
из акционных гейтов. Исключение сохранено: `candidateTicker == "Si"` (фьючерсный хедж не фильтруется).
Новые зависимости — `AdaptiveRiskService`/`RiskManagementService` (добавлены в конструктор; тесты добиты моками).

ВНИМАНИЕ (2026-09-07, регрессия после R1): фьючерсная экспозиция в `PORTFOLIO_LIMIT`/Gross/Net
считается ПО МАРЖЕ (GO × qty), а НЕ по полному номиналу контракта (`RiskManagementService.positionNotional`,
кандидат в `FuturesEntryProfile.postSizingChecks`). Иначе даже один контракт Si (номинал 92k при SIM
AUM 50k) вечно получал `PORTFOLIO_LIMIT` — фьючерс это забалансовый инструмент, he занимает депозит на
сумму номинала; его риск ограничен маржой GO (согласовано с сайзингом по GO и `max-margin-usage-percent`).
Акции — полный `spec.notional` как раньше. Регрессия: `futures entry creates position with full risk
fields` (pass), `futures exposure is measured by margin not full notional`.

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

Полный прогон: **1340 тестов, 0 падений** (unit). ktlint — чист (пред-существующие нарушения
`OrderPurpose.kt`, `RestOrderTransport.kt`, `StockEntryProfileTest.kt:706`, `WsOrderTransportTest.kt:348,425`
закрыты 2026-09-08, см. «Закрытие ktlint-остатков» ниже).

### Закрытие ktlint-остатков (2026-09-08)
Все ранее зафиксированные пред-существующие ktlint-нарушения HEAD закрыты; `./gradlew ktlintCheck`
(обе source set) — **exit 0**.

- `OrderPurpose.kt` — формат enum приведён к стилю проекта (`CloseReason.kt`):
  параметр `val code: String,` на отдельных строках с trailing comma, entries с trailing comma,
  `;` на отдельной строке перед `companion object`, конечный `\n`.
- `RestOrderTransport.kt` — починен сломанный KDoc-отступ у `denyIfNotLiveApproved`
  (`/**` был на 0, `*`-строки на 5 пробелов; стало `/**`+`*` на 4). Сломанный KDoc «съедал»
  следующую сигнатуру → цепочка `function-signature`-ошибок (:78-80).
- `StockEntryProfileTest.kt`/`WsOrderTransportTest.kt` — unit/test source set чист
  (`ktlintTestSourceSetCheck` exit 0).

Сопутствующее (см. «Закрытие ktlint-остатков»): интеграционные стабы `InvestorClearingIntegrationTest`
и `RabbitMqTransportIntegrationTest` получили 7-й матчер `anyPurpose()` (закрытие pre-existing
`InvalidUseOfMatchersException` от 56c1479b). Полный `integrationTest`: **100 тестов, 0 падений, 1 skipped**.

## P0/P1-аудит входного конвейера (закрытие, 2026-09-08): fresh GO/balance, unstressed margin, signal freshness

Закрыты оставшиеся пункты аудита «production-readiness» (решения пользователя от
предыдущей сессии реализованы в коде).

### P0-1 (исправлено): fail-closed InstrumentSpec для фьючерса
`FuturesEntryProfile.postSizingChecks`: `val spec = instrumentsConfig.find(ticker) ?: return "INSTRUMENT_SPEC_MISSING"`.
Прежний fallback `price × qty` математически НЕВЕРЕН для фьючерса (забывает `lotSize`;
для CNYRUBF дал бы 12.8 ₽ вместо 12 800 ₽ на контракт) и занижал Gross/Net риск-гейт до
ложного прохода. Не найдено — вход БЛОКИРУЕТСЯ. Регрессия: `missing instrument spec blocks
entry fail-closed` (new `FuturesEntryProfilePostSizingTest`).

### P0-2 (исправлено): fresh GO/balance в LIVE (TTL + fail-closed)
`AlorFuturesClient` кэширует ГО и свободные средства per-ticker/per-portfolio с TTL
`RiskConfig.maxGoAgeMs = 30000` (30 c). В пределах TTL — ответ из кэша без повторного
запроса; по истечении — перезапрос API; при недоступности API и УСТАРЕВШЕМ кэше — `null`
(fail-closed, паритет EXEC-005): сайзинг по старому ГО/балансу запрещён. Тесты на реальном
локальном HTTP (`AlorFuturesClientFreshnessTest`): cache-within-TTL (1 hit), refetch-after-TTL
(2 hits), API-down + stale → null, portfolio-money fail-closed.

### P1 (исправлено): stressed margin на маржинальном гейте
`RiskConfig.stressedMarginMultiplier = 1.5`; `FuturesEntryProfile.postSizingChecks` вызывает
`exceedsMarginUtilization` с `size.marginRequired × 1.5`. Вход на пределе лимита запрещён —
запас на рост/пересчёт ГО после открытия (риск margin call). Регрессия: `margin gate receives
candidate GO times stressed margin multiplier`.

### P1 (исправлено): signal freshness — min(0.25×ATR, 5 ticks, 1% cap) + спред 0.1%/0.5%
`StrategyService.isSignalFresh` / `maxAllowedDeviation`:
- отклонение цены от target ограничено МИНИМУМОМ трёх гейтов: `signalMaxDeviationAtrFraction`
  (0.25) × ATR(14, MINUTE_10), `signalMaxDeviationTicks` (5) × priceStep,
  `signalMaxDeviationPercentCap` (1.0)% от target. Заменяет прежний единый %-гейт
  `signalMaxPriceDeviationPercent` (удалён);
- спред: нормальный гейт `signalMaxSpreadPercent` понижен 2.0% → **0.1%**, жёсткий потолок
  `maxOf` не участвует — в коде `min(config, MAX_SPREAD_CAP_PERCENT = 0.5)`: даже при ошибочно
  завышенном конфиге спред > 0.5% никогда не допускается.
- `StrategyService` получил зависимость `InstrumentsConfig` (priceStep). Конфиг вынесен в
  `application.yml` с env-override (`SIGNAL_MAX_DEVIATION_*`, `SIGNAL_MAX_SPREAD_PERCENT`,
  `RISK_MAX_GO_AGE_MS`, `RISK_STRESSED_MARGIN_MULTIPLIER`).

Полный прогон: **1351 тест, 0 падений** (unit; новые — `FuturesEntryProfilePostSizingTest`,
`AlorFuturesClientFreshnessTest`); `integrationTest`: **100 тестов, 0 падений, 1 skipped**;
`./gradlew ktlintCheck` (обе source set) — **exit 0**.

## Аудит издержек/funding + LIVE-маржинальный гейт (закрытие, 2026-09-08)

Закрыты 6 пунктов аудита пользователя (роботные прогоны 2026-09-07/08). Коммит `futures-cost-audit`.

### 1 (исправлено): LIVE-маржинальный гейт без spec.go fallback
`RiskManagementService.freshMarginOfPositions(openPositions)` — фактическое ГО открытых фьючерсных
позиций: приоритет персистенного `marginUsed`, иначе ЖИВОЙ `getFuturesGO(ticker)×qty` (TTL-кэш 30с);
недоступность (API down + устаревший кэш + нет marginUsed) → **null**; `FuturesEntryProfile.postSizingChecks`
→ fail-closed `PORTFOLIO_MARGIN_DATA_UNAVAILABLE` (до вызова маржинального гейта). Статический spec.go
(850 ₽ CNYRUBF против 1 000–2 700 ₽ фактических MOEX) в LIVE НЕ авторитет; `marginOfPosition` сохранён
для SIM/тест-фикстур. Регрессии: `RiskManagementServiceThresholdTest`/`DailyPnLTest` (новый параметр
`AlorFuturesClient`), `FuturesEntryProfilePostSizingTest` (стаб `freshMarginOfPositions(anyList())` +
`unavailable margin data of open positions blocks entry fail-closed`).

### 2 (исправлено): funding CNYRUBF — first-class компонент P&L
- Новый `object FundingCosts` — число пережитых клирингов: дни, где `openedAt < clearing(18:45 МСК) < closedAt`;
  выходные без клирингов; внутридневная позиция — 0; открытие/закрытие строго на границе клиринга НЕ считаются.
- `InstrumentsSpec.fundingRubPerContractPerDay` (RUB/контракт/клиринг; null → отключено) + `fundingPerClearing()`.
- `PnlCalculator.futures(..., fundingRubPerContractPerDay={null})` вычитает `funding × qty × clearings`
  (live; прокинуто из `FuturesTradingBotService` по `instrumentsConfig`); `BacktestEngine.closePosition`
  — то же для бэктеста (`PositionSim.entryTime` из `candle.time`). Регрессии: `FundingCostsTest` (9),
  `PnlCalculatorCommissionTest` (+3 funding-кейса).

### 3 (исправлено): реальный сплит издержек broker/exchange + slippage
`InstrumentSpec`: `brokerCommissionRub` + `exchangeFeeRub` (сумма = `totalCommissionPerLotSide()`; fallback —
легаси `commissionRub`), `slippageBps`. Все call-site у переведены на единый вход:
`FuturesPositionSizer`, `StockEntryProfile`, `BacktestRiskSimulator`, `BacktestEngine.computeCommission`,
`PnlCalculator` (FuturesTradingBotService/TradingBotService). `BacktestEngine.executionFill` для futures —
проскальзывание `slippageBps×price` (HALF_UP, 8 знаков), но не меньше 1 тика. CNYRUBF: broker 1.0 +
exchange 0.5 = 1.5 ₽/контракт/сторона, slippage 1.0 bp, funding 0.5 ₽/клиринг — **provisional,
сверяются с выписками до LIVE** (docs/16). `InstrumentsConfig.validateSpecs()` валидирует новые поля (≥0).

### 4 (исправлено): docs Si → CNYRUBF
`docs/15-futures-trading.md` переписан под CNYRUBF (все числовые примеры: GO 850, SL 150→12.65, TP 1200→14.00,
liq-buffer 0.85 ₽, daily limit = min(2% AUM, 5k)=1 000 ₽, max-contracts=1); добавлен **`docs/16-instrument-specifications.md`**
(model издержек/funding, CNYRUBF specs, источники истины, «подлежит сверке перед LIVE»).
e2e smoke переведён на CNYRUBF (JSON сигнал + ожидаемая позиция).

### 5 (исправлено): daily-loss «5 000 ₽ vs 10%» — синхронизированы доки/комментарии
Код УЖЕ реализовал `min(maxDailyLossPercent×AUM, maxDailyLossRub)` (= 1 000 ₽ на 50k); устаревшие
«10% = 5 000 ₽» указывали на абсолютный потолок. Обновлены: `RiskConfig` KDoc, `FuturesRiskEngine` KDoc,
`docs/01` (лимит → min(2% AUM, 5k)), `docs/CONFIGURATION.md` (`max-daily-loss-percent: 10.0` → `2.0`),
`docs/15` (§15.4, таб. guardrails, диагностика). Легаси DailyLossCircuitBreaker для фьючерсов — см. P2.

### 6 (исправлено): max-contracts-per-position 2 → 1 для первого LIVE
`application.yml`: `max-contracts-per-position: ${RISK_MAXCONTRACTSPERPOSITION:1}` (env-overridable;
backtest-калибровка CNYRUBF продолжала использовать maxC до 100 через `futuresMaxContractsPerPosition` frozen-стратегии).

### ktlint
`function-signature`/`chain-method-continuation` (многострочные цепочки: первый сегмент без dot mid-line —
через промежуточные val), `indent` для аннотаций по колонке 4, `final-newline` — файлы писать с конечным `\n`
(Read/Edit предпочтительнее здесь-строк PowerShell из-за кодировки).

Итоговый прогон: **1363 теста, 0 падений** (unit; +12: FundingCostsTest 9, PnlCalculatorCommissionTest 3,
FuturesEntryProfilePostSizingTest 1, минус правки); `integrationTest`: **100 тестов, 0 падений, 1 skipped**;
`./gradlew ktlintCheck` (обе source set) — **exit 0**.

## P0/P1-аудит издержек/funding + LIVE-маржинальный гейт (закрытие 2-го аудита, 2026-09-08)

Закрыты пункты второго аудита пользователя (вердикт 8.6/10, NO-GO до P0). Коммит `futures-margin-funding-audit`.

### P0-1/P0-2 (исправлено): live side-specific GO в адмиссии
- `AlorFuturesClient.getFuturesGO(ticker, direction)` — **side-specific**: `FuturesGo(long?, short?, fetchedAtMs)`
  кэшируется парой; SHORT → `short.initialMargin`, иначе → `long.initialMargin` (`forDirection`, top-level
  internal extension). Отсутствие маржи ЗАПРАШЕННОЙ стороны → `null` (fail-closed, без cross-side подстановки).
- `RiskManagementService.freshMarginOfPositions(openPositions, precomputedGoPerTicker: Map<String,BigDecimal>? = null)`
  — ТОЛЬКО живое ГО (side-specific) с TTL 30с; `marginUsed` и статический spec.go (850 ₽) в LIVE-адмиссии
  НЕ используются (marginUsed застывает на моменте открытия). `marginOfPosition()` УДАЛЁН (P1-6: опасный
  статический fallback, в prod-пути не использовался; тесты портированы на fresh-margin).
- Значения CNYRUBF в `application.yml` помечены SIM/fallback-только; nightly `go` не пере-кэшируется между парой.

### P1-4 (исправлено): единый риск-снапшот входа
`FuturesRiskSnapshot(takenAt, accountId, portfolioMoney, candidateGo, openFuturesGoPerTicker)` в `domain/risk`;
nullable-поле `futuresRiskSnapshot` в `EntryRequest`. `FuturesEntryProfile.buildEntryRequest` снимает деньги+ГО
кандидата (по стороне)+ГО всех открытых futures-тикеров в ОДИН момент; `postSizingChecks` передаёт карту ГО в
`freshMarginOfPositions` (позиция вне снапшота → fail-closed `PORTFOLIO_MARGIN_DATA_UNAVAILABLE`).

### P0-3 (исправлено): динамический funding через FundingProvider
Новый пакет `application/funding/`:
- `FundingSnapshot(ticker, rawValue, unit, valueRubPerContractPerClearing, source, timestamp)`,
  `FundingSource {CONFIG, MOEX}`, `FundingUnit {RAW_UNKNOWN, RUB_PER_CONTRACT_PER_CLEARING}`;
- `FundingConfig` (prefix `funding`, env): `moex-url` (шаблон `{ticker}`, пустой → источник выключен),
  `moex-column` (LATESTFUNDING), `moex-lot-multiplier` (1000), `moex-ttl-ms` (5 мин), `request-timeout-ms`;
- `MoexFundingProvider` (ISS columns/data, `raw × lotMultiplier` → RUB/контракт/клиринг),
  `ConfiguredFundingProvider` (config-значение; SIM/backtest/fallback), `FundingSnapshotService`
  (кэш; `refresh(ticker)` suspend на входе; `value(ticker)` sync для P&L; метрики
  `funding.live.provider_unavailable`, `funding.live.snapshot_stale_config_fallback`).
- `FuturesTradingBotService` P&L-lambda → `fundingSnapshotService.value(ticker)`; backtest остаётся на config.
- FundingCosts (клиринг 18:45 МСК, будни) — без изменений; праздничный календарь MOEX не моделируется (P1, открыт).

### P1-5 (исправлено): slippage в live-риск-бюджете сайзинга
`FuturesPositionSizer`: `slippagePerSide = max(entryPrice × slippageBps/10000 × pointValue, priceStep × pointValue)`
(минимум 1 тик), только при `entryPrice != null && slippageBps > 0`; `effectiveRiskPerContract = loss + комиссия×2 + slippage×2`.

### Docs
`docs/15` (§15.1-15.6): side-specific GO, снапшот входа, `marginPerContract = ПОЛНОЕ ГО` (убрано `go/leverage`,
`maxByMargin = 35`), liq-buffer `GO/pointValue = 0.85`, риск-бюджет с slippage, funding-источник.
`docs/16`: `go`/funding помечены SIM/fallback, маржинальный гейт — live-first + снапшот, funding — MOEX-провайдер
(provisional, сверка перед LIVE).

Итоговый прогон: **1384 теста, 0 падений, 1 skipped** (unit; +21 к 1363: MoexFundingProviderTest 7,
FundingSnapshotServiceTest 4, AlorFuturesClientTest side-кейсы, AlorFuturesClientFreshnessTest side/missing-side,
RiskManagementServiceThresholdTest fresh-margin 4, FuturesPositionSizerTest slippage 2,
FuturesEntryProfilePostSizingTest snapshоt-map); `integrationTest`: **100 тестов, 0 падений, 1 skipped**;
`./gradlew ktlintCheck` (обе source set) — **exit 0**.
