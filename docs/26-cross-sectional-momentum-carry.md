# Cross-sectional momentum + carry (портфель фьючерсов) — research

Статус: **research-инструмент** (default off). Live-путь, LIVE-guard и
`max-contracts-per-position=1` не затрагиваются. Вердикт: **edge НЕ подтверждён**
(лучший кандидат PF 1.275 / Sharpe 1.54 / P(noEdge) 0.0725), но это ближайший к
значимости результат проекта.

## 1. Мотивация

PLAN-001 (≥100%/год на CNYRUBF) не достигнут: направленная детерминированная
стратегия на 730д OOS убыточна (PF 0.92), все входные фильтры и LLM-путь
отбракованы, FX-pairs и cross-asset pairs (docs/25) — REJECTED. Не проверенным
оставался класс **cross-sectional (по ранжированию) стратегий**: вместо «угадать
направление одного инструмента» держим длинные лучшие и короткие худшие ноги
внутри одного рынка. Это ортогонально и к направленному пути, и к relative-value
паре (docs/25): там торгуется расхождение двух инструментов, здесь — ранжирование
всего юниверса.

## 2. Дизайн (pre-registered, default off)

Юниверс: `CNYRUBF, USDRUBF, EURRUBF, GLDRUBF, IMOEXF` (все фьючерсы; полная 730д
история в `candles`, MINUTE_10).

- **Сигнал на ребалансе** (каждые `rebalanceBars` баров, первый — на `lookbackBars+1`):
  - momentum = `ln(close[i−1]) − ln(close[i−1 − lookbackBars])`;
  - carry-привлекательность «быть длинным» = `−avgFunding / (close[i−1]·lotSize)` по
    последним `carryLookbackDays` клирингам (`funding_history`; fallback — spec-ставка);
  - `score = z(mom) + λ·z(carry)` (оба z — кросс-секционные по валидным инструментам;
    при λ=0 carry не требуется). Вырожденная дисперсия → сечение пропускается.
- **Отбор:** топ `longCount` по score → лонг, низ `shortCount` → шорт; равные
  нотионалы (`target = capitalSlice·initialCapital/(longCount+shortCount)`), минимум
  1 контракт. Full-rebalance: старые ноги закрываются, новые открываются.
- **Исполнение:** сигнал на закрытии `i−1`, вход на открытии `i` (lookahead закрыт).
- **Издержки:** комиссия `qty × commissionPerContractSide × 2`, slippage bps на
  сторону (по slipped-ценам), funding через `FundingCosts.clearingDates` (+
  праздничный календарь MOEX). Equity переоценивается на каждом баре.
- Дефолты: `initialCapital=1_000_000` (крупный размер → малое округление контрактов),
  `lookbackBars=1680` (~20 торг. дней), `rebalanceBars=420` (~5 дней), `longCount=1`,
  `shortCount=1`, `capitalSlice=1.0`, `carryWeight=0.0`, `carryLookbackDays=5`,
  `minUniverse=3`. Guard: `n < lookbackBars+rebalanceBars+2 || u < minUniverse` → пустой результат.

## 3. Реализация

- Пакет `src/main/kotlin/com/trading/bot/backtest/portfolio/`:
  - `PortfolioMomentumEngine` — чистый движок (без Spring/БД), `run/select/zscore/carryYield`.
  - `PortfolioWalkForward` — фолды, агрегат OOS (конкатенация P&L фолдов от
    `initialCapital`), `consistency` (доля фолдов ret>0),
    `robust = sharpe>0.5 & PF>1.1 & consistency≥0.6 & trades≥100`; verdict:
    `trades<30 → INCONCLUSIVE`, `consistency<0.6 → REJECTED`, `PF<1.3 → REJECTED`,
    `P(noEdge)>0.05 → INCONCLUSIVE`, иначе `CANDIDATE`.
  - `PortfolioMomentumConfig` (`bt.portfolio.*`), `PortfolioMomentumService` (загрузка
    баров/фандинга, inner-join по времени, `run`/`validate`),
    `PortfolioMomentumController` (`GET /api/v1/portfolio/momentum[/validate]`);
    query-параметры перекрывают `bt.portfolio.*` (паттерн funding-veto/ML).
