# Formal engine-валидация momentum+low-vol (шаг A, 2026-11-02)

> **УТОЧНЕНИЕ (2026-11-06, `docs/58` §2.5).** При зарегистрированном капитале 100k
> `$mdd / 1000.0` численно равно MDD в процентах; ошибки единиц в вердикте нет.
> После суммирования P&L по датам выхода OOS closed-trade MDD = **36 646 ₽ = 36,65%**,
> поэтому O5 ≤ 40% по-прежнему пройден. Итоговый `REJECTED` определяется D3/O2–O4
> и не меняется. Это closed-trade оценка, а не mark-to-market MDD открытых позиций.

**Статус:** выполнено. Вердикт: **REJECTED** (dev-гейт D3 не пройден;
OOS-прогон — диагностический, тот же уже использованный период, тоже не прошёл).

**Зачем:** pre-screen `docs/42` дал dev PF **1,781** (block p=0,0135) и OOS
PF **1,384** на 161 сделке (block p=0,0875), но это был standalone-симулятор.
Шаг A проверяет тот же сигнал **в `BacktestEngine`** с его исполнением,
комиссией, funding и риск-сайзингом. LIVE не затрагивался.

## 1. Реализация

- Новый `MomentumBacktestSignalGenerator` (`src/main/kotlin/.../backtest/`):
  `mom20`, `ATR20%`, перцентиль за 252 бара, `volPct <= 0,50`, иначе HOLD.
  Использует только `candles[0..index]` (без look-ahead).
- Query-параметр `momentumSignalEnabled=true` на `/backtest` и `/validate`
  (research, default off); ветка в `buildSignalGenerator`.
- Тест `MomentumBacktestSignalGeneratorTest` (6 кейсов: warmup, uptrend BUY,
  downtrend SELL, high-vol HOLD, flat HOLD, stateful hold) — зелёный.
- Прогон: `/backtest/{ticker}?days=2000&timeframe=DAY_1&momentumSignalEnabled=true`
  `&maxHoldBars=4&wfaSlPoints=5000&wfaTpPoints=5000`
  `&riskPerTradePercent=100&futuresMaxContractsPerPosition=1&includeTrades=true`.
  5 инструментов: CNYRUBF, USDRUBF, EURRUBF, GLDRUBF, IMOEXF.
- `maxHoldBars=4` = 5 баров включая бар входа (паритет с pre-screen).
  SL/TP 5 000 пунктов — «disaster stop», чтобы не резать позицию до
  max-hold; для сайзера это конечный стоп (иначе `riskPerTradePercent=100`
  и гигантский стоп дают qty=0). Это **отличие** от pre-screen (там SL/TP
  не было), и оно частично объясняет расхождение.

## 2. Dev (2024-10-24…2026-10-30), портфель

| Метрика | Engine (final) | Pre-screen (`docs/42`) |
|---|---:|---:|
| Сделок | 209 | 241 |
| Net | +29 119,79 ₽ | +56 230,80 ₽ |
| PF | **1,436** | 1,781 |
| Avg net | +139,33 ₽ | +233,32 ₽ |
| Block p | **0,0605** | 0,0135 |
| Block CI95 | [−39,91; +333,00] | [+24,77; +436,22] |
| MDD | 20,20 % | 13,72 % |

Per-instrument dev: USDRUBF PF 2,35; CNYRUBF 2,04; GLDRUBF 1,53; EURRUBF 1,16;
IMOEXF 1,01. Порядок тот же, но уровни ниже pre-screen.

> **Паритет с pre-screen (найдено при A):** первый engine-прогон дал
> REVERSAL-выходы (71 сделка, −29 725 ₽), потому что движок закрывает позицию
> при противоположном сигнале, а pre-screen сигналы во время удержания
> игнорировал. Генератор сделан stateful: после сигнала на баре `i` он
> возвращает HOLD на `i+1..i+5` (виртуальное удержание 5 баров). Это
> восстановило паритет; числа выше — после фикса. Без фикса было PF 1,355
> (dev) и 1,099 (OOS) — ещё ниже.

## 3. OOS (2022-12-05…2024-10-23), портфель

| Метрика | Engine (final) | Pre-screen (`docs/42`) |
|---|---:|---:|
| Сделок | 135 | 161 |
| Net | +12 471,74 ₽ | +30 862,33 ₽ |
| PF | **1,183** | 1,384 |
| Avg net | +92,38 ₽ | +191,69 ₽ |
| Block p | **0,2795** | 0,0875 |
| Block CI95 | [−203,08; +399,19] | [−94,48; +444,72] |
| MDD | 26,16 % | 23,46 % |

## 3a. WFA folds=3 (post-hoc диагностика)

`/validate` для каждого тикера: `days=2000&timeframe=DAY_1&folds=3`
`&momentumSignalEnabled=true&maxHoldBars=4&wfaSlPoints=5000&wfaTpPoints=5000`
`&riskPerTradePercent=100&futuresMaxContractsPerPosition=1&includeOosTrades=true`.

