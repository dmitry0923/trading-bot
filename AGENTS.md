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
  **Праздничный календарь MOEX моделируется** (`MoexHolidayCalendar`, P1 закрыт 2026-09-22):
  нерабочие дни = выходные + гос. праздники РФ (новогодние 1–8 янв, 23 фев, 8 мар, 1/9 мая,
  12 июн, 4 ноя) + переносы/спец-дни из `funding.holidays` (env `FUNDING_HOLIDAYS`, yyyy-MM-dd
  через запятую). Календарь применяется в live (P&L futures) и backtest (число клирингов);
  переносы правительственного производственного календаря задаются явно в `funding.holidays`.
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
- Конфиг (`trading.*`, env `TRADING_FUNDING_VETO_*`): `enabled=false` (research; решение
  пользователя 2026-09-22 — live остаётся off, research-пороги 9/2 НЕ переносятся),
  пороги default 2.0. WFA-калибровка порогов возможна: исторический ряд SWAPRATE
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
| ~~730д (OOS, folds=8)~~ | ~~те же~~ | ~~+104.4%~~ | ~~1.22~~ | - | ~~63~~ |

> **ОПРОВЕРГНУТО 2026-09-20 (см. «730д WFA детерминированной стратегии CNYRUBF» ниже):**
> повторный прогон той же калибровки на полной живоtikce-истории (46 124 свечи 2024-09-19..2026-09-19,
> с издержками/funding/liq-симуляцией) даёт OOS **−80.4%**, PF 0.72, consistency 0.25, robust=false —
> старый «+104%» был получен на неполной истории/старом режиме издержек. 730д OOS убыточен.

Выводы:
- OOS стабильно положителен на 365д (+83% при folds=6), но consistency 33–50%; `robust=false`.
  Слабый кандидат — live без доп. фильтра не рекомендуется.
- **730д (2026-09-20, полная история): OOS убыточен (−80.4%, PF 0.72, consistency 0.25)** —
  детерминированный edge на истории через 2024 regime не держится (см. ниже).
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
- **moonshotai/kimi-k3 на CNYRUBF (WFA, 2026-09-21, 180д MINUTE_10 folds=6 sample-every=240,
  RouterAI `moonshotai/kimi-k3`, aggressive/th=0.40, `LLM_DISABLE_REASONING=true`, `LLM_BUDGET_ENABLED=false`)**:
  OOS сделок **33**, Return −0.53%, PF=**0.71**, consistency 0.500, edge P=**0.77**, Sharpe −0.74,
  CI [−56.5, +26.5] — **edge НЕТ**. Тот же вывод, что по DeepSeek/qwen3-32b/Opus: LLM-сигналы
  edge не дают; детерминированные стратегии CNYRUBF MINUTE_10 остаются единственным значимым
  источником. Прогон на полных 365д×folds=6 НЕ умещается в `--spring.mvc.async.request-timeout`
  даже 3 ч (~3000 вызовов × 5 агентов/сэмпл, reactor-nio параллельность) — 180д уложился
  за 46.5 мин, выборка 33 OOS-сделки достаточна для вывода. Параметры kimi: контекст 1M,
  вход 186 ₽/1M (RouterAI, smart-routing на самый дешёвый провайдер Relace), выход 930 ₽/1M;
  thinking-модель → `LLM_DISABLE_REASONING=true` обязателен (иначе content пустой, budget
  уходит в reasoning_tokens); дефолтный `LLM_MAX_TOKENS_PER_MINUTE=4000` душит параллельные
  WFA-вызовы (каждый резервирует estimate+maxTokens=4096 ≥ лимита) — для research отключать
  `LLM_BUDGET_ENABLED=false` (скрипты research_wfa_kimi.ps1).
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

### WFA-калибровка ML-фильтра направления (2026-09-20)

Прогон на live-стеке (postgres+redis, `java -jar` с `--bt.ml-direction-enabled=true` и
`--spring.mvc.async.request-timeout=600000`). Инструмент — query-оверрайды ML-фильтра на API
(`buildSignalGenerator` в ApiController, паттерн funding-veto): `mlDirectionEnabled`,
`mlDirectionHorizonBars`, `mlDirectionMinReturnPercent`, `mlDirectionLearningRate`, `mlDirectionL2`,
`mlDirectionSignalMargin`, `mlDirectionBlockOnUnknown`. WFA: CNYRUBF, 365д, MINUTE_10, folds=6, conf 0.60.

| Конфиг | OOS Ret | OOS PF | OOS Sharpe | OOS Trades | Consistency |
|--------|---------|--------|------------|-----------|-------------|
| **ML выкл (честный baseline)** | **+0.96%** | **1.63** | **+1.00** | 26 | **0.667** |
| ML вкл (default m=0.05) | −0.07% | 0.96 | −0.05 | 17 | 0.333 |
| ML m=0.08 | +0.13% | 1.09 | +0.16 | 19 | 0.500 |
| ML blockUnknown | +0.78% | 2.29 | +0.91 | 11 | 0.500 |
| ML m=0.10+block | +0.43% | 1.70 | +0.73 | 11 | 0.333 |
| ML hor=10 | +0.14% | 1.11 | +0.18 | 17 | 0.500 |

- **Вывод: ML-фильтр направления edge НЕ добавляет.** Честный baseline (ML off) OOS лучше всех
  ML-вариантов: +0.96%/PF 1.63/Sharpe +1.00/consistency 0.667 против −0.07%/PF 0.96 (−0.05) у
  дефолтного ML. Все `robust=false` (тонкая выборка 11–26 сделок; тот же ограничитель, что у
  funding-veto/визных путей).
- IS-скрининг был обманчив: `blockUnknown`/`m=0.10+block` давали PF 2.2–2.97/Sharpe 1.13–1.24 на
  `/backtest`, но резали выборку вдвое (7–11 сделок) и OOS НЕ подтвердились — классический overfit.
- **IS-картина по margin парадоксальна**: чем выше signalMargin (0.03→0.20), тем БОЛЬШЕ сделок
  (14→23) — ML с высоким порогом реже выдаёт veto-enough сигнал и пропускает входы; блокинг-режим
  (blockOnUnknown) — единственное, что реально режет входы.
- Baseline здесь +0.96% (consistency 0.667) vs funding-veto-baseline −0.23% — расхождение
  обусловлено сменой генератора сигналов исследования (`LiveStrategyBacktestSignalGenerator` вместо
  прежнего конвейера), а не стратегией.
- **Решение: ML-фильтр направления НЕ включается в research-варианты бэктеста по умолчанию**
  (`bt.ml-direction-enabled` остаётся off, `bt.ml-direction-*` — research). Флатификация: 26 OOS-сделок
  слишком мало; детерминированные стратегии CNYRUBF MINUTE_10 остаются единственным значимым
  источником (PF 2.15, P=0.0425).

### Диверсификация по акциям (WFA 365д folds=6, leverage x5, 2026-09-21)

