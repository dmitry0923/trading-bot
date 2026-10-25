# Trading Bot Project Memory

## Последнее (2026-10-25, P3 forward-пилот микроструктуры)
- **Найден и исправлен дефект данных пилота:** `MicrostructureRestPoller` опрашивал Alor 24/7,
  а вне сессии API отдаёт последний стакан → за 21–25.10 из 244 070 строк **104 309 (43%)**
  были выходными/ночными дублями одной книги. Гейт свежести `microstructure.rest-max-quote-age-ms`
  (default 30000, `0` = off) не пишет снимок, если `ms_timestamp` старше порога/отсутствует/из
  будущего; `ms_timestamp` читается как epoch-ms/epoch-s/ISO-8601. Метрика
  `microstructure.rest.bbo{reason=stale|future|missing_exchange_time}`, тесты 16.
- **Чистка:** удалено 104 309 строк вне 06:50–23:50 МСК и выходных; остались 21.10 — 25 841,
  22.10 — 55 382, 23.10 — 58 542 (+02.10 — 12 925). Монитор больше не считает HANG вне сессии
  (`marketOpen` в строке лога), `start_microstructure_pilot.ps1` подставляет локальные `DB_*`.
- **Статус:** пилот перезапущен на новом jar; критерии docs/23 §4 (≥20 торговых дней / ≥2 недели)
  считаются заново от 21.10 — вердикт по OBI-forward только после набора чистых данных.
  P1 (momentum+carry) — REJECTED/INCONCLUSIVE на gate, P4 (funding-window) — INCONCLUSIVE,
  P2 c1 (ставка ЦБ + RUONIA) — в торговом контуре, rg.ru блокирован подпиской.

## Архив (2026-10-20, план `18_10_2026.md`)
- **Пункты 2 и 6 плана закрыты и запушены** (HEAD `08dadfa`, ветка
  `feature/llm-veto-variant-b-monthly-tuning`).
- **Протокол `12−0` — выполнен, вердикт `INCONCLUSIVE`** (`p6-12-0-protocol`, предрегистрация
  docs/26 §11.8 закоммичена ДО прогона, `ccf5a73`). Замороженный `U8 12−0` (`L249/S0/rb21`)
  проверен тремя декомпозициями (L1 два непересекающихся под-периода, L2 8 leave-one-out,
  L3 параметрические контроли) — runner `scripts/research_12_0_protocol.ps1`, артефакты
  `artifacts/p6_12_0_protocol_{l1,l2,l3}.csv`, `_summary.json`, `_window_sensitivity.csv`.
  Итог: L1 ✅ (PF 1.648/1.732, cons 1.00), L2 ✅ (7/8, провал `−PLZL` PF 1.212), MDD ✅ (10.58 %),
  но **значимость ❌** (P(noEdge)=0.075, CI95 `[−4463; +26046]` содержит ноль) и **robust ❌**;
  полный U8 PF 2.030, Sharpe 1.57, **34 сделки**.
- **Главный вывод протокола — дефект метода, а не цифры.** `folds=8` на DAY_1 при `lookback=249`
  вырождается в «одну формацию портфеля на фолд»: сегмент 291 бар, warmup 249 → ~42 бара =
  **2 ребаланса = ровно 4 сделки/фолд**; порог `robust` (≥100 сделок) на `folds=8` **недостижим
  по построению**. PF 2.030 — **артефакт границ**: сдвиг `days` 3220→3200 (0.6 % истории)
  переворачивает результат (PF **1.430**, cons **0.50**, P **0.234**). L1 и FULL — разные режимы
  движка (48/42 vs 34 сделки), несопоставимы. Пересчёт на `folds=5` **не делался** — это
  донастройка способа проверки под результат, запрещённая §11.8/§6 PLAN-001. **`12−0` —
  режимный бет, а не подтверждённый edge.**
