# Правила

## Язык общения

- Всегда общайся со мной строго на русском языке.
- Все ответы, пояснения и комментарии — только на русском.
- Код, имена переменных и технические термины могут оставаться на английском.

## Сборка и проверка

- Unit-тесты: `./gradlew test`; интеграционные: `./gradlew integrationTest`;
  линт: `./gradlew ktlintCheck` (main + test source set). Перед завершением задачи —
  обязательно зелёные ВСЕ три (`test` + `integrationTest` + `ktlintCheck`).
- ktlint-правила (критично при редактировании):
  - файлы писать с конечным `\n` (Read/Edit предпочтительнее здесь-строк PowerShell из-за кодировки);
  - `function-signature`/`chain-method-continuation`: многострочные цепочки — через промежуточные `val`,
    первый сегмент без dot mid-line;
  - `indent`: аннотации по колонке 4; KDoc `/**`+`*` на одном отступе.
- Mockito в Kotlin: inline `Mockito.any(SomeClass::class.java)` для non-null типов → NPE; обязателен
  helper-паттерн `{ Mockito.any(X::class.java); return dummy }` (`anyString`, `anyBigDecimal`,
  `anyDirection`, `anyLong`, `anyPosition`, `anyPurpose`).

## Актуальные параметры (production, 2026-09)

### Лимиты и риск

| Параметр | Значение | Примечание |
|---|---|---|
| max-open-positions | **3** (live, `application.yml:476`) | RiskConfig default=1 (перекрывается yml); доки синхронизированы |
| futures-max-open-positions | **1** | не менялся |
| max-contracts-per-position | **1** (`RISK_MAXCONTRACTSPERPOSITION`) | для первого LIVE; калибровка — maxC до 100 из frozen-стратегии |
| daily loss | `min(2% AUM, 5 000 ₽)` = 1 000 ₽ на 50k | абсолютный потолок, не 10% |
| max-margin-usage-percent | **60** (live/demo) / 90 (для калибровки) | вернуть 60 при LIVE |
| stressed-margin-multiplier | **1.5** | вход на пределе лимита запрещён (запас на пересчёт ГО) |
| kellyNoDataFraction | **0.003** (cold start) | кап `kellyMaxPositionFraction=0.10`; staged tiers; до статистики бот почти не сайзит |
| volatility-fail-closed | **true** (дефолт) | ATR недоступен → `VOLATILITY_GUARD` блокирует вход |

### LIVE-guard (fail-closed, 2026-09-09)

- `trading.live-tickers-allowlist` = **CNYRUBF** (`LIVE_TICKERS_ALLOWLIST`, yml). В LIVE вход
  ТОЛЬКО тикерам из списка; **пустой список в LIVE = блок ВСЕХ входов (fail-closed)**; SIM игнорирует.
- Барьер на двух уровнях: `DecisionEngine` (до локов, `entry.rejected{reason=LIVE_TICKER_NOT_ALLOWED}`)
  и `RestOrderTransport.denyIfNotLiveApproved` (`alor.order.blocked`).
- CI (`.github/workflows/ci.yml`): `deploy-sim` — авто на push main/master, жёстко `TRADING_MODE=SIMULATION`;
  `deploy-live` — ТОЛЬКО manual `workflow_dispatch` (`deploy_live=true` + `live_confirmation=LIVE_CONFIRM`) +
  GitHub Environment `live` (required reviewers) + `TRADING_MODE=LIVE`, `TRADING_TICKERS=CNYRUBF`,
  `LIVE_TICKERS_ALLOWLIST=CNYRUBF`.

### Futures LIVE (маржинальный путь, 2026-09-08/09)

- **Side-specific GO**: `getFuturesGO(ticker, direction)` кэширует пару (long/short) с TTL
  `maxGoAgeMs=30s`; SHORT-вход при отсутствии `short.initialMargin` → null (fail-closed, без cross-side).
- **Единый риск-снапшот входа**: деньги + GO кандидата (по стороне) + ГО всех открытых futures-тикеров
  в ОДИН момент (`FuturesRiskSnapshot` → `EntryRequest.futuresRiskSnapshot`); позиция вне снапшота →
  `PORTFOLIO_MARGIN_DATA_UNAVAILABLE` (fail-closed).
- Маржинальный гейт — по ЖИВОЙ марже (side-specific, TTL 30s), НЕ по статическому spec.go
  (850 ₽ CNYRUBF против 1 000–2 700 ₽ фактических MOEX); spec.go — SIM/fallback только.
- Экспозиция фьючерса в гейтах (Gross/Net/PortfolioLimit/Concentration) считается ПО МАРЖЕ (GO×qty),
  не по номиналу. `candidateTicker == "Si"` не фильтруется (хедж).
- **Liq-симуляция** (`bt.futures-liquidation-simulation=true`, дефолт): позиция, чей бар пробил
  `liquidationPrice` (LONG = entry − GO/pointValue), закрывается по liq-цене (worst-case). При
  калибровочных SL 150–300 пт стоп срабатывает РАНЬШЕ liq-уровня — ликвидация не влияет на результаты.
  Отключение для stress-прогонов: `bt.futures-liquidation-simulation=false`.

### Издержки и funding (provisional, сверка с выписками до LIVE — docs/16)

