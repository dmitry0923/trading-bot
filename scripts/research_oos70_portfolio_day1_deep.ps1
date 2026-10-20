# Research: глубокий прогон `12−1` momentum на DAY_1 (18_10_2026, продолжение пункта 6;
# предрегистрация — docs/26 §11.4/§11.5, расширение на глубокую историю §11.6).
#
# Контекст: §11.5 закрыл класс как INCONCLUSIVE из-за ГЛУБИНЫ данных (2 года DAY_1).
# Пользователь решил донакачать DAY_1/HOUR_1 на максимальную глубину MOEX ISS (2026-10-20).
# Измеренная доступность: акции — с 2018-01-03 (2344 бара DAY_1, 8.8 года),
# перпетуалы CNY/USD/EUR — с 2022-04-26 (~4.5 года), GLDRUBF 2023-07, IMOEXF 2023-11.
#
# Гейт PortfolioWalkForward: need = (skip 21 + lookback 249 + rebalance 21 + 2) = 293 бар/фолд.
#   * акции U8 (2330 бара после загрузки): folds=5 с holdout 0.30; folds=7 на полной истории.
#   * U11 (акции + CNY/USD/EUR, join с 2022-04-26 = 1137 бар): folds=2 с holdout.
#   * U13 (полный, join с 2023-11-14 = 744 бар): folds=1 — по-прежнему неисполнимо.
# -> честный `12−1` протокол возможен на U8 (глубокий) и U11 (контроль «шире юниверс»).
#
# Это НЕ новая сетка: конфиги `12−1` заморожены в §11.4 (lookback 249 / skip 21 / rebalance 21),
# меняется только ГЛУБИНА ИСТОРИИ. §6 PLAN-001 запрещает новые сетки на ТОМ ЖЕ окне; здесь
# окно расширено до 8.8 лет — это ровно та донакачка, которую §11.5 назвал условием продолжения.
#
# Запуск: & .\scripts\research_oos70_portfolio_day1_deep.ps1
param(
    [string]$BaseUrl = "http://localhost:8080",
    # 3220 дней ≈ 8.8 года (от 2018-01-03 до сегодня).
    [int]$Days = 3220,
    [int]$Folds = 5,
    [double]$HoldoutFraction = 0.30,
    [string]$OutSuffix = "day1deep"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# Акции TQBR: единственный юниверс с историей 2018→ (глубина 8.8 года).
$u8 = "GAZP,LKOH,MGNT,NVTK,PLZL,ROSN,SBER,TATN"
# U11 = U8 + три перпетуала (2022-04-26): 4.5 года, folds=2 с holdout — контроль «шире юниверс».
$u11 = "GAZP,LKOH,MGNT,NVTK,PLZL,ROSN,SBER,TATN,CNYRUBF,USDRUBF,EURRUBF"
# U13 = U5 + акции: связан IMOEXF (2023-11) — контроль неполноты, ожидаемо неисполним.
$u13 = "CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF,GAZP,LKOH,MGNT,NVTK,PLZL,ROSN,SBER,TATN"

$d1 = "&timeframe=DAY_1"

$cfg = @(
    # --- классический 12−1 на юниверсе акций (исполним на 8.8 годах) ---
    "d1deep U8 12-1 L249 S21 rb21;&universe=$u8$d1&lookbackBars=249&skipBars=21&rebalanceBars=21",
    # --- контроль: то же без skip (12-0), чтобы изолировать эффект skip-month ---
    "d1deep U8 12-0 L249 S0 rb21;&universe=$u8$d1&lookbackBars=249&skipBars=0&rebalanceBars=21",
    # --- контроль: 9−1 (короткий горизонт 9 мес) на глубокой истории ---
    "d1deep U8 9-1 L187 S21 rb21;&universe=$u8$d1&lookbackBars=187&skipBars=21&rebalanceBars=21",
    # --- U11: акции + CNY/USD/EUR-перпетуалы (4.5 года) — шире юниверс, меньше глубина ---
    "d1deep U11 12-1 L249 S21 rb21;&universe=$u11$d1&lookbackBars=249&skipBars=21&rebalanceBars=21",
    # --- U13: полный прежний юниверс шага 3 (связан IMOEXF 2023-11) — контроль глубины ---
    "d1deep U13 12-1 L249 S21 rb21;&universe=$u13$d1&lookbackBars=249&skipBars=21&rebalanceBars=21"
)

Write-Host ("deep 12-1 DAY_1: {0} конфигов, days={1} folds={2} holdout={3}" -f $cfg.Count, $Days, $Folds, $HoldoutFraction)
& (Join-Path $PSScriptRoot "research_oos70_portfolio.ps1") `
    -BaseUrl $BaseUrl -Days $Days -Folds $Folds -HoldoutFraction $HoldoutFraction `
    -ConfigCsv $cfg `
    -OutCsv (Join-Path $root "artifacts\p6_day1deep_$OutSuffix.csv") `
    -OutJson (Join-Path $root "artifacts\p6_day1deep_$OutSuffix.json") `
    -StatusFile (Join-Path $root "artifacts\p6_day1deep_$OutSuffix.status")