- **Harness-баг раннера (найден и исправлен):** URL собирался «база со своими `rb21&L249` +
  `&extra`» → **Spring берёт ПЕРВОЕ значение дублированного query** (класс бага combo730/§11.6) —
  override молча не работал (rb10/L187 были байт-идентичны базе). Теперь query собирается
  хеш-таблицей + guard на `folds=0`. **Правило: research-раннер обязан собирать query из
  единственного источника и проверять, что override реально меняет результат.**
- **Пункт 2** (`c5054e0`): LIVE `PnlCalculator.futures` — направленный funding
  (`sign(direction) × r × qty`), асимметрия `takeIf { it > 0 }` убрана; дефолт
  `bt.funding-direction-aware` в бэктесте не менялся (false).
- **Пункт 6** (`b8d3640` код, `d5e0b7b` docs, `08dadfa` глубокий прогон): направление (b)
  `12−1` momentum на DAY_1 — **REJECTED/INCONCLUSIVE**. Донакачаны DAY_1/HOUR_1 на макс.
  глубину MOEX ISS (`scripts/load_candles_deep.ps1`): акции 2336–2343 бара DAY_1
  (2018-01-03…2026-10-20, 8.8 года), перпетуалы 525–1140. Глубокий прогон
  (`scripts/research_oos70_portfolio_day1_deep.ps1`, `artifacts/p6_day1deep_day1deep.{csv,json}`,
  days=3220/folds=5/holdout=0.30): честный `12−1` **впервые исполнился на walk-forward**
  (U8, 5 фолдов, 70 OOS-сделок) → **skip-month ухудшает** результат (PF 1.445 vs 1.746
  у `12−0`; Sharpe 1.77 vs 2.30; P 0.1485 vs 0.042; dev-cons 0.40 vs 0.60) — Jegadeesh–Titman
  skip-премия на MOEX-акциях НЕ воспроизводится. Единственный формальный PASS — `12−0`
  (P=0.042, dev-cons 0.60, PF 1.746, Sharpe 2.30, 70 сделок), но `robust=false` и
  **CI95 средней сделки `[−1182; +18998]` содержит ноль** → движковый PASS ≠ значимый edge.
  U11 (1137 бар) при folds=5 → 0 фолдов (только folds=2), U13 (744 бара) → 0; carry снижает
  результат. Решение: `bt.portfolio.skip-bars` остаётся `0` (off). Детали: docs/26 §11.6–§11.7,
  `18_10_2026.md` (блок 2026-10-20).
- Инфра-фикс, найденный донакачкой: `MoexFundingHistoryLoader` обрывал пагинацию на первой
  странице с пустым SWAPRATE (ISS пуст до 2023-01-03) → пагинация переведена на число **сырых**
  строк ISS (`Page(records, rawRows)`, `countRawRows`, `JsonNode.size()`); регресс-тест
  `pages past a full page of blank swaprate rows`. После фикса: CNYRUBF/USDRUBF/EURRUBF 986 дат
  (с 2022-12-05), GLDRUBF 836, IMOEXF 746.
- Проверки глубокого прогона: `test` + `integrationTest` + `ktlintCheck` зелёные
  (`build/p6deep_full_checks_*.log`, BUILD SUCCESSFUL).
- **Следующие шаги (по решению пользователя):** класс momentum на U8 закрыт как не дающий
  подтверждённого edge; продолжение — только **новый** предрегистрированный протокол
  (например, `folds≤5` с явным обоснованием структурной вместимости фолда). P1 шаг 4 —
  deployment-gate портфеля (своего эндпоинта нет); P2 — rg.ru (нужна подписка); P3 — нужен
  свежий `ALOR_REFRESH_TOKEN`; P0 — действие брокера.