Цель 100%/год на CNYRUBF недостижима (730д OOS −80.4%) → проверка альтернативных тикеров с полной
историей (GAZP/NVTK/PLZL/SBER, 56k свечей MINUTE_10 с 2024-09-19). Прогон на live-стеке
(`java -jar`, async-таймаут 1 ч), скрипт `research_wfa_diversification.ps1` (TickerCsv).
WFA: 365д, MINUTE_10, folds=6, leverage x5, stockGrid (SL/TP % в In-sample), conf 0.60.

| Ticker | OOS Ret | OOS PF | OOS Sharpe | OOS Trades | Consistency | P(noEdge) | robust |
|--------|---------|--------|------------|-----------|-------------|-----------|--------|
| GAZP | −20.2% | 0.59 | −1.57 | 47 | 0.167 | 0.99 | false |
| NVTK | −14.9% | 0.78 | −0.66 | 53 | 0.167 | 0.80 | false |
| SBER | −8.3% | 0.75 | −0.65 | 36 | 0.333 | 0.78 | false |
| **PLZL** | **+19.0%** | 1.26 | **+0.92** | 70 | 0.500 | 0.21 | false |

- **Вывод: у 3 из 4 акций OOS убыточен (GAZP −20%, NVTK −15%, SBER −8%); PLZL — единственный
  положительный кандидат (OOS +19.0%, PF 1.26, Sharpe +0.92, 70 сделок), но edge не значим
  (P=0.21), consistency 0.5, `robust=false`.** Диверсификация по этому набору НЕ даёт устойчивый
  портфельный edge: ни один тикер не проходит статистический порог.
- Сравнение с IS-калибровкой 2026-08 (GAZP +61.8%, PLZL +55.9%, NVTK +42.6%, SBER +6.3%): IS-успех
  НЕ выживает в OOS у GAZP/NVTK (классический overfit на x5-сетке), частично держится лишь PLZL.
- **Решение: диверсификация откладывается** — включать PLZL в live нельзя (P=0.21, thin sample);
  дополнительная история/Иной таймфрейм может пересмотреть. CNYRUBF MINUTE_10 остаётся единственным
  значимым источником (PF 2.15, P=0.0425).

### Таймфрейм-диверсификация CNYRUBF (HOUR_1/DAY_1 ресемплинг, WFA 365д folds=6, 2026-09-21)

Продолжение диверсификации (после акций): проверка старших таймфреймов CNYRUBF через ресемплинг
`CandleResampler` (MINUTE_10 → HOUR_1/DAY_1, поддерживается `/validate?timeframe=`). Прогон на
live-стеке, скрипт `research_wfa_diversification.ps1` (добавлен параметр `-Timeframe`), conf 0.60,
futuresGrid SL/TP (пункты), цикл тот же `LiveStrategyBacktestSignalGenerator`.

| Timeframe | OOS Ret | OOS PF | OOS Sharpe | OOS Trades | Consistency | P(noEdge) | robust |
|-----------|---------|--------|------------|-----------|-------------|-----------|--------|
| MINUTE_10 (CV, 2026-09-09/20) | −0.23..+0.96% | 0.88..1.63 | −0.27..+1.00 | 26..31 | 0.5..0.667 | 0.21..0.99 | false |
| **HOUR_1** | **+0.69%** | **1.57** | +0.68 | 18 | **0.667** | 0.24 | false |
| DAY_1 | −0.14% | 0.0 | 0.0 | **1** | 0.0 | 1.00 | false |

- **Вывод: ресемплинг в старший таймфрейм edge НЕ добавляет.** HOUR_1 — положительный (PF 1.57,
  consistency 0.667 наравне с лучшими MINUTE_10-прогонами), но 18 OOS-сделок << 100, edge не значим
  (P=0.24), `robust=false` — тот же тонковатый профиль, что у минимальной выборки MINUTE_10.
  DAY_1 практически не торгует (1 сделка) — сигнал на суточных свечах с текущим конфигом не
  генерируется, вывод неинформативен.
- Диверсификация (акции 2026-09-21 + таймфреймы 2026-09-21 + перпетуалы 2026-09-22) устойчивого
  edge не дала нигде; единственный значимый источник остаётся CNYRUBF MINUTE_10 детерминированный
  (PF 2.15, P=0.0425).

### Диверсификация по фьючерсным перпетуалам (WFA 730д, 2026-09-22)

Продолжение диверсификации (после акций/таймфреймов): проверка бессрочных фьючерсов MOEX
(перпетуалы, LASTDELDATE 2100, SECID == ticker — загрузка истории работает без склейки контрактов).
Скрипт `research_wfa_perps.ps1` (WFA 730д×folds=8 conf=0.60, калибровочный риск-профиль
riskPerTradePercent=30&futuresMaxContractsPerPosition=100, futuresGrid). История загружена напрямую
с MOEX ISS (interval=10): USDRUBF 46 140 свечей, EURRUBF 43 319, GLDRUBF 50 293, IMOEXF 50 385;
funding_history донакачан (SWAPRATE, 508 дат/тикер). Новые spec в `InstrumentsConfig`+`application.yml`
(USDRUBF/EURRUBF/GLDRUBF/IMOEXF — research-вселенная, LIVE-guard оставляет вход только CNYRUBF).
SLVRUBF исключён (полная история недоступна, контракт торгуется только с 2026-03).

| Ticker | IS ret (maxC=1) | IS PF | IS trades | OOS Ret | OOS PF | OOS Sharpe | OOS Trades | Consistency | P(noEdge) | robust |
|--------|-----------------|-------|-----------|---------|--------|------------|------------|-------------|-----------|--------|
| USDRUBF | +7.27% | 1.68 | 79 | **+1.6%** | **1.03** | +0.19 | 74 | **0.5** | **0.47** | false |
| EURRUBF | +3.23% | 1.22 | 100 | −49.9% | 0.65 | −1.21 | 81 | 0.25 | 0.93 | false |
| GLDRUBF | −0.71% | 0.32 | 127 | −162.1% | 0.05 | −1.09 | 120 | 0.0 | 1.00 | false |
| IMOEXF | +1.94% | 1.29 | 94 | −628.7% | 0.12 | −1.32 | 94 | 0.0 | 1.00 | false |

- **Вывод: фьючерсные перпетуалы edge НЕ дают.** USDRUBF — единственный положительный кандидат
  (IS PF 1.68, OOS +1.6%/PF 1.03/consistency 0.5/74 сделки), но OOS P(noEdge)=0.47 (статистическая
  нулёвка) и `robust=false` — тот же тонковатый профиль, что у PLZL/акций. EURRUBF/GLDRUBF/IMOEXF
  OOS глубоко убыточны (PF 0.05–0.65) — конвейер на этих тикерах торгует в минус.
- Характерно: все перпетуалы дают БОЛЬШЕ сделок, чем CNYRUBF (74–120 OOS против 21–26 у CNYRUBF),
  но без edge — чаще входы = хуже PF (тот же паттерн, что у LLM/conf 0.50). IS-доходность не
  выживает в OOS ни у одного тикера.
