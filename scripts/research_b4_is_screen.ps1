# Research b4-IS (PLAN-001 §5.2, направление «(б)»): IS-скрининг юниверса 730д на LIVE-риске.
#
# Цель: отсечь заведомо убыточные инструменты ДО дорогих WFA 730д/folds=8 (~50-60 мин/тикер),
# чтобы гонять WFA только по отобранным. Это ДИАГНОСТИКА отбора, а не гейт §4: IS-метрики
# считаются на всей истории (включая «будущее» OOS-окна WFA), т.е. завышены по сравнению с OOS.
#
# Механизм: POST /api/v1/backtest/panel — параллельные независимые бэктесты (каждый тикер со
# СВОИМ initialCapital, общего капитала/лимита позиций нет). Батчи по 7 тикеров ограничивают
# пиковую память сервера (-Xmx2g).
#
# Параметры сигналов — дефолты bt.* (как в a3): conf 0.60, SL/TP/риск из application.yml,
# фильтры off. Для акций leverage/capitalSlice не переопределяются (дефолты bt.*).
#
# Использование:
#   1) поднять сервер: java -Xmx2g -jar build/libs/trading-bot-2.0.0.jar --spring.mvc.async.request-timeout=10800000
#   2) .\scripts\research_b4_is_screen.ps1            # оба батча
#   3) .\scripts\research_b4_is_screen.ps1 -Batch 1   # только батч 1 (акции)
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 730,
    [double]$Conf = 0.60,
    [string]$Timeframe = "MINUTE_10",
    [int]$Batch = 0,
    [string]$OutDir = "",
    [int]$TimeoutSec = 14400
)

$ErrorActionPreference = "Stop"
[System.Threading.Thread]::CurrentThread.CurrentCulture = [System.Globalization.CultureInfo]::InvariantCulture
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
if (-not $OutDir) { $OutDir = Join-Path $root "artifacts" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Resolve-EnvValue([string]$name, [string]$def = "") {
    if (-not (Test-Path $envPath)) { return $def }
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}

$script:accessToken = $null
function Get-Auth {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" `
        -Body (@{ username = (Resolve-EnvValue "AUTH_USER" "admin"); password = (Resolve-EnvValue "AUTH_PASSWORD" "admin123456") } | ConvertTo-Json)
}

function Invoke-PostWithAuth([string]$uri, [hashtable]$body, [int]$timeout) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        if (-not $script:accessToken) { $script:accessToken = (Get-Auth).accessToken }
        try {
            return Invoke-RestMethod -Method Post -Uri $uri -ContentType "application/json" `
                -Headers @{ Authorization = "Bearer $script:accessToken" } `
                -Body ($body | ConvertTo-Json -Depth 6) -TimeoutSec $timeout
        } catch {
            if ($_.Exception.Response.StatusCode.value__ -eq 401) {
                Write-Host "  token expired, re-login (attempt $attempt)"
                $script:accessToken = $null
                continue
            }
            throw
        }
    }
    throw "401 x3: $uri"
}

$hb = Join-Path $OutDir "b4_is_screen.heartbeat"
function Write-Hb([string]$msg) {
    Set-Content -Path $hb -Value ("t={0} {1}" -f [DateTimeOffset]::UtcNow.ToUnixTimeSeconds(), $msg)
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg)
}

# Юниверс: 14 тикеров с полной историей ~2 года MINUTE_10 (покрытие из БД 2026-10-18).
# Si/RI исключены (2.5 мес истории — недостаточно для 730д).
$batches = @(
    @{ Name = "stocks"; Tickers = @("GAZP", "SBER", "NVTK", "PLZL", "MGNT", "LKOH", "VTBR") },
    @{ Name = "futures"; Tickers = @("ROSN", "TATN", "CNYRUBF", "USDRUBF", "EURRUBF", "GLDRUBF", "IMOEXF") }
)

$csv = Join-Path $OutDir "b4_is_panel_${Days}d.csv"
if (-not (Test-Path $csv)) { "batch,ticker,secs,ret_pct,pf,sharpe,mdd_pct,trades,win_pct,passable,prob_no_edge,edge_sig,ci95_low,ci95_high" | Set-Content -Path $csv }

$selected = $batches
if ($Batch -ge 1) { $selected = @($batches[$Batch - 1]) }

Write-Host "== b4 IS screen: days=$Days conf=$Conf timeframe=$Timeframe (batch " -NoNewline
Write-Host (($selected | ForEach-Object { $_.Name }) -join ", ") -NoNewline
Write-Host ") live-risk defaults =="
$startAll = Get-Date

foreach ($b in $selected) {
    Write-Hb "START batch=$($b.Name) tickers=$($b.Tickers -join ',')"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-PostWithAuth -uri "$BaseUrl/api/v1/backtest/panel" -timeout $TimeoutSec -body @{
        tickers                        = $b.Tickers
        days                           = $Days
        timeframe                      = $Timeframe
        adaptiveConfidenceThreshold    = $Conf
        loadHistory                    = $false
    }
    $sw.Stop()
    $secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)

    $json = Join-Path $OutDir "b4_is_panel_$($b.Name)_${Days}d.json"
    $r | ConvertTo-Json -Depth 8 | Set-Content -Path $json

    foreach ($t in $r.results) {
        "{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10},{11},{12},{13}" -f `
            $b.Name, $t.ticker, $secs,
            [math]::Round([double]$t.totalReturn * 100, 3), [math]::Round([double]$t.profitFactor, 4),
            [math]::Round([double]$t.sharpeRatio, 3), [math]::Round([double]$t.maxDrawdown * 100, 2),
            $t.totalTrades, [math]::Round([double]$t.winRate * 100, 1), $t.passable,
            [math]::Round([double]$t.probabilityOfNoEdge, 4), $t.edgeStatisticallySignificant,
            [math]::Round([double]$t.meanTradeCI95Low, 2), [math]::Round([double]$t.meanTradeCI95High, 2) `
            | Add-Content -Path $csv
    }

    Write-Host ("== batch={0} secs={1} json={2} ==" -f $b.Name, $secs, $json)
    $r.results | Select-Object ticker,
        @{ n = "ret%"; e = { [math]::Round([double]$_.totalReturn * 100, 2) } },
        @{ n = "PF"; e = { [math]::Round([double]$_.profitFactor, 3) } },
        @{ n = "Sharpe"; e = { [math]::Round([double]$_.sharpeRatio, 2) } },
        @{ n = "MDD%"; e = { [math]::Round([double]$_.maxDrawdown * 100, 2) } },
        totalTrades,
        @{ n = "P(noEdge)"; e = { [math]::Round([double]$_.probabilityOfNoEdge, 3) } } `
        | Format-Table -AutoSize
    Write-Hb "DONE batch=$($b.Name) secs=$secs"
}

$elapsed = [math]::Round(((Get-Date) - $startAll).TotalMinutes, 1)
Write-Host "== b4 IS screen завершён за $elapsed мин; CSV: $csv =="
Write-Hb "ALL DONE mins=$elapsed"
