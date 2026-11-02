# Formal engine-валидация momentum+low-vol (шаг A, 2026-11-02)

**Статус:** выполнено. Вердикт: **INCONCLUSIVE / NOT CONFIRMED**.

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
- Тест `MomentumBacktestSignalGeneratorTest` (5 кейсов: warmup, uptrend BUY,
  downtrend SELL, high-vol HOLD, flat HOLD) — зелёный.
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

| Метрика | Engine | Pre-screen (`docs/42`) |
|---|---:|---:|
| Сделок | 293 | 241 |
| Net | +30 468,31 ₽ | +56 230,80 ₽ |
| PF | **1,355** | 1,781 |
| Avg net | +103,99 ₽ | +233,32 ₽ |
| Block p | **0,0870** | 0,0135 |
| Block CI95 | [−36,81; +263,71] | [+24,77; +436,22] |
| MDD | 17,29 % | 13,72 % |

Per-instrument dev: USDRUBF PF 1,74; GLDRUBF 1,96; CNYRUBF 1,50; IMOEXF 1,20;
EURRUBF 1,14. Порядок тот же, но уровни ниже pre-screen.

## 3. OOS (2022-12-05…2024-10-23), портфель

| Метрика | Engine | Pre-screen (`docs/42`) |
|---|---:|---:|
| Сделок | 197 | 161 |
| Net | +9 159,46 ₽ | +30 862,33 ₽ |
| PF | **1,099** | 1,384 |
| Avg net | +46,49 ₽ | +191,69 ₽ |
| Block p | **0,3440** | 0,0875 |
| Block CI95 | [−191,54; +269,45] | [−94,48; +444,72] |
| MDD | 32,18 % | 23,46 % |

## 4. Вердикт

Формальные гейты `docs/42`:

- Dev: D1 ✅ (293 ≥ 100), D2 ✅ (PF 1,355 ≥ 1,30), **D3 ❌**
  (block p=0,0870 > 0,05), D4 ✅ (avg > 0).
- OOS: O1 ✅ (197 ≥ 30), **O2 ❌** (PF 1,099 < 1,30), **O3 ❌**
  (block p=0,3440), **O4 ❌** (CI95 содержит 0), O5 ✅ (MDD 32,18 %).

**Итог: `INCONCLUSIVE` (фактически not confirmed).** Pre-screen-оптимизм
не воспроизвёлся в движке: PF упал 1,781 → 1,355 на dev и 1,384 → 1,099 на
OOS; block p вырос 0,0875 → 0,344. Причины: реальное исполнение/комиссия/
funding, SL/TP 5 000 пунктов и сайзинг, а также более ранний старт OOS
(`days=2000` добавил 2023-01…2023-07).

## 5. Что это значит для B

**B (forward paper-портфель) сейчас не обоснован.** Pre-screen не прошёл
formal engine-валидацию; запускать вперёд стратегию с OOS PF 1,099 и
p=0,344 — значит бумажно торговать шум. B имеет смысл только после того,
как кандидат пройдёт A (или будет найден другой кандидат).

## 6. Артефакты

- `src/main/kotlin/com/trading/bot/backtest/MomentumBacktestSignalGenerator.kt`
- `src/test/kotlin/com/trading/bot/backtest/MomentumBacktestSignalGeneratorTest.kt`
- `scripts/research_engine_momentum.ps1`
- `artifacts/engine_momentum_2026-11-02_{CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF}.json`
- `artifacts/engine_momentum_2026-11-02_{summary.json,dev.csv,oos.csv}`

**Оговорка:** 1 контракт на инструмент, общий капитал 100k не проверялся на
маржинальную достаточность (GO пяти инструментов ≈ 41,5k). Для live нужен
risk-parity/маржинальный сайзинг отдельным шагом.