- **Решение: перпетуалы в live НЕ включаются** (LIVE-guard allowlist остаётся только CNYRUBF);
  research-вселенная и скрипт остаются в репозитории. CNYRUBF MINUTE_10 детерминированный остаётся
  единственным значимым источником (PF 2.15, P=0.0425).
- **Решение: старшие таймфреймы в research-цикл НЕ вводятся** (`bt.timeframe` не меняется,
  default MINUTE_10). HOUR_1 можно пересмотреть при большем горизонте/ином профиле сделок — вне
  текущего скоупа.

### Max-hold (выход по времени удержания, WFA 365д folds=6, 2026-09-21)

Второй кандидат «иное время удержания»: принудительный выход по числу баров MINUTE_10 (MKT по
close), когда SL/TP (широкие 300/600) не сработали за долгий горизонт. Реализовано `bt.max-hold-bars`
(env `BT_MAX_HOLD_BARS`) + query-оверрайд `maxHoldBars` на `/validate` `/backtest` `/robustness`
(паттерн funding-veto; в `BacktestEngine` после SL/TP, приоритет ниже liq/SL/TP, выше новых сигналов).
Прогон на live-стеке, скрипт `research_wfa_maxhold.ps1`, conf 0.60.

| maxHold (бары) | OOS Ret | OOS PF | OOS Sharpe | OOS Trades | Consistency | P(noEdge) | robust |
|----------------|---------|--------|------------|-----------|-------------|-----------|--------|
| off (baseline) | +0.96% | 1.63 | 1.00 | 26 | 0.667 | 0.15 | false |
| 30 (5ч) | −0.05% | 0.93 | −0.19 | 36 | 0.667 | 0.58 | false |
| 46 (1д) | +0.19% | 1.23 | +0.51 | 36 | 0.50 | 0.30 | false |
| 92 (2д) | +0.24% | 1.21 | +0.45 | 34 | 0.667 | 0.33 | false |
| 184 (4д) | +0.67% | 1.62 | +1.13 | 32 | 0.667 | 0.13 | false |
| **368 (8д)** | **+1.17%** | **2.15** | **+1.45** | 27 | **0.667** | **0.08** | false |
| 736 (16д) | +1.12% | 1.80 | +1.29 | 28 | 0.667 | 0.09 | false |

- **Вывод: max-hold УЛУЧШАЕТ OOS относительно baseline** (368 баров/8д: PF 2.15 vs 1.63, Sharpe
  +1.45 vs +1.00, P=0.08), короткий maxHold (5ч–1д) деградирует — согласовано с трендовым
  характером стратегии (широкие стопы обязательны). Но 27 OOS-сделок << 100 → `robust=false`,
  edge статистически НЕ значим (P=0.08 > 0.05). Это сильнейший кандидат из всех research-путей
  после детерминированной базы, но НЕ проход.
- **Комбо с funding-veto (2026-09-21)**: `maxHoldBars=368` + `fundingVetoEnabled=true` +
  пороги 9/2 ₽ в калибровочном риск-профиле (risk 30%/maxC 100) — OOS **+94.8%**, PF **2.27**,
  Sharpe **1.41**, P(noEdge)=**0.065** (vs profile-baseline PF 1.80/P=0.123; 25 сделок, robust=false).
  Комбинация слагает индивидуальные улучшения (mh 2.15, fv 1.58) и остаётся сильнейшим
  research-кандидатом, но по-прежнему не проходит: CI95 сделки включает ноль, P=0.065 > 0.05.
- **Риск-профиль НЕ решает проблему выборки**: при maxC 1→100, risk→30% число OOS-сделок НЕ
  растёт (26↔25) — входы все и так проходили маржинальный бюджет; увеличивается только
  абсолютный P&L (масштаб сайтеза). Частоту входов задаёт confidence gate, не риск.
- **Доводка порогов — плато (2026-09-21)**: сетка long 7–10 × short 0–1.5 даёт идентичный
  результат (PF 2.38–2.41, Sharpe 1.46–1.48, P=0.058, 23–24 сделки) — устойчивый оптимум,
  не острый пик; P=0.05 не пробивается, CI95 сделки по-прежнему включает ноль.
- **Снижение confidence до 0.50 разрушает edge (2026-09-21)**: выборка растёт 26→208 сделок,
  но OOS убыточен и в baseline (−124.8%, PF 0.74, P=0.954), и в комбо (−65.4%, PF 0.83, P=0.864).
  «Чем чаще торгуешь — тем хуже» (тот же паттерн, что у LLM): сигнал с conf 0.50 не
  предсказывает направление. Edge разрежен в высокоуверенных входах (conf 0.60 → PF 2.27);
  разреженность — природа стратегии, а не дефект выборки.
- **Deployment-gate комбо = REJECTED (2026-09-22, формальный вердикт)**: полный пайплайн на
  той же калибровке (368 баров + funding-veto 9/2, risk 30%/maxC 100, conf 0.60, 365д folds=6)
  отбракован ВСЕМИ проверками: backtest PF 1.63/MDD 45.8%, WFA OOS PF **0.92**/consistency **0.333**
  (21 сделка < 100), edge P(noEdge)=**0.551**, holdout +1.12% но 5 сделок < 30, MC p5=−31.0%/
  pLoss 14.7%/stressFailed 5. **Ключевой вывод: на dev-части истории (≈80%, до holdout-границы)
  комбо, откалиброванное на полной 365д, даёт OOS PF 0.92 — плато `P=0.058` из `/validate` было
  артефактом подбора на всей истории.** Тот же развал на раннем (2024) regime, что в 730д-прогоне:
  edge детерминированной базы исторически нестабилен. | test+int+ktlint |
- **Решение: `bt.max-hold-bars` остаётся off (0)** — усиление само по себе не создаёт устойчивый
  edge; max-hold=368 можно комбинировать с будущими фильтрами/профилями (вне скоупа). Цель
  «100%/год» по-прежнему недостижима.

### 730д WFA детерминированной стратегии CNYRUBF (2026-09-20, калибровочный риск-профиль)

Прогон на live-стеке (postgres+redis, `java -jar` с `--spring.mvc.async.request-timeout=3600000`,
история 730д уже в БД: 46 124 свечи MINUTE_10 2024-09-19..2026-09-19, funding_history 509 дат).
Скрипт `research_wfa730_cnyrubf.ps1`. IS base (`/backtest?days=730`, live-like сайзинг maxC=1):
**+0.2%, PF 1.11, 86 сделок, MDD 0.4%** (285 с). WFA (`/validate?days=730&folds=8&conf=0.60`
+ калибровочный риск `riskPerTradePercent=30&futuresMaxContractsPerPosition=100`, SL/TP-сетка
futuresGrid в In-sample, 77 OOS-сделок):

| Метрика | Значение |
|---------|----------|
| OOS Return | **−80.4%** |
| OOS PF | **0.72** |
| OOS Sharpe | +0.25 |
| Consistency | **0.25** |
| Robust | **false** |