**Почему folds=3, а не 8:** при 1 116 барах DAY_1 и warmup 270 баров
8 фолдов дают сегмент ~139 < warmup → **0 OOS-сделок** (проверено;
урок `docs/26` §11.9). folds=3 даёт сегмент ~372 бара и ~100 сигнальных
баров на фолд. Это геометрия, а не подбор под результат, но выбрана
**после** отказа folds=8, поэтому WFA — post-hoc, не предрегистрированный.

| Тикер | OOS сделок | OOS PF | Consistency | OOS P(noEdge) |
|---|---:|---:|---:|---:|
| USDRUBF | 23 | **3,195** | 1,000 | **0,0175** |
| EURRUBF | 25 | **3,047** | 1,000 | **0,0055** |
| CNYRUBF | 22 | 1,629 | 0,667 | 0,203 |
| GLDRUBF | 5 | 5,562 | 0,667 | 0,0745 |
| IMOEXF | 0 | — | 0,000 | 1,000 |

**Портфель (агрегат OOS fold-сделок, block bootstrap по датам выхода):**

| Метрика | Значение |
|---|---:|
| Сделок | **75** |
| Net | **+58 157,70 ₽** |
| PF | **3,010** |
| Win rate | 61,3 % |
| Avg net | **+775,44 ₽** |
| Медиана net | +316,03 ₽ |
| Block p | **0,0020** |
| Block CI95 | **[+217,32; +1 380,70]** |
| MDD | 9,86 % |

**Как это читать:** WFA-агрегат выглядит значительно сильнее fixed-split
(dev PF 1,436 / OOS 1,183) и формально проходит OOS-гейты (≥ 30 сделок,
PF ≥ 1,30, block p < 0,05, CI > 0, MDD ≤ 40 %). Но это **post-hoc**
результат: folds=3 выбраны после дегенерации folds=8; период 2022–2026
пересекается с dev; IMOEXF не дал ни одной OOS-сделки; 75 сделок — тонко.
Поэтому WFA **не отменяет** предрегистрированный вердикт fixed-split
(`REJECTED` по D3), а является **hypothesis-generating** evidence: кандидат
заслуживает **forward paper-теста (B)** на новых данных, а не live-допуска.

## 4. Вердикт

Формальные гейты `docs/42`:

- Dev: D1 ✅ (209 ≥ 100), D2 ✅ (PF 1,436 ≥ 1,30), **D3 ❌**
  (block p=0,0605 > 0,05), D4 ✅ (avg > 0).
- OOS: O1 ✅ (135 ≥ 30), **O2 ❌** (PF 1,183 < 1,30), **O3 ❌**
  (block p=0,2795), **O4 ❌** (CI95 содержит 0), O5 ✅ (MDD 26,16 %).

**Итог: `REJECTED`.** Dev D3 не пройден (block p=0,0605 > 0,05); по §4.1
`docs/42` кандидат закрывается, OOS не требуется. OOS-прогон выполнен
**только как диагностика** (тот же период 2022–2024 уже использован
pre-screen `docs/42`) и тоже не прошёл: PF 1,183, p=0,2795.

Pre-screen-оптимизм не воспроизвёлся в движке: PF упал 1,781 → 1,436 на dev
и 1,384 → 1,183 на OOS; block p вырос 0,0875 → 0,2795. Причины: реальное
исполнение/комиссия/funding, SL/TP 5 000 пунктов и сайзинг, а также
stateful-паритет (без него было ещё хуже: 1,355/1,099).

## 5. Что это значит для B

**B (forward paper-портфель) — следующий шаг, но только как pre-registered
forward test, не live.** Fixed-split A не прошёл (dev D3), поэтому нельзя
говорить «стратегия валидирована». Но WFA folds=3 даёт сильный post-hoc
сигнал (PF 3,01, block p=0,002, 75 сделок), который **не объясняется
тонкой выборкой 12 сделок** и заслуживает проверки на **новых** данных.
Forward paper — единственный способ получить новый OOS; он должен:
1. быть предрегистрирован (юниверс, сигнал, сайзинг, гейты, срок);
2. торговать **1 контракт на инструмент** в paper, без live-ордеров;
3. иметь гейты: ≥ 30 forward-сделок, PF ≥ 1,30, block p < 0,05, CI > 0,
   MDD ≤ 40 %;
4. не менять параметры по ходу.

До этого live-контур не трогаем: `maxC=1`, Kelly, allowlist CNYRUBF.

## 6. Артефакты

- `src/main/kotlin/com/trading/bot/backtest/MomentumBacktestSignalGenerator.kt`
- `src/test/kotlin/com/trading/bot/backtest/MomentumBacktestSignalGeneratorTest.kt`
- `scripts/research_engine_momentum.ps1`
- `scripts/research_engine_momentum_wfa.ps1`
- `artifacts/engine_momentum_2026-11-02_{CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF}.json`
- `artifacts/engine_momentum_2026-11-02_{summary.json,dev.csv,oos.csv}`
- `artifacts/engine_momentum_wfa_2026-11-02_{summary.json,trades.csv}`

**Оговорка:** 1 контракт на инструмент, общий капитал 100k не проверялся на
маржинальную достаточность (GO пяти инструментов ≈ 41,5k). Для live нужен
risk-parity/маржинальный сайзинг отдельным шагом.
