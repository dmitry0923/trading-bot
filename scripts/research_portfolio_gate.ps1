# Research: portfolio deployment-gate (`docs/26` P1 шаг 4 плана 18_10_2026).
#
# Зачем: у класса cross-sectional momentum + carry не было своего gate-эндпоинта (тикерный
# `/backtest/{ticker}/deployment-gate` портфель не проверяет). Здесь прогоняется НОВЫЙ
# портфельный гейт `/api/v1/portfolio/momentum/deployment-gate`, который консолидирует:
#   1. dev-backtest (первые 1-holdout истории);
#   2. walk-forward на dev-части (чистые OOS-фолды);
#   3. одноразовый holdout (последние holdout истории);
#   4. Monte Carlo по доходностям holdout-пути;
#   5. значимость edge (P(noEdge)<0.05 и CI95 w/o zero);
#   6. consistency>=0.60;
#   7. >=100 OOS-сделок;
#   8. MDD<=40%;
#   9. структурную вместимость фолдов (урок §11.9: folds=8 при lookback=249 на DAY_1
#      вырождается в ~4 сделки/фолд — «красивый PF» на вырожденном фолде не прохождение).
#
# Прогоняются замороженные конфиги класса (без подбора — §6 PLAN-001):
#   - U8DAY 12-0     — лучший PASS-кандидат §11.7 (L249/S0/rb21, 8 акций DAY_1);
#   - U13DAY vt30    — сильнейший единичный результат §11.3 (широкий юниверс, DAY_1);
#   - U15DAY vt30    — U13 + SBERF/GAZPF momentum-only (DAY_1);
#   - U5MIN 1-1      — 5 перпетуалов, MINUTE_10, штатный lookback (12-1 на минутах).
#
# Настройки также заморожены: folds=8, holdout=0.30, researchMode=true (LIVE недостижим).
#
# Запуск (в фоне, с heartbeat):
#   Start-Job -ScriptBlock { & .\scripts\research_portfolio_gate.ps1 }
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Folds = 8,
    [double]$HoldoutFraction = 0.30,
    [int]$Simulations = 2000,
    [int]$TimeoutSec = 7200,
    [string]$OutDir = "artifacts",
    [string]$StatusFile = "",
    [string[]]$Only = @()
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $StatusFile) { $StatusFile = Join-Path $env:TEMP "portfolio-gate-$stamp.status" }
if (-not [System.IO.Path]::IsPathRooted($OutDir)) { $OutDir = Join-Path $root $OutDir }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Write-Status([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
    Write-Host $line
    Add-Content -LiteralPath $StatusFile -Value $line -Encoding UTF8
}

function Resolve-EnvValue([string]$name, [string]$def = "") {
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}
$authUser = Resolve-EnvValue "AUTH_USER"
$authPassword = Resolve-EnvValue "AUTH_PASSWORD"

function Get-Headers {
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body (@{ username = $authUser; password = $authPassword } | ConvertTo-Json)
    return @{ Authorization = "Bearer $($login.accessToken)" }
}
$headers = Get-Headers

$gateBase = "$BaseUrl/api/v1/portfolio/momentum/deployment-gate"

# Замороженные конфиги класса (§11.3/§11.4/§11.6/§11.7/§11.8) — юниверс + ТФ + горизонт.
# `days` подобраны так, чтобы вмещать нужную историю для выбранного ТФ; параметры
# (lookback/skip/rebalance/vol) взяты из предрегистрации соответственного прогона
# БЕЗ изменений. Единственное послабление — folds усекается под вместимость dev-части
# (ответ отдаёт `folds` vs `foldsRequested`), т.к. folds — метод проверки, не подбор.
$configs = @(
    [pscustomobject]@{ Name = "U8DAY_12-0";  Universe = "GAZP,LKOH,MGNT,NVTK,PLZL,ROSN,SBER,TATN"; Timeframe = "DAY_1";    Days = 3220; Lookback = 249; Skip = 0; Rebalance = 21; LongCount = 2; ShortCount = 4 },
    [pscustomobject]@{ Name = "U13DAY_vt30"; Universe = "CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF,GAZP,LKOH,MGNT,NVTK,PLZL,ROSN,SBER,TATN"; Timeframe = "DAY_1"; Days = 1460; Lookback = 22; Skip = 3; Rebalance = 4; LongCount = 2; ShortCount = 4; VolTargetEnabled = $true; VolTargetAnnualized = 0.30; VolTargetBarsPerYear = 78; VolLookbackBars = 3 },
    [pscustomobject]@{ Name = "U15DAY_vt30"; Universe = "CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF,GAZP,LKOH,MGNT,NVTK,PLZL,ROSN,SBER,TATN,SBERF,GAZPF"; Timeframe = "DAY_1"; Days = 1460; Lookback = 22; Skip = 3; Rebalance = 4; LongCount = 2; ShortCount = 4; VolTargetEnabled = $true; VolTargetAnnualized = 0.30; VolTargetBarsPerYear = 78; VolLookbackBars = 3 },
    [pscustomobject]@{ Name = "U5MIN_1-1";   Universe = "CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF"; Timeframe = "MINUTE_10"; Days = 730;  Lookback = 21; Skip = 1; Rebalance = 1; LongCount = 1; ShortCount = 1 }
)

if ($Only.Count -gt 0) { $configs = @($configs | Where-Object { $Only -contains $_.Name }) }

# ВНИМАНИЕ (harness-грабли, AGENTS.md): Spring берёт ПЕРВОЕ значение дублированного
# query-параметра. Собираем из одного `[ordered]`-хеша и сериализуем ровно один раз.
function Run-Gate([pscustomobject]$cfg) {
    $p = [ordered]@{
        universe        = $cfg.Universe
        timeframe       = $cfg.Timeframe
        days            = $cfg.Days
        lookbackBars    = $cfg.Lookback
        skipBars        = $cfg.Skip
        rebalanceBars   = $cfg.Rebalance
        longCount       = $cfg.LongCount
        shortCount      = $cfg.ShortCount
        folds           = $Folds
        holdoutFraction = $HoldoutFraction
        simulations     = $Simulations
        researchMode    = "true"
    }
    if ($cfg.PSObject.Properties.Name -contains "VolTargetEnabled") {
        $p["volScalingEnabled"] = "true"
        $p["volTargetEnabled"] = "true"
        $p["volTargetAnnualized"] = $cfg.VolTargetAnnualized
        $p["volTargetBarsPerYear"] = $cfg.VolTargetBarsPerYear
        $p["volLookbackBars"] = $cfg.VolLookbackBars
    }
    $qs = ($p.Keys | ForEach-Object { "$_=$($p[$_])" }) -join "&"
    $url = "$gateBase`?$qs"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $resp = Invoke-Get $url
    $sw.Stop()

    $agg = $resp.aggregate
    $ho = $resp.holdout
    $dev = $resp.devBacktest
    $cap = @($resp.foldCapacity)
    $deg = @($cap | Where-Object { $_.degenerate })
    $checks = @($resp.checks | Where-Object { -not $_.passed } | ForEach-Object { $_.key })

    $row = [pscustomobject]@{
        Config        = $cfg.Name
        Timeframe     = $cfg.Timeframe
        Bars          = $resp.bars
        DevBars       = $resp.devBars
        HoldoutBars   = $resp.holdoutBars
        RanFolds      = $resp.folds
        ReqFolds      = $resp.foldsRequested
        Status        = $resp.status
        AllChecks     = [bool]$resp.allChecksPassed
        FailedChecks  = ($checks -join "|")
        DegenerateFolds = $deg.Count
        DevPf         = [math]::Round([double]$dev.profitFactor, 3)
        DevTrades     = [int]$dev.totalTrades
        AggPf         = [math]::Round([double]$agg.profitFactor, 3)
        AggRetPct     = [math]::Round([double]$agg.totalReturn * 100, 2)
        AggSharpe     = [math]::Round([double]$agg.sharpeRatio, 2)
        AggMddPct     = [math]::Round([double]$agg.maxDrawdown * 100, 2)
        OosTrades     = [int]$agg.totalTrades
        Consistency   = [math]::Round([double]$resp.consistency, 3)
        PNoEdge       = [math]::Round([double]$agg.probabilityOfNoEdge, 4)
        Ci95Low       = [math]::Round([double]$agg.meanTradeCI95Low, 2)
        Ci95High      = [math]::Round([double]$agg.meanTradeCI95High, 2)
        HoPf          = [math]::Round([double]$ho.profitFactor, 3)
        HoRetPct      = [math]::Round([double]$ho.totalReturn * 100, 2)
        HoTrades      = [int]$ho.totalTrades
        McP5          = if ($resp.monteCarlo) { [math]::Round([double]$resp.monteCarlo.p5Return, 4) } else { $null }
        McRobust      = if ($resp.monteCarlo) { [bool]$resp.monteCarlo.mcRobust } else { $null }
        Secs          = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    }
    Write-Status ("  {0,-14} status={1,-14} folds={2}/{3} devPF={4,-5} oosPF={5,-5} trades={6,-4} hoPF={7,-5} deg={8} ({9}s)" -f `
            $cfg.Name, $row.Status, $row.RanFolds, $row.ReqFolds, $row.DevPf, $row.AggPf, $row.OosTrades, $row.HoPf, $row.DegenerateFolds, $row.Secs)
    return [pscustomobject]@{ Row = $row; Raw = $resp }
}

function Invoke-Get([string]$url) {
    try {
        return Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec $TimeoutSec
    } catch {
        Write-Status ("retry after re-auth: {0}" -f $_.Exception.Message)
        Start-Sleep -Seconds 10
        $script:headers = Get-Headers
        return Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec $TimeoutSec
    }
}

Write-Status ("start: configs={0} folds={1} holdout={2}" -f ($configs.Name -join ","), $Folds, $HoldoutFraction)
Write-Status ("status file: {0}" -f $StatusFile)

$results = @()
foreach ($cfg in $configs) { $results += Run-Gate $cfg }

$rows = @($results | ForEach-Object { $_.Row })
$csv = Join-Path $OutDir "portfolio_gate.csv"
$json = Join-Path $OutDir "portfolio_gate.json"
$rows | Export-Csv -LiteralPath $csv -NoTypeInformation -Encoding UTF8
@{
    protocol = "portfolio momentum deployment-gate (docs/26 P1 shag 4)"
    folds = $Folds
    holdoutFraction = $HoldoutFraction
    simulations = $Simulations
    researchMode = $true
    results = @($results | ForEach-Object { @{ row = $_.Row; checks = $_.Raw.checks; foldCapacity = $_.Raw.foldCapacity } })
    generatedAt = (Get-Date).ToString("s")
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $json -Encoding UTF8

Write-Status ("csv: {0}" -f $csv)
Write-Status ("json: {0}" -f $json)
$rows | Format-Table -AutoSize | Out-String | Write-Status