- **Вывод: детерминированная стратегия НЕ выживает на 730д истории.** OOS на полной выборке
  убыточен (−80.4%, PF 0.72, consistency 0.25) — агрессивный риск-профиль (risk 30% / maxC 100)
  разворачивает прежний «edge» (PF 2.15 на ~52 неделях) в глубокий минус на истории через 2024
  regime. 86 IS-сделок при maxC=1 дают +0.2%/год — потолок порядка 1%+ именно из-за сайзинга.
- **Ответ на цель «100% в год»: на текущей детерминированной стратегии недостижим.** Единственный
  источник IS-доходности 3-значного уровня (risk 30%/maxC 100) в OOS даёт −80%; live-сайзинг (maxC 1,
  Kelly) даёт ~1%. Ни конификация (дынные фильтры ML/funding/session/pullback), ни LLM, ни
  увеличение истории не создают устойчивый edge выше уровня PF~1 на 730д.
- Live-параметры НЕ менялись (maxC=1, Kelly, LIVE-guard — в силе). Кандидаты: диверсификация
  по таймфреймам/тикерам + отдельный IS/OOS-профиль для research против сложного капитала —
  решение за пользователем.

### WFA-калибровка входных фильтров (session + pullback, 2026-09-20)

Прогон на live-стеке (postgres+redis, `java -jar`, `--spring.mvc.async.request-timeout=600000`).
Инструмент — query-оверрайды входных фильтров на API через `buildSignalGenerator` в ApiController
(паттерн funding-veto/ML): `sessionFilterEnabled/StartMinutes/EndMinutes`,
`pullbackFilterEnabled/EmaPeriod/MaxDeviationPercent/BlockOnUnknown`.
Реализация — `EntryFilters`/`EntryFilterOverrides` в `LiveStrategyBacktestSignalGenerator`
(после confidence gate, ДО ML-фильтра; блокировка = HOLD; исключение → не блокировать).
Проброс `EntryFilters.from(backtestConfig)` через `BacktestSignalGeneratorConfig`/`MonteCarloAnalyzer`/
`FinalHoldoutValidator`/`PanelBacktestService` (bt.*). Скрипт `research_calibrate_entry_filters.ps1`
(IS-сетка 12 конфигов + WFA-сетка 5 кандидатов, флаги `-SkipWfa`/`-SkipIs`).
WFA: CNYRUBF, 365д, MINUTE_10, folds=6, conf 0.60. IS-скрининг: session-окна режут входы и проигрывают
baseline (кроме «день 14:00–18:00»: PF 1.24, 12 сделок); pullback dev=0.3% давал лучший IS (PF 1.42,
Sharpe 0.80, 25 сделок).

| Конфиг | OOS Ret | OOS PF | OOS Sharpe | OOS Trades | Consistency |
|--------|---------|--------|------------|-----------|-------------|
| **baseline (фильтры off)** | **+0.96%** | 1.63 | **+1.00** | 26 | **0.667** |
| pullback ema20 dev=0.3% | +0.85% | **1.78** | +0.98 | 18 | 0.500 |
| pullback ema20 dev=0.5% | +0.71% | 1.47 | +0.77 | 25 | 0.667 |
| session 14:00–18:00 | −0.28% | 0.55 | −0.73 | 11 | 0.167 |
| session 14–18 + pb 0.5% | −0.28% | 0.55 | −0.73 | 11 | 0.167 |

- **Вывод: входные (session/pullback) фильтры edge НЕ добавляют.** Session-фильтр однозначно в минус
  (OOS −0.28% vs baseline +0.96%, PF 0.55, consistency 0.167). Pullback 0.3% даёт чуть выше PF (1.78)
  ценой выборки (18 vs 26 сделок), но OOS-доходность ниже baseline и `robust=false` — так же, как у
  ML-фильтра, IS-успех не выживает в OOS (флатификация на 18–26 сделках).
- **Решение: фильтры НЕ включаются** — `bt.session-filter-enabled` и `bt.pullback-filter-enabled`
  остаются off (default), `bt.session-filter-*`/`bt.pullback-filter-*` — research (калибровка через
  query-оверрайды без перезапуска). Детерминированные стратегии CNYRUBF MINUTE_10 остаются
  единственным значимым источником (PF 2.15, P=0.0425).

### ORB-фильтр входа (Opening Range Breakout, WFA 365д folds=6, 2026-09-23)

Гипотеза edge «вход в направлении пробоя дневного диапазона»: opening range = High/Low первых
`orbWindowBars` баров дня (день = `time.toLocalDate()` бара; CNYRUBF MINUTE_10: открытие 06:50),
пробой вверх → только LONG, вниз → только SHORT. Реализовано `EntryFilters.orbDirection` +
query-оверрайды `orbEnabled/orbWindowBars/orbStrictBreakout/orbBlockOnUnknown` на `/backtest`
`/validate` `/robustness` `/holdout` `/deployment-gate` (паттерн funding-veto/ML, в
`LiveStrategyBacktestSignalGenerator` после session/pullback, до ML-фильтра; strict=true — внутри
диапазона HOLD-блок, strict=false — пропуск; `bt.orb-*`/env `BT_ORB_*`). Прогон на live-стеке,
скрипт `research_wfa_orb.ps1`, conf 0.60, калибровочный риск-профиль risk 30%/maxC 100.

| Конфиг | OOS Ret | OOS PF | OOS Trades | Consistency | P(noEdge) | robust |
|--------|---------|--------|-----------|-------------|-----------|--------|
| baseline (фильтры off) | +0.34% | 1.27 | 27 | 0.500 | 0.32 | false |
| orb w6 strict | −9.35% | 0.94 | 26 | 0.500 | 0.57 | false |
| orb w12 strict | −51.4% | 0.63 | 24 | 0.167 | 0.83 | false |
| orb w24 strict | −51.3% | 0.63 | 24 | 0.167 | 0.83 | false |
| orb w6 loose | +46.3% | 1.35 | 27 | 0.500 | 0.28 | false |
| **orb w12 loose** | **+66.8%** | **1.55** | 26 | **0.667** | **0.19** | false |
| orb w36 loose | +33.8% | 1.27 | 27 | 0.500 | 0.32 | false |
| **orb w12 loose + fv 9/1.5** | **+89.4%** | **1.87** | 23 | **0.667** | **0.117** | false |
| orb w12 loose + fv 9/2 | +85.2% | 1.79 | 24 | 0.667 | 0.13 | false |
| orb w12 loose + fv 10/1.5 | +89.4% | 1.87 | 23 | 0.667 | 0.12 | false |

- **Вывод: ORB как направленный фильтр edge НЕ даёт.** strict-режим (внутри диапазона HOLD) убыточен
  (w6 −9.35%, w12/w24 −51%) — блокирует большинство входов, остаются сделки против тренда.
  loose-режим улучшает OOS (w12 +66.8%/PF 1.55/consistency 0.667), но P=0.19 далёк от значимости;
  комбо с funding-veto 9/1.5 (лучший) — +89.4%/PF 1.87, но P=0.117 > 0.05, 23 сделки << 100,
  `robust=false`. Плато порогов w12≈w24, long 9–10 × short 0.5–1.5 — устойчиво, не острый пик.