- CNYRUBF: broker **1.0** + exchange **0.5** = 1.5 ₽/контракт/сторона, slippage **1.0 bp**,
  funding **0.5 ₽/клиринг**.
- Funding — **per-clearing динамический** (P1, 2026-09-09; P0-верификация 2026-09-10): MOEX ISS
  **`SWAPRATE`** («Фандинг, руб.», RUB за 1 ед. базового актива; CNYRUBF: 0.00278/0.00256) × 1000 →
  RUB/контракт/клиринг (TTL 5 мин); `LATESTFUNDING` в MOEX ISS **НЕ существует** (поле заменено).
  Недоступность MOEX в LIVE → сделка помечается
  `Position.fundingUnknown=true` (колонка `funding_unknown`), CONFIG-value НЕ подставляется.
  Конфиг (`funding.*`) — SIM/backtest/fallback только.
- Клиринг 18:45 МСК, будни; открытие/закрытие на границе клиринга НЕ считаются; внутридневная позиция = 0.
  **Праздничный календарь MOEX не моделируется** (открытый P1).
- P&L futures вычитает комиссию `qty × commissionRub × 2` и funding `× qty × clearings`
  (live через `FundingSnapshotService`, backtest — на config) — паритет live↔backtest.
- Backtest P&L использует ФАКТИЧЕСКИЕ SWAPRATE из `funding_history` (если ряд донакачан):
  `sum(funding_history[clearingDate]) × qty` вместо config-ставки (fallback — при отсутствии
  истории или тикера). Донакачка — `GET /api/v1/backtest/{ticker}/funding-history?days=`.
- Slippage в риск-бюджете сайзинга: `max(entryPrice × slippageBps/10000 × pointValue, priceStep × pointValue)`
  (минимум 1 тик); `effectiveRiskPerContract = loss + комиссия×2 + slippage×2`.

### Funding Veto Gate (research, дефолт off; 2026-09-18, docs/16)

- `FundingVetoGate` (входной гейт `DecisionEngine`, после NetEvGate; метрика
  `entry.rejected{reason=FUNDING_VETO}`): LONG блокируется при funding > `+long-threshold`,
  SHORT — при funding < `−short-threshold` (руб/контракт/клиринг, SWAPRATE MOEX; положительная
  ставка = лонг платит). Источник — `FundingSnapshotService.latestForVeto` (LIVE: только свежий
  MOEX ≤ `moexTtlMs`; SIM/backtest — CONFIG). Uстаревший/нет снапшота + `funding-veto-block-on-unknown=true`
  → BLOCK (fail-closed).
- Конфиг (`trading.*`, env `TRADING_FUNDING_VETO_*`): `enabled=false` (research, live-поведение не
  меняется), пороги default 2.0. WFA-калибровка порогов возможна: исторический ряд SWAPRATE
  донакачан в `funding_history` (миграция 036, `MoexFundingHistoryLoader` + endpoint
  `GET /api/v1/backtest/{ticker}/funding-history?days=`, CNYRUBF 509 дат 2024-09-18..2026-09-17,
  конфиг `funding.moex-history-url`/`FUNDING_MOEX_HISTORY_URL`).
- **Backtest-зеркало**: `BacktestRiskSimulator` воспроизводит гейт (вход после NetEvGate,
  reason `FUNDING_VETO`, конструируется с `fundingVeto*`-параметрами, default off); ставка =
  `fundingHistory[дата входа]` из `funding_history`.
- **Backtest input-фильтр (2026-09-19, калибровка)**: изолированный funding-veto фильтр входа в
  `BacktestEngine` (по паттерну ML/MTF-фильтров, НЕ через `BacktestRiskSimulator` — он не Spring-бин,
  в проде `riskSimulator==null` → риск-гейты в бэктесте выключены). Управление `bt.funding-veto-*`
  (`BT_FUNDING_VETO_*`), default off; reason `FUNDING_VETO`, метрика `bt_funding_veto_blocked_total{ticker}`;
  блок LONG при funding > long-порога, SHORT при funding < −short-порога, fail-closed на неизвестной дате.
  **Query-параметры на API** `fundingVetoEnabled/fundingVetoLongThresholdRub/fundingVetoShortThresholdRub/
  fundingVetoBlockOnUnknown` (override bt.*, калибровка порогов без перезапуска): `/backtest`,
  `/validate`, `/robustness`, `/deployment-gate` (пробрасываются через `WfaConfig`→`BacktestValidator`/
  `FinalHoldoutValidator`→`MonteCarloAnalyzer`).
- **Результат WFA-калибровки порогов (CNYRUBF, 365д, folds=6, conf 0.60)**: см. research-раздел ниже
  «WFA-калибровка funding-veto (2026-09-19)». Оптимум — long 9 ₽, short 2 ₽; live-пороги НЕ менялись.

### Мониторинг (2026-09-09)

Пороги синхронизированы с гейтами (`prometheus-alerting-rules.yml`):
- спред: warning > 0.1% (`HighSpread`), critical > 0.5% (`SpreadCapExceeded`) — метрика `market.data.spread_percent`;
- свежесть цены: warning > 10s, critical гейт 15s (`MarketDataStale`/`MarketDataBlocked`) — `market.data.age_ms`;
- ГО-кэш: warning > 20s, critical гейт 30s (`FuturesGoCacheAge`/`FuturesMarginStale`) — `futures.go_cache_age_ms`
  (теги ticker/side/fresh); balance — `futures.balance_cache_age_ms` (> 20s warning);
