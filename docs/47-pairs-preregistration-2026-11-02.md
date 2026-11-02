# Предрегистрация: pairs/relative value CNYRUBF–USDRUBF на DAY_1 2022–2026 (2026-11-02)

**Статус:** зафиксировано **ДО** прогона. Параметры не меняются.

**Зачем:** momentum+low-vol ушёл в forward paper (`docs/46`), но это
направленный класс. Следующий независимый источник — **market-neutral
relative value**: спред CNY/RUB и USD/RUB. Старый прогон `docs/25` (MINUTE_10,
730д) дал OOS PF 0,51, но его числа **INVALID** после research reset и
исправления pairs-beta (beta/z на `i−1`). Новый прогон — на **DAY_1 2022–2026**
(1 148 баров), впервые на глубокой истории, с новым OOS-периодом 2025–2026.

> Research-эндпоинт `/api/v1/pairs/*`; live не затрагивается. Для LIVE нужна
> вторая нога (USDRUBF), сейчас allowlist CNYRUBF-only — отдельное решение.

## 1. Данные и период

| Параметр | Значение |
|---|---|
| Ноги | `CNYRUBF` / `USDRUBF` |
| ТФ | `DAY_1`, 2022-04-26…2026-10-30 |
| Funding | `funding_history` обеих ног, per-clearing |
| Издержки | комиссия 1,5 ₽/контракт/сторона, slippage 1 bp, funding по факту |
| Капитал | 100 000 ₽, `capitalSlice=0.50` |
| Параметры | `lookbackBars=240`, `entryZ=2.0`, `exitZ=0.5`, `stopZ=4.0`, `maxHoldBars=0` |

Параметры взяты из `docs/25` **без изменений** (frozen), не подбираются.

**Разделение:** IS = первые 70 % истории (2022-04…2025-01), OOS = последние
30 % (2025-01…2026-10). Это новый OOS: прежний pairs-прогон был на 730д
MINUTE_10 и другом окне.

## 2. Гипотеза

`spread = log(CNYRUBF) − beta·log(USDRUBF)`, rolling OLS beta по 240 барам;
z-score спреда. Вход при `|z| ≥ 2.0` (long дешёвой ноги / short дорогой),
выход при `|z| ≤ 0.5`, стоп `|z| ≥ 4.0`. Позиция market-neutral к рублю.
Экономика: CNY и USD — конкурирующие валюты; краткосрочное расхождение
спреда должно возвращаться.

## 3. Гейты

### 3.1 OOS (`/pairs/backtest`, `oosFraction=0.30`)

| # | Гейт | Порог |
|---|---|---|
| P1 | OOS сделок | **≥ 30** |
| P2 | OOS Profit Factor | **≥ 1,30** |
| P3 | OOS P(noEdge) | **< 0,05** |
| P4 | OOS CI95 средней сделки | **не содержит 0** |
| P5 | OOS MDD | **≤ 40 %** (от 100k) |

### 3.2 WFA (`/pairs/validate`, `folds=3`)

folds=6 при 1 148 барах и lookback 240 даёт сегмент ~191 < 240 → вырождение
(урок `docs/26` §11.9), поэтому **folds=3** (сегмент ~383). Гейты:
consistency ≥ 0,60, aggregate OOS PF ≥ 1,30, OOS сделок ≥ 30, robust=true.

**PASS** = P1–P5 + WFA-гейты. FAIL = P2/P5 или WFA PF < 1,30. Иначе
INCONCLUSIVE.

## 4. Анти-подбор

1. Пара и параметры фиксированы; сетки нет.
2. OOS 2025–2026 запускается один раз.
3. Отрицательный результат закрывает пару CNYRUBF/USDRUBF на DAY_1.
4. Новые пары/пороги на этом OOS запрещены.

## 5. Артефакты

- `scripts/research_pairs_cnyusd.ps1`
- `artifacts/pairs_cnyusd_2026-11-02_backtest.json`
- `artifacts/pairs_cnyusd_2026-11-02_wfa.json`

## 6. Результаты

Прогон: 2026-11-02, `scripts/research_pairs_cnyusd.ps1`,
`artifacts/pairs_cnyusd_2026-11-02_{backtest,wfa}.json`. 1 116 баров DAY_1,
`spreadCorr=0,886`, `meanBeta=0,888`.

### 6.1 Backtest IS70/OOS30

| Метрика | Full | IS | OOS |
|---|---:|---:|---:|
| Сделок | 14 | 10 | **4** |
| PF | 0,630 | 0,846 | **0,323** |
| P(noEdge) | — | — | 0,786 |
| CI95 средней | — | — | [−4 732; +1 580] ₽ |
| MDD | — | — | 7,66 % |

Вердикт эндпоинта: `INCONCLUSIVE (oos trades 4 < 30)`.

### 6.2 WFA folds=3

3 сделки, aggregate PF **0,430**, consistency **0**, robust=false,
`INCONCLUSIVE (trades 3 < 30)`.

### 6.3 Вывод

На DAY_1 пара **почти не расходится** (14 сделок за 4,5 года): z-score
крайне редко превышает 2,0 при `meanBeta≈0,89` и корреляции спреда 0,886.
Выборка OOS (4 сделки) недостаточна для вердикта — `INCONCLUSIVE`, не
REJECTED. Проверка мощности — MINUTE_10 (`docs/48`), где сделок ~500.
