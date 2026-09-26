# Аудит 10 стратегий (пользовательский список, 2026-09-25)

Вход: список из 10 стратегий «классический квант + LLM-агенты» с требованием
протестировать их на 2-летней истории через WFA. Ниже — проверка реализуемости
**на текущем стеке и текущих данных репозитория** (не план, а факт-аудит).

## Что реально есть в данных (проверено 2026-09-25, `candles` MINUTE_10)

| Тикер | Свечей | Период | Годится на 2 года WFA |
|---|---|---|---|
| GAZP, SBER, NVTK, PLZL | 56k–57k | 2024-09-19 … 2026-09-24 | да (≈2 года) |
| CNYRUBF | 46 563 | 2024-09-19 … 2026-09-24 | да |
| USDRUBF, EURRUBF, GLDRUBF, IMOEXF | 43k–50k | 2024-09-23 … 2026-09-22 | да (перпетуалы) |
| MGNT, LKOH, ROSN, TATN | 8.3k–8.9k | 2026-06-11 … | нет (3 мес) |
| VTBR | 1 965 | 2026-09-01 … | нет (3 нед) |
| **Si, RI, RIU6** | 5.7k–6.2k | 2026-06-11 … 2026-08-28 | **нет (2.5 мес)** |
| RIZ6 / RIM7 | 2 / 1 | — | нет (мусор от склейки контрактов) |

`funding_history`: CNYRUBF, USDRUBF, EURRUBF, GLDRUBF, IMOEXF (508–509 дат SWAPRATE).

В БД **нет** таблиц стакана, тиков и сделок-по-всем-тикам: только `candles`
(OHLCV), `macro_snapshots`, `agent_logs`, `trade_events` (собственные сделки бота).
Вывод: любая стратегия, требующая микроструктуры (Order Book Imbalance, VPIN),
структурно не тестируется без нового слоя загрузки и хранения данных.

Инструментов в `InstrumentsConfig`: Si, GAZP, VTBR, CNYRUBF, USDRUBF, GLDRUBF,
IMOEXF. **Нет** SBERP (префы), BR (Brent), RGBI (ОФЗ-индекс), GD/RTS-имён.

## Карта стратегий

| № | Стратегия | Детерминированное ядро | Данные | Вердикт |
|---|---|---|---|---|
| 1 | Интрадей MR CNYRUBF от VWAP (1.5 StdDev + ADX<25) | сессионный VWAP по OHLCV, StdDev, ADX(1ч) | есть, 2 года | **реализуемо** — новая логика входа, чистый research-фильтр |
| 2 | Календарный спред Si (near/far, roll yield) | требует пары контрактов (Si-3.26/Si-6.26) + RUONIA | **нет**: Si только 2.5 мес, пары нет, RUONIA нет | **нереализуемо** без донакачки истории и ставки RUONIA |
| 3 | Скальпинг утреннего аукциона (стакан, OBI) | Order Book Imbalance из стакана | **нет стакана** | **нереализуемо** (нужен новый data-слой) |
| 4 | Overnight-гэп SBER (вход 18:38, выход на открытии) | session-фильтр входа + удержание через max-hold | есть, 2 года | **частично реализуемо**: вход по сессии есть, межсессионное удержание — эмулируется `maxHoldBars`; честный overnight требует доработки движка позиций |
| 5 | StatArb SBER/SBERP (Z-score 20д) | Z-score пары | **нет SBERP** | **нереализуемо** без донакачки SBERP |
| 6 | Вечерний импульс Brent (19:00–23:50, 2ч-пробой) | breakout 2ч + session-фильтр | **нет BR** | **нереализуемо** без донакачки BR; аналог на GLDRUBF/USDRUBF возможен |
| 7 | ORB на золоте (15:30–16:00 МСК, пробой на открытии США) | ORB-фильтр уже реализован (`bt.orb-*`) | GLDRUBF есть, 2 года | **реализуемо сразу** — ORB на вечернем окне на GLDRUBF (нужен `orbWindowStartMinutes`, окно сейчас «с начала дня») |
| 8 | Обеденный MR на RI (13:00–15:00, канал Кельтнера) | канал Кельтнера + session-фильтр | RI только 2.5 мес | **ограниченно**: ядро реализуемо, но 2.5 мес ≠ 2 года (в AGENTS.md уже помечен RI как нестабильный/исключён) |
| 9 | VPIN на Si (токсичность потока) | VPIN по тикам/всем сделкам | **нет тиков/стакана** | **нереализуемо** (нужен новый data-слой) |
| 10 | Flight-to-quality Mix/RGBI (корреляция) | корреляция двух серий, порог −1.5%/час | IMOEXF есть, **RGBI нет** | **нереализуемо** в корректном виде: вторая нога отсутствует. Одноголая эмуляция (шорт IMOEXF) — другой тест, не то же самое |

