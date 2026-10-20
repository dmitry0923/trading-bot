# Research: направление (b) — классический `12−1` momentum на DAY_1 (18_10_2026, пункт 6;
# предрегистрация — docs/26 §11.4).
#
# Гипотеза: текущий lookback 1680 MINUTE_10 ≈ 21 торговый день сидит на горизонте месячного
# реверсала; классический momentum должен исключать последний месяц (skip-month, «12−1»).
#
# Предрегистрация ДО прогона (docs/26 §11.4) зафиксировала:
#   * inner-join DAY_1 = 498 баров (U5-связанный; U13/U15 тождественны);
#   * гейт PortfolioWalkForward требует (skip + lookback + rebalance + 2) × folds баров;
#   * при folds=8 dev=348 → потолок окна 41 бар (≈2 месяца), значит «12−1» (293 бара)
#     структурно НЕИСПОЛНИМ.
# Прогон исполняется как измеримое доказательство неисполнимости + проба максимально
# выразимых конфигов. Конфиги заморожены, holdout не участвует в выборе.
#
# Запуск: & .\scripts\research_oos70_portfolio_day1.ps1
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$HoldoutFraction = 0.30,
    [string]$OutSuffix = "day1"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$u5 = "CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF"
$u13 = "$u5,GAZP,LKOH,MGNT,NVTK,PLZL,ROSN,SBER,TATN"
$u15 = "$u13,SBERF,GAZPF"

# Префикс DAY_1 без пересчёта: бары задаются напрямую (предрегистрация §11.4).
$d1 = "&timeframe=DAY_1"

$cfg = @(
    # --- классический 12−1 (ожидаемо неисполним: 293 бара окна × 8 фолдов >> 348 dev) ---
    "d1 U13 12-1 L249 S21 rb21;&universe=$u13$d1&lookbackBars=249&skipBars=21&rebalanceBars=21",
    "d1 U15 12-1 L249 S21 rb21;&universe=$u15$d1&lookbackBars=249&skipBars=21&rebalanceBars=21",
    # --- потолок выразимого при folds=8: окно 41 бар (≈2 месяца) ---
    "d1 U13 maxexpr L40 S1 rb1;&universe=$u13$d1&lookbackBars=40&skipBars=1&rebalanceBars=1",
    "d1 U15 maxexpr L40 S1 rb1;&universe=$u15$d1&lookbackBars=40&skipBars=1&rebalanceBars=1",
    # --- «2−1»: месячный skip при 2-месячном окне, месячный ребаланс ---
    "d1 U13 2-1 L40 S20 rb21;&universe=$u13$d1&lookbackBars=40&skipBars=20&rebalanceBars=21",
    "d1 U15 2-1 L40 S20 rb21;&universe=$u15$d1&lookbackBars=40&skipBars=20&rebalanceBars=21"
)

Write-Host ("direction (b) 12-1 DAY_1: {0} конфигов, days={1} folds={2} holdout={3}" -f $cfg.Count, $Days, $Folds, $HoldoutFraction)
& (Join-Path $PSScriptRoot "research_oos70_portfolio.ps1") `
    -BaseUrl $BaseUrl -Days $Days -Folds $Folds -HoldoutFraction $HoldoutFraction `
    -ConfigCsv $cfg `
    -OutCsv (Join-Path $root "artifacts\p6_day1_$OutSuffix.csv") `
    -OutJson (Join-Path $root "artifacts\p6_day1_$OutSuffix.json") `
    -StatusFile (Join-Path $root "artifacts\p6_day1_$OutSuffix.status")