## Status
- GOAL DONE: `artifacts/baseline_730d_gates.json` получен 2026-10-08 12:03 (прогон 2401 с).
- Вердикт гейта: **REJECTED, liveAllowed=false** (8/8 проверок провалены).
  - backtest: Sharpe=0.244 MDD=0.004 PF=1.062 trades=58
  - walk_forward: consistency=0.375 oosTrades=56 oosSharpe=-1.619 oosPF=0.584
  - sample: OOS-сделок 56 < 100; consistency 0.375 < 0.600
  - edge: P(noEdge)=0.948 (нет значимости)
  - holdout: return=-0.1% trades=24 < 30, passable=false
  - robustness: mcRobust=false p5=-0.7% pLoss=40.7% stressFailed=5
- Параметры прогона (решение пользователя 2026-10-08): **live-совместимый риск** —
  `days=730&folds=8&holdoutFraction=0.30&riskPerTradePercent=1&futuresMaxContractsPerPosition=1&
  loadHistory=false&timeframe=MINUTE_10` (НЕ research-профиль 30/100 — запрещён PLAN-001 для финального вердикта).

## Как запускался сервер (воспроизводимо)
- Все conditional-правки (`trading.enabled`/`alor.ws.enabled`) ОТКАЧЕНЫ (`git checkout --`),
  рабочая копия чистая; bootJar пересобран (`build/bootjar_restore.log`).
- `java -Xmx4g -jar build/libs/trading-bot-2.0.0.jar --spring.mvc.async.request-timeout=10800000`
  с env: DB_HOST/PORT=5432, DB_NAME=trading_bot, DB_USER=trader, DB_PASS=backtest123,
  AUTH_USER=admin, AUTH_PASSWORD=admin123, JWT_SECRET='supersecretkey minlength32 bytes!!',
  TRADING_MODE=SIMULATION; stdout → `build/server_gate.log`.
- Стартуется 13 с, Tomcat 8080. Health 503 из-за RabbitMQ (нет контейнера) — на гейт не влияет.
  Login admin/admin123 работает. Alor WS постоянно реконнектится — не мешает.
- Runner: `scripts/research_gate_fixed3.ps1 -Name baseline_730d_gates -Url <полный URL> -OutDir artifacts`
  (login хардкодит admin/admin123 — сервер надо поднимать с этим паролем).
- Монитор: `scripts/gate_monitor.ps1` (каждые 300с пишет `artifacts/gate_monitor.log`:
  heartbeat age, RSS/CPU java, последний backtest-лог).

## FX-pairs (CNYRUBF/USDRUBF) — закрыто 2026-10-09
- Реализован движок relative-value на rolling OLS beta + z-score (docs/25), дефолт off:
  `PairsBacktestEngine`, `PairsBacktestService`, `PairsBacktestController` (`GET /api/v1/pairs/backtest`),
  config `bt.pairs.*`, юнит-тесты `PairsBacktestEngineTest` (5/5). `test`+`integrationTest`+`ktlintCheck` зелёные.
- Честный прогон (live-совместимый риск, издержки + funding, IS70/OOS30):
  `?legA=CNYRUBF&legB=USDRUBF&timeframe=MINUTE_10&days=730&lookbackBars=240&entryZ=2.0&exitZ=0.5&stopZ=4.0&oosFraction=0.30&useFundingHistory=true`.
- Вердикт: **REJECTED (oos PF 0.508)**, P(noEdge) OOS=0.9865, edge=false.
  - full: 576 сделок, −18.0%, Sharpe −2.64, PF 0.62, win 41.3%
  - IS (70%): 418 сделок, −13.7%, PF 0.65
  - OOS (30%): 158 сделок, −4.9%, PF 0.51, Sharpe −1.71
- spreadReturnCorrelation=0.86, meanBeta=0.95 → ноги почти дублируют друг друга.
- Причина: gross ≈ +30 ₽/сделку, издержки ≈ 58 ₽/сделку (комиссия 1.5×2 + slippage 1bp, qty ~6:1),
  поэтому переторговка (576 сделок) даёт чистый минус; costDrag OOS=82%.