- **Deployment-gate комбо = REJECTED (формальный вердикт)**: полный пайплайн (orb w12 loose +
  fv 9/1.5, risk 30%/maxC 100, conf 0.60, 365д folds=6): backtest PASS (Sharpe 1.25/MDD 0.4%/PF 1.56/
  19 сделок), но WFA OOS **PF 0.78**/consistency **0.500** (20 сделок < 100), edge P(noEdge)=**0.665**,
  holdout +1.1% (5 сделок < 30), MC p5=−0.32%/pLoss 18.3%/stressFailed 4. **Тот же паттерн, что у
  максиа: плато `P=0.117` из `/validate` было артефактом подбора на полной истории; dev-часть OOS
  PF 0.78.** ORB-усиление не создаёт устойчивый edge вне исторической перестройки.
- **Решение: `bt.orb-*` остаются off** (`orbEnabled=false` default); ORB-фильтр и скрипт остаются
  research-инструментом (комбинация с будущими фильтрами — вне скоупа). Цель «100%/год» по-прежнему
  недостижима.

### Time-direction фильтр входа (блок утренних LONG / дневных SHORT, WFA 365д folds=6, 2026-09-24)

Гипотеза из декомпозиции трейд-лога CNYRUBF (730д IS, maxC=1, `includeTrades=true`): основные
убыточные кластеры — утренние LONG 7–11ч (15 сделок, сумма −319 ₽, TP 1/15) и дневные SHORT
13–16ч (10 сделок, −358 ₽, TP 0/10); вечер 18–23ч прибылен (+688 ₽/22 сделки). Реализовано
`EntryFilters.blocksDirection(time, action)` — блок LONG при `hour <= longBlockUntilHour`, блок
SHORT при `hour in shortBlockStart..shortBlockEnd` + query-оверрайды `timeDirectionEnabled/
timeDirectionLongBlockUntilHour/timeDirectionShortBlockStartHour/timeDirectionShortBlockEndHour`
на `/backtest` `/validate` `/robustness` `/holdout` `/deployment-gate` (паттерн funding-veto/ORB;
в `LiveStrategyBacktestSignalGenerator` после session/pullback+ORB, до ML-фильтра; `bt.time-direction-*`
env `BT_TIME_DIRECTION_*`; тесты `EntryFiltersTest` 2 кейса). Прогон на live-стеке, скрипт
`research_wfa_timedirection.ps1`, conf 0.60, калибровочный риск-профиль risk 30%/maxC 100.

| Конфиг | OOS Ret | OOS PF | OOS Trades | Consistency | P(noEdge) | robust |
|--------|---------|--------|-----------|-------------|-----------|--------|
| baseline (off) | +33.8% | 1.27 | 27 | 0.500 | 0.322 | false |
| long<=11 | +46.6% | 1.58 | 24 | 0.500 | 0.210 | false |
| **long<=9** | **+100.1%** | **1.97** | 24 | **0.667** | **0.088** | false |
| short 13–16 | +46.6% | 1.58 | 24 | 0.500 | 0.210 | false |
| long11 \| short13-16 | +46.6% | 1.58 | 24 | 0.500 | 0.210 | false |
| long9 \| short13-16 | +100.1% | 1.97 | 24 | 0.667 | 0.088 | false |

- **Вывод: time-direction фильтр как направленный edge НЕ даёт.** Блок длинных до 09ч улучшает OOS
  (PF 1.97 vs 1.27, ret +100.1% vs +33.8%, P=0.088 близко к значимости, consistency 0.667), но
  24 OOS-сделки << 100, P=0.088 > 0.05 → статистически НЕ значим. Блок SHORT 13–16ч идемпотентен
  (SELL-входы в этом окне на выборке не встречались — результат идентичен long-конфигам).
- **Deployment-gate long<=9 = REJECTED (формальный вердикт)**: полный пайплайн (long<=9,
  risk 30%/maxC 100, conf 0.60, 365д folds=6): backtest PF 1.454/MDD 45.8%/20 сделок, но
  **WFA OOS PF 0.840**/consistency 0.667 (20 сделок < 100), edge P(noEdge)=**0.629**, holdout
  6 сделок < 30, MC p5=−0.44%/stressFailed 5. **Тот же паттерн, что у max-hold/ORB: плато
  `P=0.088` из `/validate` было артефактом подбора на полной истории; dev-часть OOS PF 0.84.**
  Время-фильтр не создаёт устойчивый edge вне исторической перестройки.
- **Решение: `bt.time-direction-enabled` остаётся off** (default); фильтр и скрипт остаются
  research-инструментом. Декомпозиция трейд-лога (по часам/дням недели/ATR/hold) фиксирует
  структуру P&L, но входной фильтр на ней показал OOS-развал (консистентно с funding-veto/ML/
  session/pullback/ORB). Цель «100%/год» по-прежнему недостижима.

### Комбо-калибровка 730д (2026-09-25/26, `combo730-wfa`)

Совместный перебор WFA 730д/folds=8/conf 0.60/risk 30%/maxC 100 (калибровочный профиль)
на CNYRUBF вокруг связки `timeDirection + fundingVeto + maxHold`. Оркестратор
`scripts/research_wfa_combo730_wd_orch.ps1` + раннер `..._wd_runner.ps1` (heartbeat,
авторестарт зависших, `ServerDownThreshold=6`, таймаут логина 30с, `-ConfigFile`),
журнал и методика — `docs/18-wfa-combo730-runbook.md`; автодокументирование результатов
(`autodoc.ps1`, секции `AUTO-WAVEn` в runbook). 3 волны × 15-16 конфигов, все без FAIL.

| Конфиг (все: td=block LONG до 9ч, fv=funding-пороги, mh=maxHoldBars) | OOS Ret 730д | Annual | OOS PF | Сделок | P(noEdge) |
|---|---|---|---|---|---|
| **td-fv7-7 (волна 3, mh368)** | **+191.4%** | **+70.9%** | 2.70 | 45 | — |
| **td-mh-fv6-6** | +187.5% | +69.6% | **2.73** | 40 | **0.01** |
| td-fv-mh1095 | +186.2% | +69.2% | 2.18 | 43 | 0.03 |
| tdL8-fv-mh | +160.9% | +61.5% | 2.26 | 50 | 0.03 |
| td-mh-fv4-4 | +155.9% | +60.0% | 2.74 | 35 | 0.02 |
| td-mh-fv9-2 (волна 1, лидер волны 1) | +148.1% | +57.5% | 2.04 | 47 | 0.04 |
| td-mh-fv12-12 | +123.1% | +49.4% | 1.97 | 59 | 0.04 |
| td-fv-mh184 | +97.4% | +40.5% | 1.63 | 49 | 0.12 |
| tdL9-mh368-ctl (без funding-veto) | +87.5% | +36.9% | 1.70 | 65 | 0.08 |
| tdL11-fv-mh | +15.4% | +7.4% | 1.13 | 47 | 0.37 |
| td-fv-mh-h1 (HOUR_1) | +12.3% | +6.0% | 1.11 | 18 | 0.43 |
| tdL10-fv-mh | 0.0% | 0.0% | 1.00 | 48 | 0.50 |
| одиночный tdL9 | +4.7% | +2.3% | 1.04 | 71 | 0.44 |
| одиночный mh368 | −12.9% | −6.7% | 0.92 | 86 | 0.62 |
| **baseline (без фильтров)** | **−17.1%** | **−8.9%** | **0.90** | 87 | 0.68 |
| одиночный fv9-2 | −75.6% | −50.6% | 0.58 | 69 | 0.94 |