- LLM: p95 > 1 s warning (`LLMHighLatency`), p99 > 3 s (`LLMSleeping`) — `llm_latency_seconds`;
- LIVE-guard blocking: `entry.rejected{reason=LIVE_TICKER_NOT_ALLOWED}` и др.

## Калибровки (research-референс; НЕ production-настройки)

> Все параметры этого раздела — результат research-бэктестов и в LIVE **не воспроизводятся**
> напрямую: live-сайзинг управляется `AdaptiveRiskService` (Kelly, `RiskConfig`/`application.yml`),
> лимиты/риск-кэпсы — разделом «Актуальные параметры (production, 2026-09)» выше. Каждая таблица
> ниже помечена, что именно является research-параметром бэктест-сайзера.

### Акции (365д, x5, SL=2%/TP=15%, conf=0.6)

**Research-параметры** (x5/leverage, SL/TP %, conf) — только параметры бэктест-сайзера, в live
заменяются Kelly+ATR-стопами.

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
- **ВНИМАНИЕ (P2-c, решено)**: live-сайзинг акций — Kelly (`StockEntryProfile`+`AdaptiveRiskService`);
  калибровочный x5/x6 — параметр бэктест-сайзера и в live НЕ воспроизводится. Решение: оставить Kelly.

### Фьючерсы (365д, MINUTE_10)

Для фьючерсов панельные SL%/TP%/leverage не работают — действуют `riskPerTradePercent`,
`futuresMaxContractsPerPosition`, `slPoints`, `tpPoints` (SL/TP в пунктах цены). Калибровка 2026-08:

**Research-параметры** (risk 11%, maxC 33) — параметры бэктест-сайзера; в live действуют
`max-contracts-per-position=1` и Kelly/`RiskConfig` (см. «Актуальные параметры» выше).

| Ticker | Параметры | Return | PF | WR | MDD | Trades |
|--------|-----------|--------|----|----|-----|--------|
| CNYRUBF | risk 11%, maxC 33, SL 300, TP 600 | +91.8% | 2.19 | 55% | 38.8% | 22 |
| RI | risk 11%, maxC 33, SL 300, TP 600 | +26.7% | 2.57 | 60% | 9.1% | 10 |

Ключевые находки:
- MDD фьючерсного прогона определяется SL×позицией (не TP); TP влияет только на доходность
- TP 1200 пунктов даёт выше PF (2.8), но MDD растёт (позиции дольше в просадке)
- RI OOS убыточен/нестабилен (см. research-логи) — **исключён из портфеля**

## Research-логи (валидации на текущей истории)

### Углублённая калибровка CNYRUBF (маржа 90%, 2026-08-30)

| Горизонт | Параметры | Return | PF | MDD | Trades |
|----------|-----------|--------|----|-----|--------|
| 365д (IS) | risk 30%, maxC 100, SL 150, TP 1200, conf 0.63 | +179.0% | 6.55 | 36.7% | 6 |
| 365д (OOS, folds=6) | те же | +82.9% | 4.53 | - | 8 |
| 365д (OOS, folds=8) | те же | +57.8% | 3.46 | - | 8 |
| 730д (IS) | risk 30%, maxC 100, SL 150, TP 1200, conf 0.6 | +381.9% | 1.86 | 81.3% | 54 |
| 730д (OOS, folds=8) | те же | +104.4% | 1.22 | - | 63 |

Выводы:
- OOS стабильно положителен (365д +83% при folds=6, 730д +104%), но consistency 33–50%;
  `robust=false`. Слабый кандидат — live без доп. фильтра не рекомендуется.
- **Conf 0.63 vs 0.60 — вопрос открыт**: на 365д панели БЕЗ издержек «0.63 strict better»
  (+45.1%→+82.9% OOS), на 3-мес окне С издержками сильнее 0.60 (+77.4% / consistency 0.667 / edge sig).
  Требуется повторная чувствительность на полной истории (после донакачки). 0.64+ резко деградирует.
- Ограничение MDD до 40% на 730д недостижимо (минимальный MDD ~60% при 217% дохода).
- RI: OOS от −26.9% до +60.8% при смене folds/conf — нестабилен, исключён.

### Статистическая валидность (независимый прогон, 2026-09-05)

Воспроизведено кодом проекта (WFA `validate` / MC `robustness` / IS-panel `panelBacktest`)
на live-стеке (postgres+redis, `bootRun`).

| Ticker | Folds | Consistency | OOS Ret | OOS Sharpe | OOS PF | OOS Trades | Robust |
|--------|-------|-------------|---------|------------|--------|------------|--------|
| CNYRUBF (conf 0.63, risk30%, maxC100) | 6 | 0.33 | +24.4% | 1.27 | 5.29 | 5 | false |
| CNYRUBF (conf 0.63, risk30%, maxC100) | 8 | 0.25 | +23.0% | 1.21 | 4.45 | 6 | false |
| RI (conf 0.60, risk30%, maxC33) | 4 | 0.75 | +24.6% | 1.60 | 3.28 | 9 | false |

- Все `robust=false` (trades 5–9 << 100).
- Ограничение инструмента: `robustness` endpoint долго не принимал futures SL/TP в пунктах —
  использовал дефолтные SL 2%/TP 4% (исправлено 2026-09-07, B1); для фьючерса применять WFA `validate`.