- Артефакт: `build/pairs_result.json`.

## Cross-asset pairs + WFA — закрыто 2026-10-09
- Свип кросс-ассетных пар (единые дефолты, rounds: те же lookbackBars=240/entryZ=2.0/exitZ=0.5/stopZ=4.0):
  `IMOEXF/CNYRUBF` дал спорный CANDIDATE (oos PF 2.109), прочие REJECTED
  (GLDRUBF/CNYRUBF 0.775, USDRUBF/EURRUBF 0.872, IMOEXF/GLDRUBF 1.010). Артефакты `build/pairs_sweep/*.json`.
- Красные флаги CANDIDATE: знак-инверсия IS (−45.3%) ↔ OOS (+42.7%); corr≈−0.05, meanBeta=−0.35
  (ноги почти независимы — β-хедж подгоняет шум). → пользователь выбрал WFA-валидацию.
- Добавлен walk-forward для pairs: `PairsWalkForward` (фолды + агрегат OOS + consistency),
  `/api/v1/pairs/validate` (параметр `folds`, default 6), рефактор сервиса (общий `loadInputs`/`resolve`),
  юнит-тесты `PairsWalkForwardTest` (3/3) + `PairsBacktestEngineTest` (5/5). Live-путь не затронут.
- WFA-вердикт IMOEXF/CNYRUBF (**REJECTED** оба горизонта, consistency 0.5):
  - 365д×folds=6: agg PF 1.247, ret +9.5%, Sharpe 0.612, trades 101, P(noEdge)=0.291; фолды +/−: 3/6.
  - 730д×folds=8: agg PF **0.822**, ret −16.4%, Sharpe −0.454, trades 199, P(noEdge)=0.768; фолды +/−: 4/8
    (2024–нач.2025 минус, середина 2025–сер.2026 плюс, хвост минус).
- Вывод: одиночный OOS «PF 2.11» — артефакт одного удачного сегмента (режимность);
  relative-value пары устойчивого edge не дают. Артефакты `build/pairs_wfa_IMOEXF_CNYRUBF_365d_f6.json`,
  `..._730d_f8.json`; `test`+`integrationTest`+`ktlintCheck` зелёные (`build/pairs_wfa_full.log`).

## Cross-sectional momentum + carry (портфель фьючерсов) — закрыто 2026-10-09
- Движок: новый пакет `backtest/portfolio/` — `PortfolioMomentumEngine` (кросс-секционный
  z-score momentum = `ln(C[t-1])−ln(C[t-1-L])` + carry-tilt `λ·z(−avgFunding/notional)`;
  top `longCount` в лонг / bottom `shortCount` в шорт, equal-notional; сигнал на close `i-1`,
  fill на open `i`; издержки/фандинг через `FundingCosts`). `PortfolioWalkForward`
  (фолды, агрегат OOS, consistency, `robust = sharpe>0.5 & PF>1.1 & cons≥0.6 & trades≥100`).
  API `/api/v1/portfolio/momentum[/validate]`, конфиг `bt.portfolio.*` (default off,
  universe CNYRUBF/USDRUBF/EURRUBF/GLDRUBF/IMOEXF). Live-путь/LIVE-guard не затронуты.
- Тесты: `PortfolioMomentumEngineTest` (5) + `PortfolioWalkForwardTest` (2) — 7/7;
  `test`+`integrationTest`+`ktlintCheck` зелёные (`build/portfolio_build.log`, BUILD SUCCESSFUL 18m57s).
- WFA 730д folds=8 (42362 бара; при rebal420 — 144 сделки):
  - momentum-only (λ=0): PF 1.109, ret +8.48%, Sharpe 0.554, cons 0.625, robust=True, P(noEdge)=0.315.
  - **momentum+carry (λ=1): PF 1.300, ret +21.46%, Sharpe 1.312, Sortino 2.05, MDD 8.6%,
    cons 0.75, robust=True, P(noEdge)=0.124** → carry удваивает Sharpe и срезает левый хвост
    (f2 −5.2%→−2.8%, f5 −5.9%→+0.3%, f7 −8.4%→−3.7%; 6/8 фолдов в плюс).
  - Вердикт обоих: REJECTED (у carry PF 1.2998 чуть ниже порога 1.3; независимо P(noEdge)=0.124>0.05).