**Итог: из 10 стратегий полноценно тестируются на текущих данных 2 (№1 и №7),
частично — 2 (№4, №8). Остальные 6 упираются в отсутствие данных** (SBERP, BR,
RGBI, история Si/RI, стакан/тики) либо в новый data-слой.

### Решение пользователя (2026-09-26): подстановки + 3 новых ядра

Вместо «сдаться» по нереализуемым инструментам принято: **тестировать ядро на
доступном тикере, сохраняя экономику оригинала**, и честно называть это
подстановкой в вердикте.

| № | Оригинал | Реализуемое ядро (подстановка) | Инструмент |
|---|---|---|---|
| 2 | Календарный спред Si | **macro-trend вход в трендовой среде** (EMA20>EMA50 на H1 + откат) | CNYRUBF (2 года) |
| 3 | Утренний аукцион + OBI (стакан) | **ORB-пробой** (открытие диапазона дня) | GLDRUBF (2 года) |
| 7 | ORB на золоте | **panic-reversal** (перепроданность + разворот) | IMOEXF (2 года) |

Три новых детерминированных ядра (все — входные research-фильтры, **дефолт off**):
- **№8 squeeze-breakout** (CNYRUBF) — как ядро из п. «Обеденный MR»: канал
  Кельтнера/Боллинджера, но логика «сжатие → импульс», а не возврат к среднему;
- **№7 panic-reversal** (IMOEXF) — перепроданность по старшему ТФ + разворот;
- **№1 macro-trend** (CNYRUBF) — тренд + откат, дополнено funding plateau 5–8 ₽
  и выходом по времени.

Подробности реализации — в разделе «Три новых детерминированных ядра» ниже.

## Что нужно добавить в код для тестируемых ядер (паттерн уже есть в репо)

Все исследовательские входные фильтры сделаны по одному шаблону (см. `docs/16`,
`docs/18`): параметр в `BacktestConfig` (`bt.*`, env `BT_*`) → query-override в
`ApiController.buildSignalGenerator` → проверка в `LiveStrategyBacktestSignalGenerator`
после confidence-gate → тесты. Новые фильтры не трогают live-путь (он идёт через
`DecisionEngine`) и по умолчанию выключены.

## Реализовано в коде (2026-09-26)

Обе реализуемые стратегии реализованы по общему шаблону research-фильтров
(`BacktestConfig` → query-override → `EntryFilters` → тесты), **дефолт off**,
live-путь не затронут.

### №7 ORB на золоте — окно диапазона (commit `8139342`)

`EntryFilters.orbDirection(candles, index)` получил окно диапазона:
- `bt.orb-window-start-minutes` / `bt.orb-window-end-minutes` (query: `orbWindowStartMinutes`,
  `orbWindowEndMinutes`) — минуты от полуночи;
- диапазон = первые `orbWindowBars` баров текущего дня, начиная с первого бара
  с временем ≥ `orbWindowStartMinutes`;
- проверка пробоя — только на барах с временем ≥ `orbWindowEndMinutes`
  (для суженного окна). Дефолт `0..1440` = исходное поведение (диапазон = первые
  бары дня, проверка весь день) — обратная совместимость;
- до начала окна и внутри окна (диапазон не закрыт) — пропуск либо HOLD при
  `orbBlockOnUnknown` (fail-closed);
- настройка под стратегию: `orbWindowStartMinutes=930`, `orbWindowEndMinutes=960`,
  `orbWindowBars=3` (MINUTE_10 → диапазон 15:30–16:00 МСК, вход на пробое после 16:00).

Тесты (`EntryFiltersTest`, +5 кейсов): до окна / внутри окна / после окна,
пробой вверх и вниз, strict vs loose, fail-closed, день, начинающийся после
начала окна, диаграмма длиннее окна, дефолтное поведение.

### №1 VWAP-MR (commit `1af7944`)

`IndicatorCalculator`:
- `vwap(candles)` — сессионный VWAP по типичной цене с **сбросом по дате**
  (типичная цена = (H+L+C)/3, объёмные веса);
- `vwapStdDevPercent(candles)` — σ типичной цены в % от VWAP;
- `adx(candles, period)` — ADX по Уайлдеру (для трендового фильтра);
- `MIN_MEANINGFUL_VWAP_SIGMA_PERCENT = 1e-6` — **найденный баг**: на полностью
  плоской сессии σ ≈ 1e-14 (float-шум), деление отклонения на такую σ давало
  ложные отклонения в тысячи σ и ложные BUY/SELL на плоском рынке. Регрессионный
  тест `flat session sigma is float noise...`.

`EntryFilters.vwapMrDirection(session, higherTimeframeCandles)`:
- вход только при отклонении |close − VWAP| ≥ `vwapMrDeviationSigma`·σ **и**
  ADX(`vwapMrTimeframe`, по умолчанию HOUR_1) ≤ `vwapMrMaxAdx`;