- Тесты: `PortfolioMomentumEngineTest` (5) + `PortfolioWalkForwardTest` (2) — 7/7.
- Полный прогон зелёный: `test`+`integrationTest`+`ktlintCheck`, BUILD SUCCESSFUL in 18m 57s
  (`build/portfolio_build.log`).

## 4. Результаты WFA (730д, folds=8, ризл. 42362 бара)

Reanaliz420, 144 сделки:

| Конфиг | OOS Ret | OOS PF | Sharpe | Sortino | MDD | Сделки | Cons | robust | P(noEdge) |
|---|---|---|---|---|---|---|---|---|---|
| momentum-only (λ=0) | +8.48% | 1.109 | 0.554 | 0.79 | 9.96% | 144 | 0.625 | True | 0.315 |
| **momentum+carry (λ=1)** | **+21.46%** | **1.300** | **1.312** | 2.05 | 8.56% | 144 | 0.75 | True | 0.124 |

Вердикт обоих — **REJECTED** (у carry PF 1.2998 чуть ниже порога 1.3; независимо
P(noEdge)=0.124 > 0.05). Carry удваивает Sharpe и срезает левый хвост по-фолдово:
f2 −5.2%→−2.8%, f5 −5.9%→+0.3%, f7 −8.4%→−3.7%; 6/8 фолдов в плюс (у momentum-only 5/8).

## 5. Проверка плато (не пика)

Sweep (`scripts/research_portfolio_momentum_sweep.ps1`):

- `carryWeight` 0.5→2.0: PF 1.18–1.30, все robust=True, cons 0.625–0.75, P 0.12–0.23 —
  **широкое плато**, carry-тилт устойчив, не подгонка.
- `rebalanceBars` 105→840 (λ=1): 1.059 (105, not robust) → 1.183 (150) → **1.275 (210)** →
  1.215 (300) → 1.300 (420) → 1.069 (840) — плато 150–420, оптимум по Sharpe/P/сделкам на 210.
- `lookbackBars` 840/1680/3360: 1.017 / лучший (default) / 0.995.

**Победитель полного WFA: `carryWeight=1.0, rebalanceBars=210`** — PF 1.275, Sharpe 1.543,
ret **+27.0%/730д** (~+13%/год), MDD 9.8%, **288 сделок**, robust=True,
**P(noEdge)=0.0725**, CI95 `[−279;+2185]` (содержит 0).

Расширение числа ног (та же 730д, folds=8) вывело часть конфигов за порог значимости —
**кластер short-heavy**:

| Конфиг (cw=1.0, rb210) | PF | Ret | Sharpe | MDD | Сделки | Cons | P(noEdge) | CI95 средней сделки |
|---|---|---|---|---|---|---|---|---|
| L1S3 (1 long, 3 short) | 1.274 | +24.6% | 2.159 | 5.4% | 576 | 0.75 | **0.014** | **[+40; +823]** |
| L1S4 (1 long, 4 short) | 1.245 | +21.5% | 2.192 | 5.2% | 720 | 0.875 | **0.0145** | **[+29; +574]** |
| L0S3 (0 long, 3 short) | 1.313 | +27.7% | 2.100 | 6.3% | 432 | 0.75 | **0.023** | **[+14; +1240]** |
| L1S3 rb420 | 1.376 | +24.4% | 2.116 | 5.3% | 288 | 0.75 | 0.019 | [+64; +1643] |
| L1S2 rb420 | 1.375 | +23.9% | 1.852 | 4.5% | 216 | 0.75 | 0.035 | [−82; +2382] |

Воспроизводимость точная (`L1S3 rb210` дважды бит-в-бит), `carryWeight` 1.0/1.5 и
`rebalanceBars` 210/420 — плато. Формально это первый в проекте результат с
`P(noEdge)<0.05` **и** CI95 без нуля.

## 6. Честный IS70/OOS30 (holdout) — решающий тест

Все конфиги выше отобраны на **полной** 730д истории, т.е. подозрение на selection.
`/api/v1/portfolio/momentum?days=730&oosFraction=0.30` считает непрерывный holdout —
последние 30% (≈219 дней), IS — первые 70%; вердикт только по OOS:

| Конфиг | IS PF | OOS PF | OOS Ret | OOS Sharpe | OOS сделки | OOS P(noEdge) | Вердикт |
|---|---|---|---|---|---|---|---|
| baseline momentum-only | 1.301 | 1.026 | +0.9% | 0.136 | 62 | 0.477 | REJECTED |
| L1S1 rb210 cw1 | 1.242 | 1.066 | +2.9% | 0.349 | 122 | 0.398 | REJECTED |
| **L1S3 rb210 cw1** | 1.232 | 1.105 | +3.7% | 0.623 | 244 | 0.303 | REJECTED |
| L1S3 rb420 cw1 | 1.489 | 1.076 | +2.4% | 0.430 | 124 | 0.385 | REJECTED |
| L0S3 rb210 cw1 | 1.327 | 1.172 | +6.0% | 0.731 | 183 | 0.233 | REJECTED |
| L1S4 rb210 cw1 | 1.216 | 1.111 | +3.8% | 0.666 | 305 | 0.253 | REJECTED |
| L1S2 rb420 cw1 | 1.387 | 1.020 | +0.6% | 0.125 | 93 | 0.474 | REJECTED |

Устойчиво к границе: при `oosFraction` 0.25/0.30/0.35 OOS PF всех кандидатов
1.00–1.17 (P 0.23–0.49) — **REJECTED на всех границах**. Причина видна по фолдам:
`L1S3 rb210` положителен в f0,f1,f3,f5,f6 (сильные 2025), но f4 (−0.7%) и f7 (−1.3%)
отрицательны, а сильнейший f3 (2025-07..10) лежит в IS. Т.е. WFA-«значимость» держится
на средних фолдах 2025, а хвостовой год — плоско.

## 7. Расширение юниверса до 13 инструментов (momentum-only)

Инвентаризация `candles` показала полную историю MINUTE_10 (~57k свечей) у **5 фьючерсов
(CNYRUBF/EURRUBF/GLDRUBF/IMOEXF/USDRUBF) + 8 акций (GAZP/LKOH/MGNT/NVTK/PLZL/ROSN/SBER/TATN)**.
RI/Si/VTBR — только 2.5 мес (исключены). Экономика совместима: движок считает
`dir·qty·lotSize·Δprice`, т.е. `price·lotSize` = нотионал (для акций `lotSize` = акций в лоте);
у акций funding=0. Inner-join по общим таймстемпам не теряет бары (42038 vs 42362 futures-only).
**Carry по акциям не определён** (funding 0), поэтому mixed carry-z искажает сигнал —
U13 cw1 слабее U13 cw0 и тоже REJECTED (OOS PF 0.89–1.08).

**U13 momentum-only (cw=0), 730д folds=8:** WFA PF 1.00–1.22, cons до 0.75, robust=True у
13/16 конфигов; **holdout положителен стабильно** (в отличие от futures-only):

| Конфиг | WFA PF | WFA cons | WFA P | OOS PF | OOS Ret | OOS Sharpe | OOS сделки |
|---|---|---|---|---|---|---|---|
| L2S4 rb300 | 1.17 | 0.62 | 0.08 | **1.29** | +15% | 1.26 | 258 |
| L2S4 rb210 | 1.22 | 0.62 | 0.02 | 1.22 | +13% | 1.11 | 366 |
| L2S3 rb210 | 1.20 | 0.62 | 0.03 | 1.18 | +11% | 0.97 | 305 |
| L2S2 rb210 | 1.21 | 0.75 | 0.06 | 1.12 | +8% | 0.67 | 244 |
| L2S4 rb420 | 1.10 | 0.38 | 0.23 | 1.33 | +13% | 1.06 | 186 |

Границы 0.25/0.30/0.35 (L2S3): OOS PF 1.13–1.19, ret +9–11%, Sharpe 0.74–0.97.

## 8. Волатильностное масштабирование ног (risk-parity)

Реализовано в движке: `Params.volScalingEnabled`/`volLookbackBars` (+`bt.portfolio.vol-*`,
env `BT_PORTFOLIO_VOL_*`, query `volScalingEnabled/volLookbackBars` на обоих эндпоинтах,
дефолт off). При включении нотионал ноги ∝ 1/σ(лог-доходностей за `volLookbackBars` баров);
суммарный нотионал книги неизменен; недоступная σ любой ноги → откат к равным долям
(fail-safe, регресс-тесты). Экономика не меняется — тот же `price·lotSize` = нотионал.