- Sweep устойчивости (плато, не пик):
  - `carryWeight` 0.5→2.0: PF 1.18–1.30, все robust=True, cons 0.625–0.75, P 0.12–0.23 — широкое плато.
  - `rebalanceBars` 105→840 (λ=1): 1.059 (rb105, not robust) → 1.183 (150) → **1.275 (210, Sharpe 1.543,
    ret +27.0%, 288 сделок, P(noEdge)=0.0725)** → 1.215 (300) → 1.300 (420, Sharpe 1.31) → 1.069 (840).
  - `lookbackBars` 840/1680/3360: 1.017 / лучший (default 1680) / 0.995.
- Победитель полного WFA: `cw=1.0, rebalanceBars=210` — PF 1.275, Sharpe 1.543, ret +27.0%,
  MDD 9.8%, 288 сделок, robust=True, P(noEdge)=0.0725.
- Расширение числа ног (short-heavy, cw=1.0, rb210) вывело кластер за порог значимости:
  `L1S3` PF 1.274/Sharpe 2.159/**P=0.014**/CI95 **`[+40;+823]`** (576 сделок); `L1S4` P=0.0145
  CI95 `[+29;+574]`; `L0S3` PF 1.313/P=0.023/CI95 `[+14;+1240]`; `L1S3 rb420` PF 1.376/P=0.019.
  Воспроизводимость точная; `carryWeight` 1.0/1.5 и `rebalanceBars` 210/420 — плато. Первый
  в проекте результат с `P(noEdge)<0.05` И CI95 без нуля.
- **РЕШАЮЩИЙ ТЕСТ — честный IS70/OOS30 (`/momentum?oosFraction=0.30`) — REJECTED:**
  OOS PF всех кандидатов 1.02–1.17, P 0.23–0.49 (L1S3 OOS PF 1.105/P 0.303; L0S3 лучший
  OOS PF 1.172/P 0.233); устойчиво при границах 0.25/0.30/0.35. По фолдам L1S3 прибыль
  сосредоточена в средних 2025 (f1,f3,f5,f6), хвостовые f4/f7 отрицательны, а сильнейший f3
  лежит в IS → **selection/режимный артефакт** (ср. combo730/ORB/time-direction).
- **Расширение юниверса до 13 инструментов** (5 фьючерсов + 8 акций GAZP/LKOH/MGNT/NVTK/PLZL/ROSN/SBER/TATN,
  у всех полная история MINUTE_10 ~57k свечей; RI/Si/VTBR — 2.5 мес, исключены): экономика
  совместима (`price·lotSize`=нотионал; funding акций=0). **U13 momentum-only (cw=0)** даёт
  стабильно положительный holdout (OOS PF 1.04–1.33 на границах 0.25/0.30/0.35) и WFA PF
  1.17–1.22/robust=True у 13/16 конфигов — **единственный шаг, качественно улучшивший holdout**
  (futures-only давал OOS PF ~1.0). Лучший баланс — `L2S4 rb300` (WFA PF 1.17/cons 0.62/robust,
  OOS PF 1.29/ret +15%/Sharpe 1.26/258 сделок); max OOS PF 1.33 (L2S4 rb420) — но при слабом WFA
  (cons 0.38). Mixed carry (cw1) по акциям искажает сигнал (funding=0) → слабее cw0.