- close ниже VWAP на Nσ → BUY (возврат вверх), выше → SELL;
- отклонение < Nσ → HOLD; ADX выше порога → HOLD (MR в тренде не работает);
- сессия не набрала `vwapMrMinSessionBars` либо σ ≈ 0 → HOLD при
  `vwapMrBlockOnUnknown` (fail-closed), иначе пропуск;
- ADX старшего ТФ считается через `CandleResampler.resample(..., completedBefore = bar.time)` —
  **без lookahead** (используются только завершённые часовые бары);
- query-override: `vwapMrEnabled`, `vwapMrDeviationSigma`, `vwapMrMaxAdx`,
  `vwapMrTimeframe`, `vwapMrMinSessionBars`, `vwapMrBlockOnUnknown` на 5 эндпоинтах.

Дефолты: `vwapMrEnabled=false`, `vwapMrDeviationSigma=1.5`, `vwapMrMaxAdx=25.0`,
`vwapMrTimeframe=HOUR_1`, `vwapMrMinSessionBars=6`, `vwapMrBlockOnUnknown=true`.

Тесты: `IndicatorCalculatorVwapAdxTest` (11 кейсов: VWAP-веса, сброс сессии,
σ, ADX на тренде/боковике, диапазон, регрессия float-шума) + 7 кейсов
`vwapMr*` в `EntryFiltersTest`. Итого 36 тестов, `test` + `ktlintCheck` зелёные.

**Что ещё не сделано для №1**: выход «возврат к VWAP» — движок позиций не имеет
такого exit-типа; на текущем прогоне выход моделируется SL/TP grid бэктеста.
Это отдельная задача и отдельное исследование (см. ниже).

Требование пользователя «в 5% случаев симулировать таймаут LLM» — **отдельный
нереализованный флаг**: сейчас есть только реальный таймаут
`trading.llm-signal-budget-ms` → fail-closed HOLD. Инъекция искусственных таймаутов
в бэктест требует нового кода (`bt.agent.timeout-injection-rate`).

LLM-роли ( veto по свопам/РЕПО, календарь США, корпоративные новости) в списке
описывают внешние источники, которых в проекте нет: новости приходят только через
`RgRuNewsProvider` (платная подпилка, `news.enabled`, дефолт выключен), макро —
`macro_snapshots`. Парсинг сайта ЦБ и календаря EIA/CPI не реализован.

## Три новых детерминированных ядра (2026-09-26)

По шаблону research-фильтров: `BacktestConfig` (`bt.*`, env `BT_*`, дефолт off) →
query-override на 5 эндпоинтах (`/backtest`, `/validate`, `/robustness`,
`/holdout`, `/deployment-gate`) → `EntryFilters` → тесты. Live-путь не затронут.

### №8 Squeeze-breakout (CNYRUBF) — `EntryFilters.squeezeDirection`

Гипотеза: сжатие волатильности (Bollinger **внутри** Keltner) предшествует
импульсу; вход — на выходе полосы за границу.

`IndicatorCalculator`:
- `KeltnerChannel(middle/upper/lower)` и `keltner(candles, emaPeriod, atrPeriod, multiplier)`
  — EMA ± multiplier·ATR (по Уайлдеру), `null` при нехватке данных;
- `isSqueeze(candles, ...)` — `bbUpper < kcUpper && bbLower > kcLower` (сжатие
  «внутри», строгие неравенства: касание = сжатия нет);
- константы `BOLLINGER_SQUEEZE_PERIOD=20`, `BOLLINGER_SQUEEZE_MULT=2.0`,
  `KELTNER_EMA_PERIOD=20`, `KELTNER_ATR_PERIOD=10`, `KELTNER_MULT=1.5`.

`EntryFilters.squeezeDirection`: пока BB внутри Keltner — HOLD; на первом баре,
где сжатия нет И `close > bbUpper` → BUY, `close < bbLower` → SELL; иначе PASS.

Query: `squeezeEnabled`, `squeezeBlockOnUnknown` (fail-closed при нехватке истории).

**Найденная ловушка при тестировании** (зафиксировано, чтобы не повторить):
линейный рост цены **не** даёт пробоя — σ Bollinger растёт вместе с ценой, и
z-последней точки ≈ 1.65 < 2σ, т.е. `isSqueeze=false`, но `close < bbUpper` → HOLD.
Корректный тест на пробой — «19 плоских баров + резкий импульс в последнем».
Тест `EntryFiltersTest`: 19 плоских баров (100±1) + импульс 130/70.

### №7 Panic-reversal (IMOEXF) — `EntryFilters.panicReversalDirection`

