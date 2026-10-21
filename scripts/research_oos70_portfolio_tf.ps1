# Шаг 3 плана 18_10_2026 (P1): репликация carry/momentum-портфеля на СТАРШИХ таймфреймах
# (HOUR_1/DAY_1). Вердикт — по holdout-гейтам §4 (OOS PF >= 1.30, P(noEdge) < 0.05,
# >= 30 OOS-сделок, dev-consistency >= 0.60), как в scripts/research_oos70_portfolio.ps1.
#
# ПРЕДРЕГИСТРАЦИЯ ПЕРЕСЧЁТА БАРОВ (зафиксирована до прогона):
#   Коэффициент конвертации MINUTE_10 -> старший ТФ берётся из ФАКТИЧЕСКОГО отношения
#   баров на торговый день (замер по U5 в candles, docs/26 §11.2):
#     MINUTE_10 78 баров/тикер/день, HOUR_1 14.2 -> коэффициент 5.5
#     MINUTE_10 78 баров/тикер/день, DAY_1 1     -> коэффициент 78
#   Баровые параметры замороженных конфигов делятся на коэффициент и округляются до
#   целого (минимум 1). Параметры в ДНЯХ (carryLookbackDays) не пересчитываются.
#   Дополнительно гоняется «наивный» перенос (те же числа баров, что на MINUTE_10) —
#   он структурно неисполним: dev-WFA требует >= folds*(lookback + rebalance + 2) баров,
#   чего на старших ТФ нет; это контроль, а не кандидат.
#
# Запуск: .\scripts\research_oos70_portfolio_tf.ps1
param(
    [string]$OutSuffix = "step3b",
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$HoldoutFraction = 0.30
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$u5 = "CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF"
$u13 = "$u5,GAZP,LKOH,MGNT,NVTK,PLZL,ROSN,SBER,TATN"
$u7 = "$u5,SBERF,GAZPF"
$u15 = "$u13,SBERF,GAZPF"

# timeeq-параметры (коэффициенты 5.5 и 78)
$h1 = "&timeframe=HOUR_1&lookbackBars=305"
$d1 = "&timeframe=DAY_1&lookbackBars=22"

$cfg = @(
    # --- U5: наивный перенос (контроль) ---
    "s3 U5 H1 naive-bars L1S1 cw1;&timeframe=HOUR_1&rebalanceBars=210&carryWeight=1",
    "s3 U5 D1 naive-bars L1S1 cw1;&timeframe=DAY_1&rebalanceBars=210&carryWeight=1",
    # --- U5: timeeq ---
    "s3 U5 H1 timeeq L1S1 cw1;$h1&rebalanceBars=38&carryWeight=1",
    "s3 U5 H1 timeeq mom L1S1;$h1&rebalanceBars=38",
    "s3 U5 H1 timeeq L2S3 vt15;$h1&longCount=2&shortCount=3&rebalanceBars=38&volScalingEnabled=true&volLookbackBars=76&volTargetEnabled=true&volTargetAnnualized=0.15",
    "s3 U5 D1 timeeq L1S1 cw1;$d1&rebalanceBars=3&carryWeight=1",
    "s3 U5 D1 timeeq mom L1S1;$d1&rebalanceBars=3",
    "s3 U5 D1 timeeq L2S3 vt15;$d1&longCount=2&shortCount=3&rebalanceBars=3&volScalingEnabled=true&volLookbackBars=5&volTargetEnabled=true&volTargetAnnualized=0.15",
    # --- U7 (момент+carry, перпетуалы с funding) ---
    "s3 U7 H1 timeeq L1S1 cw1;&universe=$u7$h1&rebalanceBars=38&carryWeight=1",
    "s3 U7 D1 timeeq L1S1 cw1;&universe=$u7$d1&rebalanceBars=3&carryWeight=1",
    # --- U13: наивный перенос (контроль) + timeeq ---
    "s3 U13 H1 naive-bars L2S4 vt30;&universe=$u13&timeframe=HOUR_1&rebalanceBars=300&longCount=2&shortCount=4&volScalingEnabled=true&volLookbackBars=210&volTargetEnabled=true&volTargetAnnualized=0.30",
    "s3 U13 D1 naive-bars L2S4 vt30;&universe=$u13&timeframe=DAY_1&rebalanceBars=300&longCount=2&shortCount=4&volScalingEnabled=true&volLookbackBars=210&volTargetEnabled=true&volTargetAnnualized=0.30",
    "s3 U13 H1 timeeq L2S4 vt30;&universe=$u13$h1&longCount=2&shortCount=4&rebalanceBars=55&volScalingEnabled=true&volLookbackBars=38&volTargetEnabled=true&volTargetAnnualized=0.30",
    "s3 U13 D1 timeeq L2S4 vt30;&universe=$u13$d1&longCount=2&shortCount=4&rebalanceBars=4&volScalingEnabled=true&volLookbackBars=3&volTargetEnabled=true&volTargetAnnualized=0.30",
    # --- U15 (лучший MINUTE_10-кандидат шага 2) ---
    "s3 U15 H1 timeeq L2S4 vt30;&universe=$u15$h1&longCount=2&shortCount=4&rebalanceBars=55&volScalingEnabled=true&volLookbackBars=38&volTargetEnabled=true&volTargetAnnualized=0.30",
    "s3 U15 H1 timeeq L1S1 cw1;&universe=$u15$h1&rebalanceBars=38&carryWeight=1",
    "s3 U15 D1 timeeq L2S4 vt30;&universe=$u15$d1&longCount=2&shortCount=4&rebalanceBars=4&volScalingEnabled=true&volLookbackBars=3&volTargetEnabled=true&volTargetAnnualized=0.30",
    "s3 U15 D1 timeeq L1S1 cw1;&universe=$u15$d1&rebalanceBars=3&carryWeight=1"
)

Write-Host ("step 3: {0} конфигов, days={1} folds={2} holdout={3}" -f $cfg.Count, $Days, $Folds, $HoldoutFraction)
& (Join-Path $PSScriptRoot "research_oos70_portfolio.ps1") `
    -BaseUrl $BaseUrl -Days $Days -Folds $Folds -HoldoutFraction $HoldoutFraction `
    -ConfigCsv $cfg `
    -OutCsv (Join-Path $root "artifacts\p1_oos70_portfolio_$OutSuffix.csv") `
    -OutJson (Join-Path $root "artifacts\p1_oos70_portfolio_$OutSuffix.json") `
    -StatusFile (Join-Path $root "artifacts\p1_oos70_portfolio_$OutSuffix.status")