### Статвалидация CNYRUBF/RI (2026-09-09, 3 мес истории, с издержками)

Прогон на live-стеке (postgres+redis, `java -jar` с `--spring.mvc.async.request-timeout=600000`).
История: июн–сен 2026, MINUTE_10, с новой моделью издержек (коммит `0b32f4d`). WFA-тюнинг —
по сетке `futuresGrid` (max TP 600).

| Сценарий | Verdict | Прошло | Провалено |
|----------|---------|--------|-----------|
| CNYRUBF conf 0.63 folds=6 | REJECTED | robustness (p5=+23.8%) | backtest, walk_forward, consistency 0.167, edge, holdout 1 |
| CNYRUBF conf **0.60** folds=6 | REJECTED | consistency 0.667, edge (P=0.026), holdout (2) | backtest (MDD 37.4%), robustness (p5=−4.1%, pLoss 6.4%), oosTrades 8 << 100 |
| RI conf **0.60** folds=4 | REJECTED | holdout | consistency 0.25, edge (P=0.693), robustness (p5=−24.7%), oosTrades 9 << 100 |

- Блокер для ВСЕХ — тонкая выборка (8–11 OOS-сделок против 100; holdout 1–2 против 30) — ожидаемо
  на 3-мес истории. **Live для CNYRUBF/RI НЕ одобрен**; frozen-стратегия не заморожена.
- CNYRUBF conf 0.60 проходит consistency/edge/holdout, но хрупок по MC (p5=−4.1%) — NO-GO.
- IS-panel по сетке SL/TP (conf 0.63/0.60): доходность растёт с шириной TP — TP 600 → +171.4% ≈
  TP 1200 → +174.6%; узкие сетки (25/50…100/200) убыточны (трендовый характер — широкие стопы обязательны).
- Перед повторной валидацией — **донакачка истории ≥ 6–12 мес**.
- Оговорка: в deployment-gate confidence берётся из `--bt.adaptive-confidence-threshold`, request-параметр
  НЕ принимается (защита от leakage holdout).

### Статвалидация CNYRUBF (2026-09-09, полная история 365д после донакачки MOEX ISS)

Донакачка: убрана retention policy 90д на `candles` (TimescaleDB, миграция 012) → история
2025-09-09…2026-09-09 (23 842 свечи MINUTE_10, в 3.6 раза больше прежних 3 мес). Прогон:
`java -jar trading-bot-2.0.0.jar --bt.adaptive-confidence-threshold=0.60 --spring.mvc.async.request-timeout=600000`
+ `scripts/research_wfa_cnyrubf.ps1 -Days 365 -Folds 6 -DropRetention`. Deployment-gate ~9.7 мин.

| Сценарий | Verdict | Прошло | Провалено |
|----------|---------|--------|-----------|
| CNYRUBF conf **0.60** folds=6 (deployment-gate research) | **RESEARCH_ONLY** (не LIVE, не PAPER) | backtest (PF=2.06, Sharpe=1.75, MDD 0.7%, 21 сделка); holdout+MC композит | walk_forward (consistency 0.500, oosTrades 20 < 100, oosSharpe −0.69, oosPF 0.69); edge (noEdge P=0.764); holdout (1.3%, 6 сделок < 30) |
| CNYRUBF conf **0.63** folds=6 (WFA validate, request-param) | REJECTED | oosPF 1.24, oosSharpe +0.42 | consistency 0.50, oosTrades 25 << 100, robust=false, edge=false |

- **Вердикт: CNYRUBF НЕ проходит даже на полной 365д истории.** Ключевой индикатор — WFA OOS
  прибыльность РАЗВАЛИЛАСЬ при расширении выборки (3-мес conf 0.60: OOS PF 1.2…3.3 → 365д: 0.69),
  consistency 0.5, edge статистически незначим. Прежний «+77.4% на 3-мес» — на тонкой выборке.
- Ограничитель тот же тонкий профиль сделок: на 365д 20 OOS-сделок (ещё далеко от 100), но даже при
  текущем объёме OOS-направление отрицательное — это не «мало данных», а отсутствие устойчивого edge.
- Holdout (последние ~20% истории): 6 сделок, +1.3% — слишком мало для уверенного вывода.
- **Закрытый вопрос conf 0.60 vs 0.63**: на полной истории оба не дают значимого OOS-результата;
  сравнение не в пользу 0.63 (consistency 0.50, oosPF 1.24 → всё равно far below порогов).
- Live-параметры НЕ менялись; maxC=1, СЛ №1 для LIVE — в силе. Донакачка не дала одобрения
  CNYRUBF; кандидат на LIVE остаётся под вормафутным наблюдением (research-режим paper).
- Тех. находка в ходе прогона: в `DeploymentGate.kt` holdout/robustness check'и показывали
  `passed=true` при `mcRobust=false` (баг `holdout?.passed == true.also{...}` — `passed` поля всегда
  true) — исправлено на `(… == true).also`, добавлены регрессионные тесты. Также починен отступ
  `db.changelog-master.yaml` (include 028–034 ломал парс Liquibase → 90/100 integration-тестов).

### LLM-сигналы на CNYRUBF (WFA-валидация, 2026-09-16, полная история 365д)