Ключевые выводы (730д, расширяют выводы 365д):

- **Комбинация даёт переход из минуса в плюс: baseline OOS −17.1%/PF 0.90 → лучшие
  конфиги +187…+191% за 730д (PF 2.70-2.73, annual ≈ +70%/год), P(noEdge)=0.01-0.03.**
  Каждый фильтр по отдельности убыточен или нейтрален (fv9-2 −75.6%, tdL9 +4.7%,
  mh368 −12.9%) — эффект строго синергетический, не сумма.
- **Финансирование порогов: оптимум 6-7 ₽ (обе стороны) на 730д**, против 9/2 в 365д
  калибровке. 12/12 деградирует (PF 1.97), 4/4 даёт PF 2.74, но всего 35 сделок.
  Вывод 365д-калибровки (порог 9) на 730д НЕ переносится — горизонт меняет оптимум.
- **max-hold: плато 368-1095 баров** (оптимум 368 ≈ 6.5 торговых дней, PF 2.73);
  184 хуже (PF 1.63), 552/736 — между (1.37/1.20).
- **Граница time-direction очень узкая**: блок LONG до 9ч — лучший, 8 — чуть хуже,
  **10 обнуляет P&L (0.00%, PF 1.00)**. Почти бинарный эффект — при переносе в live
  рискованно.
- **`adaptiveConfidenceThreshold` 0.62/0.65 и SHORT-окно 13-16 не меняют набор сделок**
  (результат тождественен базовому) — после жёстких входных фильтров confidence-gate
  не связывает; дальше не калибровать.
- ORB, pullback, ML, session-фильтры в лидер **не идут** (в 365д уже отброшены).
  HOUR_1-ресемплинг слабее (PF 1.11, 18 сделок).
- **Все результаты `robust=false`** (35-87 OOS-сделок), годовой потолок ≈ +70% при
  калибровочной марже 30%/maxC 100 — это НЕ live-параметры (live: maxC=1, Kelly).
  Цель «100%/год» впервые близка в research, но формальной валидации
  (deployment-gate/holdout/MC на dev-части) лидеры пока не проходили.
- Оценки калибровки дрейфуют на ±2-3 п.п. при догрузке новых свечей
  (контроль tdL9-mh368: 90.07% → 87.51% за сутки истории).

### Аудит 10 пользовательских стратегий (2026-09-26, `strategies-audit`)

`docs/19-strategies-audit.md`: проверка реализуемости списка из 10 стратегий на текущем
стеке и данных. **Полноценно тестируются 2 (№1 VWAP-MR CNYRUBF, №7 ORB золото на
GLDRUBF), частично 2 (№4 overnight SBER, №8 обедный MR на RI). Остальные 6 упираются
в отсутствие данных:** SBERP и RGBI нет в БД, Brent (BR) нет, история Si/RI — всего
2.5 мес (2026-06..08), пары контрактов для календарного спреда нет, RUONIA нет,
**стакана/тиков в БД нет вообще** → №3 (order book imbalance) и №9 (VPIN)
не тестируются без нового data-слоя. Также не реализованы источники из LLM-ролей
(парсинг сайта ЦБ, календарь США EIA/CPI); новости только через `RgRuNewsProvider`
(`news.enabled`, дефолт off). Флаг «5% таймаутов LLM» в бэктесте не существует —
нужен `bt.agent.timeout-injection-rate`. Реализуемые ядра требуют новых research-фильтров
по отработанному шаблону (`bt.*` + query-override + тесты, дефолт off).

### Реализация стратегий №7 ORB-окно и №1 VWAP-MR (2026-09-26)

Обе реализуемые стратегии реализованы, дефолт **off**, live-путь не затронут.

**№7 ORB на золоте** (`8139342`): `EntryFilters.orbDirection` получил окно диапазона
`bt.orb-window-start-minutes`/`bt.orb-window-end-minutes` (query `orbWindowStartMinutes`/
`orbWindowEndMinutes`, минуты от полуночи). Диапазон = первые `orbWindowBars` баров дня
с первого бара ≥ start; проверка пробоя — только на барах ≥ end. Дефолт `0..1440` =
исходное поведение (обратная совместимость). Под стратегию: start=930, end=960,
bars=3 (MINUTE_10) → диапазон 15:30–16:00 МСК, вход на пробое после 16:00.

**№1 VWAP-MR** (`1af7944`, `ce684dc`): `IndicatorCalculator.vwap` (сессионный VWAP со
сбросом по дате), `.vwapStdDevPercent` (σ типичной цены в % от VWAP), `.adx` (по Уайлдеру).
`EntryFilters.vwapMrDirection`: вход только при |close−VWAP| ≥ `vwapMrDeviationSigma`·σ
И ADX(`vwapMrTimeframe`, HOUR_1) ≤ `vwapMrMaxAdx`; HOLD при отклонении < Nσ, высоком ADX,
нехватке данных/σ≈0 (fail-closed). Старший ТФ через `CandleResampler` c
`completedBefore = bar.time` (без lookahead) и **ограниченным lookback** (32 бара ТФ,
потолок 600 базовых баров — иначе O(n²) на 46k свечах).

**Найденный баг (важно для индикаторов):** на полностью плоской сессии σ ≈ 1e-14
(float-шум), а не 0 — деление отклонения на такую σ давало ложные BUY/SELL в тысячи σ.
Отсёк порогом `IndicatorCalculator.MIN_MEANINGFUL_VWAP_SIGMA_PERCENT = 1e-6` +
регрессионный тест. Для любых новых σ-метрик проверять вырожденный случай.