**U13 momentum-only + vol-scaling, 730д folds=8 и holdout 0.30:**

| Конфиг | WFA PF | cons | rob | OOS PF | OOS Ret | OOS Sharpe |
|---|---|---|---|---|---|---|
| L2S4 rb300 vs105 | 1.19 | 0.75 | True | **1.28** | +13% | 1.42 |
| L2S4 rb300 vs210 | 1.17 | 0.75 | True | 1.28 | +13% | 1.40 |
| L2S3 rb210 vs420 | 1.21 | 0.62 | True | 1.27 | +13% | 1.42 |
| L2S3 rb210 vs210 | 1.19 | 0.62 | True | 1.25 | +12% | 1.33 |
| L2S4 rb300 eq (база) | 1.16 | 0.62 | True | 1.16 | +10% | 0.82 |

Vol-scaling **устойчиво улучшает holdout** (OOS Sharpe 0.67→1.42, PF 1.13–1.16→1.25–1.28 у
всех 6 пар «равно→scaled»; улучшение структурное, не острый пик: plateau vs105–210/420,
off при vs840→1.23). Граничная устойчивость лучшего (L2S4 rb300 vs210):
0.25 → **OOS PF 1.336/P 0.0535 (INCONCLUSIVE — за полшага от CANDIDATE)**; 0.30 → 1.275/P 0.076
(REJECTED); 0.35 → 1.173/P 0.148. Фолды 6/8 (f2 −7.6%, f7 −4.4%).

## 8a. Таргетирование волатильности книги (portfolio risk-budget)

`Params.volTargetEnabled`/`volTargetAnnualized`/`volTargetBarsPerYear` (+`bt.portfolio.vol-target-*`,
env `BT_PORTFOLIO_VOL_TARGET_*`, query `volTargetEnabled/volTargetAnnualized/volTargetBarsPerYear`).
При включении суммарный нотионал книги множится на `min(1, target/σ_ann)`, где σ_ann — реализованная
волатильность книги (по текущим весам ног) за `volLookbackBars`, аннуализированная `sqrt(barsPerYear)`.
Скалирование только вниз (никогда не леверит выше базы); нет истории/вырожденная σ → 1.0 (fail-safe).

**U13 momentum + inverse-vol + vol-target, holdout 0.30:**

| Конфиг | WFA PF | cons | OOS PF | OOS Ret | OOS Sharpe | OOS MDD |
|---|---|---|---|---|---|---|
| L2S4 rb300 vs210 vt30 | 1.14 | 0.62 | **1.30** | +13% | 1.44 | 0.09 |
| L2S4 rb300 vs105 vt15 | 1.15 | 0.62 | 1.30 | +12% | 1.46 | 0.09 |
| L2S3 rb210 vs420 vt15 | 1.20 | 0.62 | 1.29 | +13% | **1.47** | **0.08** |
| L2S4 rb300 vs210 (без vt) | 1.17 | 0.75 | 1.28 | +13% | 1.40 | 0.10 |