Прогон конвейера tech→fund→strategy→contrarian→arbitrator (`bt.agent.live-strategies=false`,
`prompt-version=aggressive`, `confidence-threshold=0.40`) на live-стеке после устранения
guardrail (тех-агент давал 0.3–0.55 при жёстком `signalStrength < 0.5` в `StrategyAgent`).

| Прогон | Сделок OOS | OOS Return | OOS PF | Consistency | Вывод |
|--------|-----------|------------|--------|-------------|-------|
| 30д folds=2 (sample-every 240) | 4–10 | +0.10..0.48% | 1.24–20.65 | 1.0 | **шум** на 4–9 сделках |
| 90д folds=6 (sample-every 60) | 55 | −1.22% | **0.22** | 0.0 | большой минус |
| 90д folds=6 (sample-every 240) | 9 | ~0% | 0.97 | 0.33 | безубыток |
| 90д folds=6 (sample-every 960) | 17 | минус | 0.40 | — | минус |
| **365д folds=6 (sample-every 240)** | **69** | **−0.41%** | **0.73** | 0.50 | **edge нет** |

- **Вердикт (зафиксирован): LLM-сигнальный конвейер на CNYRUBF НЕ имеет edge.**
  Ключевые показатели на полной 365д истории (69 OOS-сделок — достаточная выборка):
  PF=0.73 (проигрыш), edge P=0.886 (no-edge), mean trade CI −15..+3.9 ₽ (статистическая
  нулёвка), consistency 0.5. Ранние «PF 9–20» на 30д — классический overfit на коротком окне.
- Закономерность: **чем чаще LLM торгует (sample-every 60), тем хуже** (PF 0.22) — сигнал
  не предсказывает направление, убыток растёт с числом попыток. Это не «мало данных», а
  отсутствие устойчивого edge.
- **Достижение прогона (код остаётся)**: guardrail-задача решена — LLM реально генерирует
  BUY/SELL (в логах 376 BUY / 837 SELL по тех/стратегу), конвейер бэктеста работает.
  Промпт-версии `aggressive`/`signal`, параметры `bt.agent.confidence-threshold`/
  `tech-min-signal-strength`/`prompt-version`/`sample-every` — рабочие research-инструменты.
- Live-параметры НЕ менялись; maxC=1, LIVE-guard, Kelly-сайзинг — в силе. LLM-сигналы
  остаются research/shadow (docs/17, этап 5); для LIVE НЕ одобрено.
- **Кросс-тикерная проверка (2026-09-16, 365д folds=6, aggressive/th=0.40/sample-240)**:
  GAZP — 24 сделки, PF=0.73, −4.9%, consistency 0.33, edge P=0.74; SBER — 0 сделок OOS.
  Тот же PF=0.73, что и у CNYRUBF — **проблема НЕ тикер-специфична**: тот же конвейер
  отбракован проверкой детерминированных стратегий. LLM-сигналы edge не дают.
- **qwen3-32b на CNYRUBF (WFA, 2026-09-17, 365д MINUTE_10 folds=6 sample-every=240,
  RouterAI `qwen/qwen3-32b`, aggressive/th=0.40)`**:
  OOS сделок 67, Return −0.65%, PF=**0.81**, consistency 0.167, edge P=**0.72**, Sharpe −0.57,
  CI [−40.4, +24.3] — **edge НЕТ**. Модель включена через `LLM_DISABLE_REASONING=true`
  (`llm.disable-reasoning`, см. `LlmConfig`/`ResilientLlmClient`): у qwen3-32b (thinking-модель)
  без этого параметра ответ занимает 60+ с и упирается в `llm.timeout-sec: 30` (fallback
  `CALL_ERROR` → детерминированный baseline с занижением сигналов); с выключенным reasoning
  латентность ~1.5 с, LLM работал штатно (тех 0.65–0.75, стратег BUY/SELL).
  Тот же вывод, что и по DeepSeek/тех-дефолту: LLM-сигналы edge не дают; детерминированные
  стратегии CNYRUBF MINUTE_10 остаются единственным значимым источником (PF=2.15, P=0.0425).
- **claude-opus-4.6 на CNYRUBF (WFA, 2026-09-17, 90д MINUTE_10 folds=3 sample-every=240,
  RouterAI `anthropic/claude-opus-4.6`, prompt-version=signal/th=0.30, `LLM_DISABLE_REASONING=true`)**:
  OOS сделок **10**, Return +0.09%, PF=**1.11**, consistency 0.333, edge P=**0.48**, Sharpe 0.12,
  CI [−125.3, +171.3] — **edge НЕТ** (слишком мало сделок, статистически не значимо). Тот же
  вывод, что по DeepSeek/qwen3-32b: LLM-сигналы edge не дают; детерминированные стратегии
  CNYRUBF MINUTE_10 остаются единственным значимым источником.
- По ходу Opus-прогонов исправлены research-баги конвейера (Live/детерминированные стратегии НЕ затронуты):
  - **fenced-JSON bug**: `ContrarianAgent`/`FundamentalAnalysisAgent`/`TechnicalAnalysisAgent`
    валидировали сырой `resp.content` без снятия ```json-обёртки (Strategy/Arbitrator стрипали).
    Opus (в отличие от qwen) оборачивает JSON в код-фенс → schema rejected → fail-closed CRITICAL →
    контрарьян ветоил ВСЕ входы (0 сделок и «все HOLD»). Исправлено: fence-strip перед
    валидацией/парсингом + регресс-тесты `AgentResponseParsingTest` (tech/fund/contrarian).
  - **`signal`-промпты добавлены contrarian/arbitrator**: ранее при `prompt-version=signal` они
    откатывались на консервативный `default` (`arbitrator.yml` «riskLevel HIGH → HOLD») → арбитр
    ветоил сигналы Opus (контрарьян Opus ставит HIGH, qwen — нет). Теперь блокирует только CRITICAL.
  - **Opus возвращает скаляры строками** (`"0.72"`, `"true"`): `LlmResponseValidator` принимает
    строковые number/integer/boolean (тесты `LlmResponseSchemaTest`).