Не сделано: выход «возврат к VWAP» — движок позиций не имеет такого exit-типа (на текущих
прогонах выход = SL/TP grid бэктеста). WFA-прогоны №1/№7 — отдельная задача.

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
| 2026-09-20 | ML-фильтр направления калибровка (`ml-direction-calibration`) | query-оверрайды `mlDirection*` на `/backtest` `/validate` `/robustness` `/deployment-gate` `/holdout` через `buildSignalGenerator` (паттерн funding-veto; `MlDirectionOverrides` + `OnlineMlDirectionStrategy.from(config, overrides)`, null → bt.*); метрика WFA 365д folds=6 conf=0.60 на 6 конфигах: **честный baseline (ML off) лучший OOS (PF 1.63, Sharpe +1.00, +0.96%), дефолтный ML ухудшает (PF 0.96, −0.07%), blockUnknown PF 2.29 но 11 сделок** → ML-фильтр edge не даёт, `bt.ml-direction-enabled` остаётся off | test+int+ktlint |
| 2026-09-20 | Входные фильтры (session+pullback) калибровка (`entry-filters-calibration`) | query-оверрайды `sessionFilter*`/`pullbackFilter*` на `/backtest` `/validate` `/robustness` `/deployment-gate` `/holdout` (паттерн funding-veto/ML; `EntryFilters.from(config, overrides)` в `LiveStrategyBacktestSignalGenerator` после confidence gate, до ML-фильтра; HOLD-блокировка, исключение → не блокировать; `bt.session-filter-*`/`bt.pullback-filter-*` + env `BT_SESSION_FILTER_*`/`BT_PULLBACK_FILTER_*`; тесты `EntryFiltersTest` + 2 теста генератора); IS-сетка 12 конфигов + **WFA 365д folds=6 conf=0.60: baseline лучший OOS (PF 1.63, +0.96%), pb 0.3% PF 1.78 но 18 сделок, session 14–18 PF 0.55/−0.28%, комбо=session** → фильтры edge НЕ дают, `bt.session-filter-enabled`/`bt.pullback-filter-enabled` остаются off | test+int+ktlint |
| 2026-09-20 | 730д WFA детерминированной стратегии (`wfa-730d`) | прогон на полной истории (46 124 свечи MINUTE_10 2024-09-19..2026-09-19, funding_history 509 дат); IS base (maxC=1): +0.2%/PF 1.11/86 сделок; **WFA folds=8 conf=0.60 + риск 30%/maxC 100: OOS −80.4%, PF 0.72, consistency 0.25, robust=false (77 OOS-сделок)** → детерминированная стратегия НЕ выживает на 730д, цель «100% в год» на ней недостижима; live-параметры НЕ менялись; скрипт `research_wfa730_cnyrubf.ps1` | test+int+ktlint |
| 2026-09-21 | Диверсификация по акциям (`diversification-stocks`) | **WFA 365д folds=6 leverage x5 conf=0.60 stockGrid на GAZP/NVTK/PLZL/SBER (56k свечей с 2024-09-19): у 3 из 4 OOS убыточен (GAZP −20.2%/PF 0.59, NVTK −14.9%/0.78, SBER −8.3%/0.75), PLZL +19.0%/PF 1.26/Sharpe 0.92/70 сделок но P(noEdge)=0.21, robust=false** → диверсификация НЕ даёт устойчивый портфельный edge, PLZL в live не включается; скрипт `research_wfa_diversification.ps1` (TickerCsv, re-auth в catch) | test+int+ktlint |
| 2026-09-21 | Таймфрейм-диверсификация CNYRUBF (`timeframe-resample`) | **WFA 365д folds=6 conf=0.60 futuresGrid через `CandleResampler` (MINUTE_10 → HOUR_1/DAY_1, `/validate?timeframe=`): HOUR_1 +0.69%/PF 1.57/Sharpe 0.68/18 сделок/P(noEdge)=0.24/consistency 0.667 но robust=false; DAY_1 −0.14%/1 сделка (неинформативно)** → старшие таймфреймы edge НЕ дают, `bt.timeframe` остаётся MINUTE_10; скрипт `research_wfa_diversification.ps1 -Timeframe` | test+int+ktlint |
| 2026-09-22 | Диверсификация по фьючерсным перпетуалам (`perps-diversification`) | **WFA 730д×folds=8 conf=0.60 риск 30%/maxC 100 на USDRUBF/EURRUBF/GLDRUBF/IMOEXF (MOEX ISS interval=10: 46 140/43 319/50 293/50 385 свечей; funding_history 508 дат/тикер; новые spec в `InstrumentsConfig`/`application.yml`; SLVRUBF исключён — история с 2026-03): USDRUBF единственный положительный (IS PF 1.68/OOS +1.6%/PF 1.03/consistency 0.5/74 сделки но P(noEdge)=0.47, robust=false); EURRUBF/GLDRUBF/IMOEXF OOS убыточны (PF 0.65/0.05/0.12, P 0.93/1.0/1.0)** → перпетуалы edge НЕ дают, в live не включаются; скрипт `research_wfa_perps.ps1` | test+int+ktlint |
| 2026-09-21 | Max-hold (`max-hold-time-exit`) | **`bt.max-hold-bars` (env `BT_MAX_HOLD_BARS`) + query-оверрайд `maxHoldBars` на /backtest /validate /robustness (паттерн funding-veto; в `BacktestEngine` после SL/TP, приоритет ниже liq/SL/TP): WFA 365д folds=6 conf=0.60 сетка 30/46/92/184/368/736 баров — off (baseline) PF 1.63/P=0.15; 368 баров (8д) PF **2.15**/Sharpe **1.45**/P=**0.08**/consistency 0.667 но 27 сделок (robust=false); короткие 5ч–1д деградируют; комбо mh368+funding-veto 9/2 (risk30/maxC100) PF **2.27**/P=**0.065**; доводка порогов (2026-09-21,
  long 7–10 × short 0–1.5) — **плато PF 2.38–2.41/P=0.058/24 сделки** (устойчивый оптимум, не острый
  пик, но 0.05 не пробивается); conf 0.50 → выборка 26→208 сделок но OOS убыточен (baseline
  −124.8%/P=0.954, комбо −65.4%/P=0.864) — разреженность сигнала это природа стратегии, не дефект
  выборки; deployment-gate комбо (2026-09-22) = REJECTED (OOS PF 0.92/consistency 0.333/21 сделка,
  P(noEdge)=0.551) — плато P=0.058 артефакт подбора на полной истории** → max-hold УЛУЧШАЕТ OOS
  (сильнейший research-кандидат после базы), но edge статистически НЕ значим; `bt.max-hold-bars`
  остаётся 0 (off); скрипт `research_wfa_maxhold.ps1` | test+int+ktlint |