Vol-target поднимает OOS PF 1.28→1.30 и снижает MDD (0.10→0.08), но **остаётся у самих порогов**:
`L2S4 rb300 vs210 vt30` на 0.30 → OOS PF 1.305/P 0.056 (INCONCLUSIVE); `L2S3 rb210 vs420 vt15`
на 0.30 → PF 1.288/**P 0.041** (значим, но PF чуть не дотянул → REJECTED); границы 0.25/0.35
уводят за порог обе метрики. Фолды L2S4 vt30 — 5/8 (f0 −5.2%, f5 −4.1%).

## 8b. Смена класса: кросс-секционный краткосрочный разворот (mean-reversion)

По решению пользователя (после строго граничного momentum) проверен **обратный знак** сигнала:
`Params.reversalEnabled` (query `reversalEnabled`, env `BT_PORTFOLIO_REVERSAL_ENABLED`, дефолт
`false`) инвертирует momentum- z-скор, оставляя carry-тилт независимым; тот же движок/harness.
Реализация — флаг в `Params` (+`momentumSign`), плюмбинг в service/controller/yml, регресс-тест
`reversal book is long the loser and short the winner`.

**U13, reversal-only (carryWeight=0), 730д folds=8 + holdout 0.30** (ось разворота — короткие
окна; ~58 баров/день, lb29≈½д, lb58≈1д, lb116≈2д):

| Конфиг | WFA PF | cons | OOS PF | OOS Ret | OOS Sharpe | OOS сделок | P(noEdge) |
|---|---|---|---|---|---|---|---|
| lb29 rb29 L3S3 | 0.94 | 0.25 | **0.97** | −0.08 | −0.29 | 2616 | 0.69 |
| lb29 rb29 L4S4 | 0.92 | 0.12 | 0.94 | −0.19 | −0.83 | 3488 | 1.00 |
| lb58 rb29 L3S3 | 0.88 | 0.12 | 0.87 | −0.39 | −1.86 | 2616 | 0.99 |
| lb58 rb58 L3S3 | 0.90 | 0.12 | 0.81 | −0.30 | −2.07 | 1314 | 0.99 |
| lb116 rb58 L3S3 | 0.80 | 0.00 | 0.75 | −0.58 | −2.71 | 1314 | 1.00 |
| lb116 rb116 L3S3 | 0.80 | 0.25 | 0.79 | −0.38 | −1.76 | 660 | 0.98 |
| lb232 rb58 L3S3 | 0.80 | 0.12 | 0.76 | −0.65 | −2.09 | 1314 | 1.00 |
| L4S4 варианты (lb29/58/116) | 0.84–0.93 | 0–0.25 | 0.77–0.94 | −0.19…−0.64 | <0 | 1752–3488 | 0.97–1.0 |

Все 10 конфигов — **OOS PF 0.75–0.97, отрицательный return, `robust=false`, P(noEdge) 0.69–1.0**.
Короткое окно (lb29) — наименее плохое (PF 0.97), но всё равно < 1; удлинение окна монотонно
ухудшает (lb232 → 0.76). Ни carry-тилт, ни vol-overlays не спасают (`rev29 carry1` PF 0.95,
`carry1 vs` 0.96, `vs` 0.95 — возврат отрицательный):

| Комбо (lb29 rb29 L3S3) | OOS PF | OOS Ret | OOS Sharpe |
|---|---|---|---|
| reversal-only | 0.97 | −0.08 | −0.29 |
| + carryWeight=1 | 0.95 | −0.09 | −0.61 |
| + carryWeight=2 | 0.92 | −0.12 | −1.06 |
| + vol-scaling | 0.95 | −0.13 | −0.73 |
| + carry1 + vol-scaling | 0.96 | −0.06 | −0.59 |

**Выборка решающая:** 660–3488 OOS-сделок — это не «мало данных», а надёжный отрицательный
результат. Краткосрочный кросс-секционный разворот на U13 (MINUTE_10) **издержками съедается
целиком**: сигнал есть (момент слегка разворачивается на lb29), но оборот книги на порядок
превышает доступный edge. **Вердикт: REJECTED.** `bt.portfolio.reversal-enabled` остаётся off
(research-флаг сохранён). Стабильный источник в этом юниверсе — не направленный прайс-сигнал
(ни momentum, ни reversal), а **carry** (§7).

## 8c. Carry-центричная стратегия (momentum отключён)

По решению пользователя проверен **чистый carry** как самостоятельный источник (momentum-тилт
выключен): `Params.momentumWeight` (query `momentumWeight`, env `BT_PORTFOLIO_MOMENTUM_WEIGHT`,
дефолт `1.0`; `0.0` → carried-only). `select()` отрефакторен: `needMom`/`needCarry` считаются по
весам, при обоих нулях → `null`, momentum-lookback при `momentumWeight=0` не требуется; регресс-тест
`carry-only book longs the payer of funding and shorts the receiver`. Попутно исправлен
**пред-сущий баг**: `/momentum/validate` принимал `volTarget*`, но не пробрасывал их в сервис
(аналог llm-veto) — теперь пробрасываются.

**Юниверс carry принудительно U5** — из 13 инструментов `funding_history` есть только у 5:
`CNYRUBF/USDRUBF/EURRUBF/GLDRUBF/IMOEXF` (§7). `carryYield = −avgFunding/notional` (выше = лонг
получает funding).

**Грид 1 (carry-only, lb1, rb420, cl5): структура L/S.** Лонговая нога carry — балласт; сигнал
строго шорт-тяжёлый:

| Конфиг | WFA PF | cons | WFA robust | OOS PF | OOS Ret | OOS Sharpe | OOS сделок | P(noEdge) |
|---|---|---|---|---|---|---|---|---|
| **L0S3** | 1.36 | 0.62 | true | **1.14** | +0.05 | +0.60 | 93 | 0.30 |
| L1S3 | 1.22 | 0.62 | true | 1.13 | +0.04 | +0.69 | 124 | 0.30 |
| L0S2 | 1.39 | 0.62 | true | 1.08 | +0.02 | +0.29 | 62 | 0.40 |
| L2S3 | 1.16 | 0.75 | true | 1.05 | +0.01 | +0.31 | 155 | 0.41 |
| L2S2 | 1.11 | 0.62 | true | 0.96 | −0.03 | −0.41 | 124 | — |
| L3S0 | — | — | — | 0.91 | −0.05 | −0.76 | 155 | — |
| L2S0 | 0.85 | 0.38 | false | 0.81 | −0.09 | −0.66 | 62 | — |

**Грид 2 (уточнение короткой ноги).** Оптимум смещается к большому `rebalanceBars`, но бар не
достигается:

| Конфиг | WFA PF | cons | WFA robust | OOS PF | OOS Ret | OOS сделок | P(noEdge) |
|---|---|---|---|---|---|---|---|
| **L0S3 rb630 cl5** | 1.36 | 0.62 | true | **1.29** | +0.09 | 66 | 0.19 |
| L0S3 rb420 cl1 | 1.45 | 0.62 | true | 1.24 | +0.07 | 93 | 0.19 |
| L0S3 rb420 cl10 | 1.30 | 0.62 | true | 1.23 | +0.08 | 93 | 0.21 |
| L1S3 rb420 cl10 | 1.24 | 0.62 | true | 1.22 | +0.07 | 124 | 0.19 |
| L0S4 rb420 cl5 | 1.28 | 0.62 | true | 1.21 | +0.07 | 124 | 0.20 |

**Контроли (решающие).** Short-beta сам по себе не прибылен, а знак carry экономически корректен:

| Контроль (rb630 cl5) | WFA PF | cons | WFA robust | OOS PF | OOS сделок | P(noEdge) |
|---|---|---|---|---|---|---|
| CARRY+1 L0S3 (шорт плательщиков funding) | 1.36 | 0.62 | true | **1.29** | 66 | 0.19 |
| MOM L0S3 (тот же short-каркас, ранжир. по momentum) | 1.32 | 0.62 | true | 0.96 | 63 | 0.57 |
| CARRY−1 L0S3 (шорт получателей — обратный знак) | 1.34 | 0.62 | true | 1.05 | 66 | 0.41 |
| MOM L3S0 (длинная нога momentum) | 0.77 | 0.50 | false | 0.67 | 63 | 0.90 |
| CARRY L3S0 (длинная нога carry) | 0.71 | 0.38 | false | 0.91 | 66 | 0.66 |

- **Carry-ранжирование — реальный сигнал, а не short-beta:** тот же short-каркас `L0S3` по
  momentum даёт OOS PF 0.96 (не прибылен), инверсия знака carry — 1.05, а корректный short-carry
  — 1.29. Совпадение `CARRY±1` при rb420 (оба 1.14) оказалось артефактом округления: при rb630
  они расходятся (1.29 vs 1.05), т.е. направление carry действительно важно.
- **Лонговая carry-нога устойчиво убыточна** (L3S0 carry 0.71–0.91): эффективна только короткая
  сторона «шорт высокого funding» (получаем funding). Это согласуется с §5 (futures-оптимум был
  short-heavy).
- **Вердикт: INCONCLUSIVE / не проход** — лучший OOS PF 1.29 < 1.30 при P(noEdge) 0.19 ≫ 0.05
  (критерии CANDIDATE берутся вразнобой), 66 OOS-сделок, `bt.funding_history` покрывает лишь 5
  инструментов. Но это **не REJECTED**: WFA-агрегат robust=true, consistency 0.62, WFA P≈0.02–0.05;
  класс тот же граничный, что momentum (§8/§8a), и осмысленнее reversal (§8b). Ливеридж/расширение
  грида на тех же 730д = selection. `bt.portfolio.momentum-weight` дефолт 1.0 (carry-only — research).

## 9. Вывод

Расширение кросс-секции до 13 инструментов + два риск-оверлея (inverse-vol масштабирование ног,
затем таргетирование волатильности книги) — единственные шаги, качественно улучшившие holdout
(futures-only momentum+carry был OOS PF ~1.0). Итоговый сигнал — **реальный, но строго граничный**:
на стандартном сплите 0.30 OOS PF ~1.29–1.30 при P 0.04–0.06; критерии CANDIDATE (PF ≥ 1.3 И
P ≤ 0.05) берутся вразнобой, но не вместе ни на одной границе (0.25/0.30/0.35). Это устойчивая
закономерность: сигнал слабеет с расширением holdout (сильнее в середине 2025). Честный вердикт —
**INCONCLUSIVE/не проход**: в LIVE не идёт, `bt.portfolio.*` off. Принципиальные рычаги внутри
класса исчерпаны (universe, inverse-vol, vol-target); дальнейшее — свежие/независимые данные или
смена класса, а не новый грид на тех же 730д (selection).

**Смена класса (reversal, §8b) — REJECTED:** обратный знак momentum даёт OOS PF 0.75–0.97 на
660–3488 сделках (`robust=false`), carry/vol-оверлеи не поднимают PF выше ~0.96. Издержки оборота
съедают слабый сигнал. Направленный прайс-сигнал в этом юниверсе не работает ни в одну сторону;
устойчивый компонент один — **carry** (§7). `bt.portfolio.reversal-enabled` остаётся off.

**Carry-центричность (§8c) — INCONCLUSIVE/не проход:** изолированный carry (momentum off)
экономически корректен (шорт плательщиков funding; лонговая нога не платит), лучше и momentum-контроля
(0.96), и инверсии знака (1.05), и даёт WFA-агрегат robust=true/cons 0.62 — но лучший OOS PF 1.29
< 1.30 при P 0.19 ≫ 0.05 (66 сделок, funding_history всего у 5 инструментов). Тот же граничный класс,
что momentum; `bt.portfolio.momentum-weight` дефолт 1.0 (carry-only — research). Без свежих
инструментов/данных вывод не пересматривается на тех же 730д.

## 10. Артефакты

- `build/portfolio_momentum_730d_f8.json`, `build/portfolio_momentum_carry_730d_f8.json`;
- `build/portfolio_sweep/*.json` (+ `summary.json`), `build/portfolio_volscaling_oos.csv`,
  `build/portfolio_voltarget_oos.csv`, `build/portfolio_reversal_oos.csv`,
  `build/portfolio_reversal2_oos.csv`, `build/portfolio_carry_oos.csv`,
  `build/portfolio_carry2_oos.csv`;
- OOS30-сплиты — на лету (`/momentum?oosFraction=0.25/0.30/0.35`), ключевые значения в §6/§8/§8a/§8b/§8c;
- `scripts/research_portfolio_momentum.ps1`, `scripts/research_portfolio_momentum_sweep.ps1`,
  `scripts/research_portfolio_volscaling.ps1`, `scripts/research_portfolio_voltarget.ps1`,
  `scripts/research_portfolio_reversal.ps1`, `scripts/research_portfolio_reversal2.ps1`,
  `scripts/research_portfolio_carry.ps1`, `scripts/research_portfolio_carry2.ps1`;
- движок: `PortfolioMomentumEngine` (vol-scaling + vol-targeting + `reversalEnabled` + `momentumWeight`
  `Params.*`), конфиг `bt.portfolio.vol-*`/`vol-target-*`/`reversal-enabled`/`momentum-weight`,
  тесты `PortfolioMomentumEngineTest` (7 новых, включая reversal и carry-only).