- Кандидаты на продолжение: другие таймфреймы, платная подписка rg.ru (news в
  `FundamentalAnalysisAgent`), либо закрытие LLM-сигнального пути как не-еdge.

### WFA-калибровка funding-veto (2026-09-19)

Прогон на live-стеке (postgres+redis, `java -jar` с `--spring.mvc.async.request-timeout=600000`).
Источник ставок — `funding_history` (SWAPRATE MOEX, 509 дат). Инструмент — новый изолированный
input-фильтр в `BacktestEngine` (`bt.funding-veto-*`, query-override `fundingVeto*` на API, см.
раздел «Funding Veto Gate»). WFA: CNYRUBF, 365д, MINUTE_10, folds=6, conf 0.60.

| Параметры | OOS Ret | OOS PF | OOS Sharpe | OOS Trades | Consistency |
|-----------|---------|--------|------------|-----------|-------------|
| baseline (фильтр off) | −0.23% | 0.88 | −0.27 | 31 | 0.667 |
| long 2 / short 2 ₽ | +0.16% | 1.16 | +0.22 | 15 | 0.500 |
| long 4 / short 4 ₽ | +0.53% | 1.49 | +0.67 | 19 | 0.667 |
| long 6 / short 6 ₽ | +0.57% | 1.38 | +0.64 | 24 | 0.667 |
| long 7 / short 7 ₽ | +0.58% | 1.39 | +0.65 | 25 | 0.667 |
| **long 8 / short 8 ₽** | +0.78% | 1.52 | +0.85 | 26 | 0.667 |
| **long 9 / short 9 ₽** | +0.85% | 1.58 | +0.92 | 26 | 0.667 |
| **long 9 / short 2 ₽** | **+0.86%** | **1.58** | **+0.93** | 26 | 0.667 |
| long 10 / short 10 ₽ | +0.80% | 1.53 | +0.87 | 26 | 0.667 |
| long 12 / short 12 ₽ | −0.31% | 0.84 | −0.37 | 30 | 0.667 |
| long 14 / short 14 ₽ | −0.31% | 0.84 | −0.37 | 30 | 0.667 |

- **Оптимум — long 9 ₽, short 2 ₽**: OOS PF 1.58 (baseline 0.88), Sharpe +0.93 (−0.27), OOS +0.86%
  (−0.23%). Разворот OOS из отрицательной зоны в положительную; consistency 0.667 (как baseline).
  Дробные пороги (7–10) дают единый плато, 12+ откатывается к baseline — слишком слабая фильтрация
  LONG, а жёсткий SHORT-порог режет.
- Deployment-gate того же кандидата (research): **RESEARCH_ONLY** — backtest PASS (Sharpe 1.22, MDD 0.8%,
  PF 1.66, 22 сделки), но WFA OOS 23 сделки < 100 (consistency 0.333, oosPF 0.79), edge нет
  (P=0.668), holdout 5 сделок < 30 (return 1.3%), MC p5=−0.5% → не robust. Тот же ограничитель —
  тонкая выборка; funding-veto НЕ чинит статистический edge (улучшение сидит на 26 OOS-сделках).
- IS-панель через `/backtest` (live-like сайзинг maxC=1): порог 2.0 → 21 сделка, PF 1.76, MDD 0.14%
  vs baseline 38 сделок, PF 1.08, MDD 0.24% — фильтр реально отсекает убыточные LONG-входы в
  высокий funding.
- **Live-пороги НЕ менялись**: `trading.funding-veto-*` остаётся default off/2.0. Research-пороги
  не переносятся автоматически (см. «Открытые пункты»).

## Каталог закрытых аудитов (сжато; суть — в разделах выше)