Гипотеза: резкое падение сессии на фоне перепроданности по старшему ТФ разворачивается.
Вход **только LONG** (панельный шорт такого сигнала не подтверждала — см. wave-2
вывод про SHORT-окно 13–16 в AGENTS.md).

Условия (все должны выполняться):
- просадка текущей сессии (open → low) ≥ `panicMinSessionDropPercent`;
- RSI(`panicRsiPeriod`) на `panicTimeframe` ≤ `panicMaxRsi`;
- последний бар bullish (close > open), если `panicRequireBullishBar`;
- сессия набрала ≥ `panicMinBars` баров, иначе HOLD при `panicBlockOnUnknown`.

Старший ТФ — через `CandleResampler.resample(..., completedBefore = bar.time)`,
**без lookahead**; RSI считается по завершённым барам.

### №1 Macro-trend (CNYRUBF) — `EntryFilters.macroTrendDirection`

Гипотеза: в трендовой среде вход по откату даёт лучше R:R, чем вход на пробое.

Условия: EMA(`macroTrendFastEma`) > EMA(`macroTrendSlowEma`) на `macroTrendTimeframe`
**и** отклонение базового close от EMA(`macroTrendPullbackEmaPeriod`) ≤
`macroTrendMaxDeviationPercent` (иначе это разгон, а не откат). Пропуск
(SELL) в шорт-тренде, HOLD — при нехватке данных старшего ТФ при
`macroTrendBlockOnUnknown=true`.

**Осознанное упрощение против исходной формулировки.** В постановке ядро
описано как «EMA-тренд + VWAP-pullback + funding plateau 5–8 ₽ + maxHold 1095».
Реализовано: EMA-тренд + откат к базовой EMA. VWAP-откат **не** добавлен, потому что
он контртрендовый по природе (вход к среднему) и логически противоречит трендовому
ядру; VWAP-MR исследуется отдельно (`research_wfa_vwapmr.ps1`). Funding plateau и
max-hold — существующие механизмы, подключаются в WFA-сценарии, код не дублируется.
Расхождение зафиксировано здесь, чтобы результат не выдавался за полный оригинал.

### Общий для трёх ядер нюанс: warm-up старшего ТФ

`higherTimeframeLookback` расширяет окно ресемплинга до `higherTfBarsNeeded` с
1.5×-запасом (жёсткий потолок — иначе O(n²) на 46k свечей), затем
`completedBefore = bar.time` исключает lookahead. Следствие: первые бары прогонa
не имеют завершённых часовых баров → при `*BlockOnUnknown=true` они HOLD, и
фильтр включается постепенно, а не с первого бара. Тест
`panic reversal fail-closed blocks everything without higher timeframe history`
фиксирует fail-closed на короткой истории.

## Протокол честной валидации IS 70% / OOS 30% (`scripts/research_oos70.ps1`)

Зачем отдельный инструмент: предыдущие калибровки подбирали параметры на
**полной** истории, а формальный гейт гоняли на dev-части **той же** истории. Это
не исключает selection bias — на combo730 плато `P(noEdge)=0.058` на полной
истории дало OOS PF 0.78–0.92 на dev-части (см. AGENTS.md).

Дисциплина нового протокола:
- `holdoutFraction=0.30` → WFA обучается на первых 70%, последние 30% (holdout)
  не участвуют ни в подборе, ни в walk-forward;
- **вердикт только по holdout-сегменту**: `OOS PF < 1.3` → `REJECTED` независимо от
  IS/dev-метрик; при `< 30` сделок holdout → `INCONCLUSIVE` (защита от вывода на
  2–3 сделках, тот же принцип, что `MIN_WALK_FORWARD_TRADES=100` в
  `DeploymentGate`);
- IS/dev-колонки — диагностика, а не вердикт;
- прогон не подбирает параметры: на вход подаются **замороженные** конфиги
  (`-ConfigCsv "имя;query;…"`), сетку подбора гоняют `/validate`-скрипты.

Порог 1.3 не выбран произвольно: `BacktestResult.isPassable()` уже использует
`profitFactor > 1.3` как порог проходимости backtest, так что новый протокол
применяет тот же критерий к независимому сегменту.

Скрипты этого этапа:
- `research_wfa_squeeze.ps1` / `research_wfa_panic.ps1` / `research_wfa_macrotrend.ps1`
  — IS-скрининг и WFA-OOS по сетке параметров на `/validate`;
- `research_oos70.ps1` — независимый holdout-вердикт для отобранных кандидатов
  (`-RunGate` дополнительно зовёт `/deployment-gate` с Monte Carlo и стрессом).

Порог 5–8 ₽ для funding-veto проверяется как **плато**, а не как пик: устойчивость
(5/6/8 рядом) важнее максимума — именно на этом провалились max-hold, ORB и
time-direction (см. AGENTS.md).