- **Волатильностное масштабирование ног (risk-parity) реализовано**: `Params.volScalingEnabled`/
  `volLookbackBars` в `PortfolioMomentumEngine` (+`bt.portfolio.vol-*`/env `BT_PORTFOLIO_VOL_*`,
  query `volScalingEnabled/volLookbackBars` на обоих эндпоинтах, дефолт off). Нотионал ноги ∝ 1/σ
  за `volLookbackBars`, суммарный нотионал неизменен, недоступная σ → откат к равным долям
  (fail-safe, 2 регресс-теста). **Устойчиво улучшает holdout**: OOS Sharpe 0.67→1.42, PF
  1.13–1.16→1.25–1.28 у всех 6 пар «равно→scaled»; plateau vs105–210/420. Лучший —
  `L2S4 rb300 vs210`: WFA PF 1.17/cons 0.75/robust, OOS PF **1.28**/Sharpe 1.40/ret +13%/258 сдел.;
  на сплите 0.25 OOS PF **1.336/P 0.0535** (за полшага от CANDIDATE), на 0.30 → 1.275/P 0.076
  (REJECTED), 0.35 → 1.173/P 0.148 (P растёт с расширением holdout — сигнал сильнее в 2025).
- **Таргетирование волатильности книги (portfolio risk-budget) реализовано и измерено**:
  `Params.volTargetEnabled`/`volTargetAnnualized`/`volTargetBarsPerYear` (+`bt.portfolio.vol-target-*`,
  env `BT_PORTFOLIO_VOL_TARGET_*`, query-параметры). Нотионал книги × `min(1, target/σ_ann)`
  (только вниз; нет истории/вырожденная σ → 1.0, fail-safe). OOS PF 1.28→1.30, Sharpe →1.44–1.47,
  MDD 0.10→0.08. Но **остаётся у порогов**: L2S4 rb300 vs210 vt30 → 0.30: PF 1.305/**P 0.056**
  (INCONCLUSIVE); L2S3 rb210 vs420 vt15 → 0.30: PF **1.288**/P 0.041 (значим, но PF<1.3 → REJECTED);
  0.25/0.35 уводят обе метрики за порог. Фолды L2S4 vt30 — 5/8.
- **Вывод: сигнал broad-universe momentum + два риск-оверлея (inverse-vol + vol-target) реален,
  но СТРОГО ГРАНИЧНЫЙ** — критерии CANDIDATE (PF≥1.3 И P≤0.05) берутся вразнобой, но не вместе ни
  на одной границе (0.25/0.30/0.35). `bt.portfolio.*` остаётся off, в LIVE не идёт. Артефакты:
  `build/portfolio_{momentum_730d_f8,carry_730d_f8}.json`, `build/portfolio_sweep/*.json`,
  `build/portfolio_volscaling_oos.csv`, `build/portfolio_voltarget_oos.csv`; скрипты
  `scripts/research_portfolio_momentum.ps1`/`_sweep.ps1`/`_volscaling.ps1`/`_voltarget.ps1`; дизайн —
  `docs/26-cross-sectional-momentum-carry.md` (§8 inverse-vol, §8a vol-target, §9 вывод).
- **Смена класса — кросс-секционный краткосрочный разворот (mean-reversion), REJECTED (2026-10-05)**:
  `Params.reversalEnabled` (query `reversalEnabled`, env `BT_PORTFOLIO_REVERSAL_ENABLED`, дефолт
  false) инвертирует momentum-z, carry-тилт независим; `momentumSign` + 1 регресс-тест. U13
  reversal-only 730д: **все 10 конфигов OOS PF 0.75–0.97, отрицательный return, robust=false,
  P(noEdge) 0.69–1.0** на 660–3488 сделках (не «мало данных»). Короткое окно lb29 — наименее плохое
  (PF 0.97), удлинение монотонно хуже (lb232 → 0.76). Carry(1/2) и vol-scaling/vol-target не
  поднимают PF выше ~0.96. Издержки оборота съедают слабый сигнал; направленный прайс-сигнал в
  юниверсе не работает ни в одну сторону. Флаг остаётся off. Артефакты
  `build/portfolio_reversal_oos.csv`, `build/portfolio_reversal2_oos.csv`; скрипты
  `scripts/research_portfolio_reversal.ps1`, `scripts/research_portfolio_reversal2.ps1`; docs/26 §8b.