| Дата | Аудит | Что закрыто | Итоговый прогон |
|---|---|---|---|
| 2026-09-06 | P2 риск-слой (`e0ab5ed`) | P2-a AUM per-account в exposure; P2-b единый `LocalDate.now(clock)`; P2-c Kelly (решено, задокументировано) | 1324 |
| 2026-09-06 | P1 входной конвейер | P1-1 race admission (account locks + Redis); P1-2 accountId явно в лимиты; P1-3 cold-start Kelly 0.003; #5/#6 fail-closed доказаны; #10 docs→3 | — |
| 2026-09-07 | P1 продолжение | P1-4 renewable lease (watchdog); P1-5 fail-closed AUM (`AumResult`); P1-6 close accountId + unresolvable; P1-7 `LockExecutionResult`; P2-в AUM max-age 5 мин | 1325 |
| 2026-09-07/08 | P1-8 fencing | `LeaseFence` (isHeld читает Redis) + `runExclusiveFenced`; `EntryLeaseRecoveryGate` (entry halt до reconcile); ≤1 физ. ордер на логический вход | 1334 |
| 2026-09-07 | Production-readiness (7) | P0-1 rolling DD по времени свечи; R2 futures PnL −комиссия; R1 futures pre/post-gates; E1 compensation; E3 limited reconcile; D1 recoverAll; B1 robustness SL/TP пункты; B2 multi-seed MC | 1340 |
| 2026-09-08 | ktlint-остатки | формат `OrderPurpose`/`RestOrderTransport`/тестов; helper `anyPurpose()` для инт-стабов | exit 0 |
| 2026-09-08 | P0/P1 вход. конвейер (закрытие) | P0-1 spec fail-closed; P0-2 fresh GO/balance TTL (30s); stressed margin ×1.5; signal freshness min(0.25×ATR, 5 ticks, 1%) + спред 0.1%/cap 0.5% | 1351 |
| 2026-09-08 | Издержки/funding 1 (`futures-cost-audit`) | LIVE-маржа без spec.go fallback; `FundingCosts`; сплит broker/exchange+slippage; docs 15/16; daily-loss docs; maxC 1 | 1363 |
| 2026-09-08 | Издержки/funding 2 (`futures-margin-funding-audit`) | side-specific GO; риск-снапшот; `FundingProvider` (MOEX); slippage в риск-бюджете | 1384 |
| 2026-09-09 | CNYRUBF 365д донакачка + gate (`research-wfa-cnyrubf`) | retention 90д → 730д; WFA/holdout/MC на 365д; фикс бага `passed` в `DeploymentGate`; фикс YAML `db.changelog-master`; скрипт `research_wfa_cnyrubf.ps1` | RESEARCH_ONLY |
| 2026-09-09 | P0-1 + код-P1 по аудиту `8b4ebd67` | P0-1 `report.avgPrice!!` → mark-to-market fallback + метрика `close.price_estimated` (регресс-тесты); P1 Clock `Europe/Moscow` в funding-провайдерах; `README_PRODUCTION_ARCHITECTURE.md` (дисклеймер RESEARCH_ONLY); research/production разделение в AGENTS.md | test+int+ktlint |
| 2026-09-14 | Этап 3 риск-аудит LLM-пути (R1–R4) | R1 риск-паритет (LLM-победитель через единый EntryRequest; StrategyDecision без qty/SL/TP — тест делегирования цепочки); R2 бюджет `trading.llm-signal-budget-ms=2000` + `withTimeout` → fail-closed HOLD + метрика `llm.signal.timeout`; R3 фикс StackOverflow `ResilientLlmClient.decoratedCall` (immutable-цепочка, регресс-тест с HTTP-сервером); R4 fail-closed LLM недоступен/таймаут/ошибка агента → HOLD; docs/17 §17.8 | test+int+ktlint |
| 2026-09-14 | Этап 5: shadow-режим LLM-сигнала | `trading.llm-signal-shadow=true` (+`llm-signal-source`): LLM участвует в конкуренции, но победа НЕ исполняется — `StrategyResult.shadowed` (не публикуется в order-admission, не пишется в Redis «последняя стратегия»); метрика `llm.signal.shadow{ticker,strategy}`; тесты StrategyRunnerTest (3); docs/17 §17.3/§17.7.2/§17.8 R5 | test+int+ktlint |
| 2026-09-16 | LLM-сигналы WFA 365д (`llm-signal-wfa-365d`) | guardrail-конфиг (tech-min-signal-strength/prompt-version/sample-every); промпт-версия `signal` в tech/strategy; grid-тюнинг 30д/90д/365д; **вердикт: edge НЕТ (PF=0.73, P=0.886, 69 OOS-сделок)**; кросс-тикер GAZP PF=0.73/SBER 0 сделок; research-инструменты остаются, LIVE не одобрено | test+int+ktlint |
| 2026-09-17 | Opus-проверка (`llm-signal-opus`) | fenced-JSON bug у Contrarian/Fundamental/Technical (0 сделок из-за fail-closed CRITICAL); `signal`-промпты contrarian/arbitrator; строковые скаляры Opus в `LlmResponseValidator` + тесты; **вердикт: Opus edge НЕТ (PF=1.11, P=0.48, 10 OOS-сделок)**; конвейер теперь реально генерирует сделки | test+int+ktlint |
| 2026-09-18 | Funding Veto research (`funding-veto`) | `FundingVetoGate` (входной гейт после NetEvGate, `FUNDING_VETO`, fail-closed); `FundingSnapshotService.latestForVeto` (LIVE: только свежий MOEX); конфиг `trading.funding-veto-*`; тесты FundingVetoGateTest/DecisionEngineTest/FundingSnapshotServiceTest; docs/16 + AGENTS.md | test+int+ktlint |
| 2026-09-18 | Донакачка SWAPRATE + funding-history в бэктест (`funding-history-backfill`) | миграция 036 `funding_history`; `FundingHistoryRepository` (R2DBC) + `MoexFundingHistoryLoader` (ISS history, пагинация); endpoint `funding-history`/`funding-history/status`; BacktestEngine P&L по фактическим SWAPRATE per-clering (fallback на config-ставку при отсутствии истории); CNYRUBF 509 дат 2024-09-18..2026-09-17 | test+int+ktlint |
| 2026-09-19 | Funding-veto калибровка WFA (`funding-veto-calibration`) | изолированный funding-veto входной фильтр в `BacktestEngine` (ML/MTF-паттерн, НЕ через неактивный в проде `BacktestRiskSimulator`); query-параметры `fundingVeto*` на `/backtest` `/validate` `/robustness` `/deployment-gate` (override bt.*, калибровка без перезапусков, проброс через `WfaConfig`→`BacktestValidator`/`FinalHoldoutValidator`→`MonteCarloAnalyzer`); метрика `bt_funding_veto_blocked_total`; **WFA 365д folds=6 conf=0.60: оптимум long 9 ₽ / short 2 ₽ (PF 1.58, Sharpe 0.93 vs baseline PF 0.88/−0.27); вердикт deployment-gate
  RESEARCH_ONLY (OOS 23 сделки < 100, holdout 5, edge нет)**; live-пороги НЕ менялись | test+int+ktlint |

