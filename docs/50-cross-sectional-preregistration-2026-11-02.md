# Предрегистрация: cross-sectional momentum/carry на 5 фьючерсах (2026-11-02)

**Статус:** зафиксировано **ДО** прогона. Параметры не меняются.

**Зачем:** последний независимый класс после pairs/passive/momentum. Старые
результаты `docs/26` — **INVALID** (research reset); движок портфельного
моментума с тех пор не менялся семантически, но числа не используются.
Проверяется кросс-секционный momentum + carry на U5 (CNYRUBF, USDRUBF,
EURRUBF, GLDRUBF, IMOEXF) на MINUTE_10 730д — с честным IS70/OOS30 и
dev-WFA. Это **не** повторный тест time-series momentum (он в forward paper
`docs/46`), а другой класс: ранжирование инструментов в сечении.

> Research-эндпоинты `/api/v1/portfolio/momentum*`; live не затрагивается.
> Для LIVE нужен allowlist на все 5 инструментов (сейчас CNYRUBF-only).

## 1. Данные и параметры (frozen)

| Параметр | Значение |
|---|---|
| Юниверс | `CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF` |
| ТФ | `MINUTE_10`, 730д |
| IS/OOS | `oosFraction=0.30`; dev-WFA `folds=8`, `holdoutFraction=0.30` |
| Капитал | 100 000 ₽, `capitalSlice=1.0` |
| Издержки | комиссия/funding по движку (`funding_history`) |

### Конфиги (3, без сетки)

| ID | Параметры |
|---|---|
| **X1** `mom_L2S4_rb300_vs210_vt30` | `lookbackBars=1680, rebalanceBars=300, longCount=2, shortCount=4, momentumWeight=1, carryWeight=0, volScalingEnabled=true, volLookbackBars=210, volTargetEnabled=true, volTargetAnnualized=30, volTargetBarsPerYear=21000` |
| **X2** `mom_carry_L2S4_rb300_cw1_vs210_vt30` | X1 + `carryWeight=1, carryLookbackDays=5` |
| **X3** `carry_L0S3_rb630_cl5` | `lookbackBars=1680, rebalanceBars=630, longCount=0, shortCount=3, momentumWeight=0, carryWeight=1, carryLookbackDays=5, volScalingEnabled=false, volTargetEnabled=false` |

Параметры скопированы из `docs/26` §8/§8c/§8a как замороженные; не
подбираются.

## 2. Гейты

### 2.1 OOS (`/portfolio/momentum`, `oosFraction=0.30`)

| # | Гейт | Порог |
|---|---|---|
| XG1 | OOS сделок | **≥ 30** |
| XG2 | OOS PF | **≥ 1,30** |
| XG3 | OOS P(noEdge) | **< 0,05** |
| XG4 | OOS CI95 средней | **не содержит 0** |
| XG5 | OOS MDD | **≤ 40 %** |
| XG6 | Dev consistency (WFA на первых 70 %) | **≥ 0,60** |

PASS = XG1–XG6 все. FAIL = XG2/XG5. Иначе INCONCLUSIVE. Если проходят
несколько — максимальный OOS PF; донастройка запрещена.

## 3. Анти-подбор

1. Юниверс/конфиги/фолды фиксированы.
2. OOS 2024–2026 запускается один раз.
3. Отрицательный результат закрывает класс cross-sectional momentum/carry
   на U5/MINUTE_10.
4. Новые конфиги/юниверс на этом OOS запрещены.

## 4. Артефакты

- `scripts/research_portfolio_xs.ps1`
- `artifacts/portfolio_xs_2026-11-02_{X1,X2,X3}_{oos,wfa}.json`
- `artifacts/portfolio_xs_2026-11-02_summary.json`

## 5. Результаты

Прогон: 2026-11-02, `scripts/research_portfolio_xs.ps1`,
`artifacts/portfolio_xs_2026-11-02_*`. U5, MINUTE_10, 41 047 баров после
inner-join, `days=730`.

| Конфиг | OOS сделок | OOS PF | OOS P | OOS CI95 | Dev cons | Вердикт |
|---|---:|---:|---:|---|---:|---|
| X1 `mom_L2S4_rb300_vs210_vt30` | **0** | — | 1,00 | — | 0,00 | **INVALID (config)** |
| X2 `mom_carry_L2S4_rb300_cw1_vs210_vt30` | **0** | — | 1,00 | — | 0,00 | **INVALID (config)** |
| X3 `carry_L0S3_rb630_cl5` | **63** | **1,068** | 0,4215 | [−397; +499] | **0,75** | **REJECTED** |

### 5.1 Дефект постановки X1/X2 (не результат edge)

X1/X2 требуют `longCount=2 + shortCount=4 = 6` инструментов, а в U5 их
**пять**. Движок не может выбрать 6 различных ног и возвращает пустой
результат (0 сделок). Это **ошибка pre-registration**, перенесённая из
`docs/26` (там был U13), а не свидетельство отсутствия momentum. Конфиг
невалиден для U5; корректный вариант — `L2S3` или `L1S3`, но OOS
2024–2026 уже использован X3, поэтому новый прогон на нём был бы подбором.

### 5.2 X3 carry-only — REJECTED

63 OOS-сделки, PF **1,068**, P(noEdge)=0,4215, CI95 средней [−397; +499] ₽
(содержит 0), dev consistency 0,75, robust=false. PF < 1,30 → **REJECTED**.
Carry-only на U5 не даёт значимого edge.

### 5.3 Вердикт класса

**Закрыт как `REJECTED`/`INCONCLUSIVE`:** единственный исполнимый на U5
конфиг (carry-only) отклонён; momentum-конфиги были невалидны по числу ног.
Ошибка зафиксирована как урок: **проверять `longCount+shortCount ≤ universe`
до прогона**. Для честного теста momentum нужен U6+/новый горизонт, а не
повторный прогон на использованном OOS.