| 2026-09-22 | Праздничный календарь MOEX (`moex-holiday-calendar`, P1) | **`MoexHolidayCalendar`**: нерабочие дни = выходные + гос. праздники РФ (новогодние 1–8 янв, 23 фев, 8 мар, 1/9 мая, 12 июн, 4 ноя) + переносы/спец-дни из `funding.holidays` (env `FUNDING_HOLIDAYS`, yyyy-MM-dd); `FundingCosts.clearingDates/clearingsCrossed` принимают `isTradingDay`-предикат (дефолт = будни, обратная совместимость), календарь подключён в LIVE P&L futures (`FuturesTradingBotService`/`PnlCalculator.futures`) и backtest (`BacktestEngine`, бин `RiskBeansConfig.moexHolidayCalendar`); переносы производственного календаря задаются явно через `funding.holidays`; тесты `MoexHolidayCalendarTest` + 2 кейса в `FundingCostsTest` | test+int+ktlint |
| 2026-09-21 | Kimi K3 LLM-сигналы (`llm-signal-kimi`) | **WFA 180д MINUTE_10 folds=6 sample-every=240 aggressive/th=0.40 RouterAI `moonshotai/kimi-k3` (`LLM_DISABLE_REASONING=true`, `LLM_BUDGET_ENABLED=false`): OOS 33 сделки, −0.53%/PF 0.71/Sharpe −0.74/consistency 0.500/P(noEdge)=0.77/CI [−56.5;+26.5] — edge НЕТ**; 365д×folds=6 идёт >3 ч не влезает в async-таймаут; конвейер работает (892+ Agent 5 FINAL BUY/SELL/HOLD); баги research-прогона: дефолтный `LLM_MAX_TOKENS_PER_MINUTE=4000` душит WFA (отключать `LLM_BUDGET_ENABLED=false`); скрипт `research_wfa_kimi.ps1` | test+int+ktlint |
| 2026-09-23 | ORB-фильтр входа (`orb-entry-filter`) | **`EntryFilters.orbDirection`** (opening range = High/Low первых `orbWindowBars` баров дня, `time.toLocalDate()`; пробой вверх→LONG, вниз→SHORT) + query-оверрайды `orbEnabled/orbWindowBars/orbStrictBreakout/orbBlockOnUnknown` на `/backtest` `/validate` `/robustness` `/holdout` `/deployment-gate` (паттерн funding-veto/ML; в `LiveStrategyBacktestSignalGenerator` после session/pullback, до ML-фильтра; strict=true — внутри диапазона HOLD, strict=false — пропуск; `bt.orb-*`/env `BT_ORB_*`; тесты `EntryFiltersTest` 7 кейсов + 1 тест генератора); **WFA 365д folds=6 conf=0.60 risk30/maxC100: baseline +0.34%/PF 1.27/P=0.32; strict убыточен (w6 −9.35%/P=0.57, w12/w24 −51.4%/P=0.83); loose w12 +66.8%/PF 1.55/P=0.19; комбо w12loose+fv 9/1.5 +89.4%/PF 1.87/P=0.117 (плато, 23 сделки, robust=false); deployment-gate = REJECTED (OOS PF 0.78/consistency 0.5/P=0.665, holdout 5 сделок, MC p5=−0.32) — плато P=0.117 артефакт подбора на полной истории** → ORB edge НЕ даёт, `bt.orb-enabled` остаётся off; скрипт `research_wfa_orb.ps1` | test+int+ktlint |
| 2026-09-24 | Time-direction фильтр входа (`time-direction-filter`) | **`EntryFilters.blocksDirection(time, action)`** — блок LONG при `hour <= longBlockUntilHour`, SHORT при `hour in start..end` + query-оверрайды `timeDirectionEnabled/timeDirectionLongBlockUntilHour/timeDirectionShortBlockStartHour/timeDirectionShortBlockEndHour` на `/backtest` `/validate` `/robustness` `/holdout` `/deployment-gate` (паттерн funding-veto/ORB; в `LiveStrategyBacktestSignalGenerator` после session/pullback+ORB; `bt.time-direction-*`/env `BT_TIME_DIRECTION_*`; тесты `EntryFiltersTest` 2 кейса, всего 11); триггер — декомпозиция трейд-лога 730д IS (maxC=1, `includeTrades=true`): утренние LONG 7–11ч −319 ₽/15 сд. (TP 1/15), дневные SHORT 13–16ч −358 ₽/10 сд. (TP 0/10), вечер 18–23ч +688 ₽/22 сд.; **WFA 365д folds=6 conf=0.60 risk30/maxC100: baseline +33.8%/PF 1.27/P=0.322; long<=9 +100.1%/PF 1.97/P=0.088/consistency 0.667 но 24 сделки (robust=false); short 13–16 идемпотентен (SELL-входы в окне не встречались); deployment-gate = REJECTED (OOS PF 0.84/consistency 0.667/P=0.629, holdout 6 сделок, MC p5=−0.44/stressFailed 5) — плато P=0.088 артефакт подбора на полной истории** → время-фильтр edge не создаёт, `bt.time-direction-enabled` остаётся off; трейд-лог (`BacktestTradeRecord`/`includeTrades=true`) — рабочий инструмент декомпозиции; скрипт `research_wfa_timedirection.ps1` | test+int+ktlint |

Открытые пункты (вне скоупа / решение пользователя):
- live-сайзинг акций Kelly vs калибровочный x5/x6 — открытый вопрос (min приоритет).
- Funding-veto: **решено (2026-09-22, пользователь): live остаётся off** — research-пороги
  (long 9 ₽ / short 2 ₽) в LIVE НЕ переносятся; `trading.funding-veto-*` default off/2.0.
- **Kimi K3 WFA (2026-09-21, 180д folds=6)**: выполнено — OOS −0.53%/PF 0.71/P(noEdge)=0.77/33 сделки,
  edge НЕТ (см. research-раздел); 365д×folds=6 не влезает в async-таймаут 3 ч (~3000 вызовов × 5 агентов);
  месячный лимит RouterAI восстановился; для research обязательно `LLM_BUDGET_ENABLED=false`
  (дефолтный `LLM_MAX_TOKENS_PER_MINUTE=4000` душит WFA-вызовы) и `LLM_DISABLE_REASONING=true`.
- ML-фильтр направления: калибровка WFA (2026-09-20) показала отсутствие OOS-edge — решение «не
  включать» (`bt.ml-direction-enabled` остаётся off), см. research-раздел выше.
- Входные фильтры session/pullback: калибровка WFA (2026-09-20) показала отсутствие OOS-edge —
  решение «не включать» (`bt.session-filter-enabled`/`bt.pullback-filter-enabled` остаются off), см.
  research-раздел выше.
- **Цель «100% в год» (2026-09-20)**: WFA 730д показывает, что детерминированная стратегия CNYRUBF
  на полной истории (2024 regime) OOS убыточна (−80.4% при калибровочном риске 30%/maxC 100); при
  live-сайзинге (maxC 1/Kelly) потолок ~1%+/год. Устойчивый edge уровня 100%/год на текущей
  стратегии не найден ни одним из калиброванных фильтров (ML/funding/session/pullback) и LLM.
  Диверсификация по акциям (2026-09-21) тоже edge не дала (3 из 4 OOS убыточны; PLZL +19% но
  P=0.21); таймфреймы (2026-09-21) — HOUR_1 +0.69%/PF 1.57 но 18 сделок/P=0.24, DAY_1 не торгует;
  max-hold 368 баров/8д (2026-09-21) — PF 2.15/Sharpe 1.45/P=0.08, но 27 сделок (robust=false);
  комбо mh368+funding-veto 9/2 (2026-09-22) — формальный deployment-gate REJECTED (OOS PF 0.92,
  P(noEdge)=0.551, 21 сделка); перпетуалы (2026-09-22) — USDRUBF единственный положительный
  (OOS +1.6%/PF 1.03, но P=0.47/robust=false), EURRUBF/GLDRUBF/IMOEXF OOS убыточны (PF 0.65/0.05/0.12);
  сильнейший research-кандидат закрыт.
  Кандидаты вне скоупа: иные тикеры/таймфреймы; решение за пользователем.

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