Открытые пункты (вне скоупа / решение пользователя):
- Праздничный календарь MOEX в `FundingCosts` не моделируется (P1).
- live-сайзинг акций Kelly vs калибровочный x5/x6 — открытый вопрос (min приоритет).
- Funding-veto: research-пороги (long 9 ₽ / short 2 ₽) НЕ переносятся в live автоматически —
  live-конфиг `trading.funding-veto-*` остаётся default off/2.0; решение о live-порогах — за пользователем.
- WFA-прогон LLM с Kimi K3 (`moonshotai/kimi-k3`) отложен: исчерпан месячный лимит RouterAI
  (429, 2054,78 ₽ / 2000 ₽); модель подтверждена в `/api/v1/models`.

## LLM как источник сигнала (research, `research/llm-signal-source`, 2026-09-11)

- Требование пользователя «решения принимает строго LLM» **сейчас НЕ выполняется**:
  `StrategyRunner.kt:52` исключает `AdvisoryOnlyStrategy` из конкуренции за сигнал
  (C-001); `DiscretionaryStrategy` (полная LLM-цепочка) — только advisory/A-B;
  `LlmAdvisor` меняет уверенность (−0.30..+0.15) и VETO лишь при CRITICAL; направление
  всегда за детерминированными стратегиями.
- Дизайн зафиксирован в `docs/17-llm-signal-source.md` (ADR). Ключевые флаги
  (research, дефолт off): `trading.llm-signal-source` (LLM участвует в конкурентном
  выборе сигнала), `trading.llm-signal-only` (только LLM), `trading.llm-signal-shadow=true`
  (**РЕАЛИЗОВАН, этап 5, 2026-09-14**: LLM-победитель логируется — `StrategyResult.shadowed`,
  метрика `llm.signal.shadow{ticker,strategy}` — но НЕ исполняется: сигнал не публикуется
  в order-admission и не пишется в Redis «последняя стратегия»; см. docs/17 §17.8 R5).
  Роль `LlmAdvisor` сохраняется как fail-safe слой.
- Новостной источник — **rg.ru** (проверка 2026-09-11): публичный доступ закрыт
  CAPTCHA `qauth` (все GET → 401, RSS в браузере). **Решение пользователя (2026-09-11):
  в LIVE будет платная подписка rg.ru** — под неё `RgRuNewsProvider` РЕАЛИЗОВАН
  (`infrastructure/news/`, конфиг `news.*`: enabled/base-url/`{ticker}`/`{hours}`/api-key/
  max-items=5/timeout-ms=5000/ttl-minutes=15, Redis-кэш, fail-closed, метрики). Тесты
  `RgRuNewsProviderTest` + `FundamentalAnalysisAgentNewsTest` (сценарии хорошая/плохая
  новость, 401, disabled, невалидный JSON) — зелёные; test+integrationTest+ktlintCheck пройдены.
  Интерфейс `IssuerDataProvider.newsFor(ticker, hours)` и интеграция в
  `FundamentalAnalysisAgent` (переменная `issuerNews`, fingerprint только стабильные
  поля) — в коде. Без подписки (`news.enabled=false`) провайдер молча возвращает пустой
  список — NEUTRAL-база, ничего не ломается.
- Бэктест «строго LLM»: профиль `backtest` + `bt.agent.enabled=true` +
  `bt.agent.live-strategies=false` (сейчас default `live-strategies=true`, LLM не гоняется).
- Блокер валидации: **`LLM_API_KEY` в `.env` отсутствует** → все LLM-агенты на
  детерминированных fallback (smoke-only до появления ключа).
- `docs/03-llm-pipeline.md` описывает текущие 6 агентов и не менялся для этого дизайна.
- Риск-аудит LLM-пути (этап 3, 2026-09-14; docs/17 §17.8): `trading.llm-signal-budget-ms=2000`
  (env `TRADING_LLM_SIGNAL_BUDGET_MS`) — `LlmSignalStrategy.evaluate` под `withTimeout` → при
  превышении fail-closed HOLD + метрика `llm.signal.timeout`; риск-паритет структурный
  (StrategyDecision/Signal без qty/SL/TP, риск-поля — только OrderBuilder); фикс
  StackOverflow в `ResilientLlmClient.decoratedCall` (immutable-цепочка, регресс-тест).