- **Carry-центричная стратегия (momentum off), INCONCLUSIVE/не проход (2026-10-09)**:
  `Params.momentumWeight` (query `momentumWeight`, env `BT_PORTFOLIO_MOMENTUM_WEIGHT`, дефолт 1.0;
  0 = carry-only) + рефактор `select()` (needMom/needCarry; оба 0 → null) + 1 регресс-тест; заодно
  исправлен баг: `/momentum/validate` не пробрасывал `volTarget*`. Carry-юниверс — только 5
  инструментов с funding_history (CNYRUBF/USDRUBF/EURRUBF/GLDRUBF/IMOEXF). U5 carry-only 730д:
  сигнал **шорт-тяжёлый** (лонговая нога carry убыточна, L3S0 PF 0.71–0.91). Лучший
  **L0S3 rb630 cl5: OOS PF 1.29/P 0.19/66 сделок**, WFA PF 1.36/cons 0.62/robust=true/P≈0.05.
  Контроли: тот же short-каркас по momentum → 0.96/P 0.57; инверсия знака carry → 1.05 →
  carry-ранжирование реально (не short-beta) и экономически верно (шорт плательщиков funding).
  Бар не достигнут (PF 1.29 < 1.30, P 0.19 ≫ 0.05) — тот же граничный класс, что momentum, но
  не REJECTED. `momentumWeight` дефолт 1.0. Артефакты `build/portfolio_carry_oos.csv`,
  `build/portfolio_carry2_oos.csv`; скрипты `scripts/research_portfolio_carry.ps1`/`_carry2.ps1`;
  docs/26 §8c. Билд `build/portfolio_build5.log` (BUILD SUCCESSFUL 18m39s, test+int+ktlint).

## Next steps
- Закрыты: baseline 730д (oosPF 0.584), FX-pairs (0.51), cross-asset pairs + WFA (REJECTED),
  cross-sectional momentum+carry (WFA-значимость L1S3 P=0.014/CI без нуля → IS70/OOS30 REJECTED),
  **broad-universe momentum + inverse-vol + vol-target (L2S4 rb300 vt30: OOS PF 1.30/P 0.056;
  L2S3 rb210 vt15: PF 1.29/P 0.041 — оба за полшага, критерии не берутся вместе)**,
  **кросс-секционный reversal U13 (OOS PF 0.75–0.97 на 660–3488 сделках → REJECTED)**,
  **carry-центричная стратегия (momentum off; OOS PF 1.29/P 0.19, robust WFA — INCONCLUSIVE/не
  проход, тот же граничный класс)**.
- **Принципиальные рычаги внутри класса исчерпаны** (universe ✓, inverse-vol ✓, vol-target ✓,
  reversal ✓, carry-центричность ✓). Дальнейшее — свежие/независимые данные (carry-юниверс
  ограничен 5 тикерами с funding_history) либо новый инструментарий (order book/funding-арбитраж);
  новый грид направленного сигнала на тех же 730д неинформативен (selection). Решение по PLAN-001
  — за пользователем.
- **После 2026-10-20** добавились: донакачка DAY_1/HOUR_1 на 8.8 года (акции) + глубокий `12−1`
  (`12−1` REJECTED как skip-гипотеза, `12−0` PASS с CI95, содержащим ноль — см. блок выше).

## Rules
- At start of each session, ALWAYS read this file (memory-trading-bot.md) to understand context.
- Do not change core logic; conditional-props эксперимент провалился (лавина зависимостей) — не повторять.
- Тяжёлые прогоны — только в фоне (Start-Process) + монитор каждые 5 минут.
