# Research: догрузка истории свечей до 730 дней (variant 2 - расширение вселенной).
#
# Зачем. Инвентаризация БД (2026-09-29) показала: 9 инструментов имеют полные
# 2 года MINUTE_10, но MGNT/LKOH/ROSN/TATN были загружены только с 2026-06-11
# (~8.6k свечей, 3.5 месяца). Проверка MOEX ISS напрямую: за сентябрь 2024 все
# четыре отдают по 500 строк на страницу, т.е. ограничение не в бирже, а в
# параметре days предыдущих прогонов (HistoricalDataLoader считает now()-days).
#
# Загрузка идёт через штатный HistoricalDataLoader (endpoint /backtest/{ticker}
# с loadHistory=true), поэтому пагинация ISS (500/страницу, до 200k) и
# сохранение в TimescaleDB - ровно тот же код, что и на всех прошлых прогонах.
# Побочно /backtest отдаёт IS-метрики на полной истории - отдельный вызов не
# нужен, они попадают в CSV как предварительный (IS, не OOS) скрининг.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Tickers = "MGNT,LKOH,ROSN,TATN",
    [int]$Days = 730,
    [switch]$LoadOnly
)

$ErrorActionPreference = "Stop"
[System.Threading.Thread]::CurrentThread.CurrentCulture = [System.Globalization.CultureInfo]::InvariantCulture
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"

function Resolve-EnvValue([string]$name, [string]$def = "") {
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}

$script:accessToken = $null
function Get-Auth {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" `
        -Body (@{ username = (Resolve-EnvValue "AUTH_USER"); password = (Resolve-EnvValue "AUTH_PASSWORD") } | ConvertTo-Json)
}

function Invoke-WithAuth($uri, [int]$TimeoutSec = 21600) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        if (-not $script:accessToken) { $script:accessToken = (Get-Auth).accessToken }
        try {
            return Invoke-RestMethod -Method Get -Uri $uri -Headers @{ Authorization = "Bearer $script:accessToken" } -TimeoutSec $TimeoutSec
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

$out = Join-Path $PSScriptRoot "backfill_history_${Days}d.csv"
if (Test-Path $out) { Remove-Item -LiteralPath $out -Force }
"ticker,secs,ret_pct,sharpe,mdd_pct,pf,win_pct,trades,probability_no_edge" | Set-Content -Path $out

Write-Host "== backfill: tickers=$Tickers days=$Days =="
foreach ($t in $Tickers.Split(",")) {
    $t = $t.Trim()
    if (-not $t) { continue }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Write-Host "-> $t ..."
    # endpoint сам вызывает HistoricalDataLoader.loadAndSave(ticker, days)
    $r = Invoke-WithAuth "$BaseUrl/api/v1/backtest/$t`?days=$Days&loadHistory=true"
    $sw.Stop()
    $ret = [math]::Round([double]$r.totalReturn, 4)
    $sharpe = [math]::Round([double]$r.sharpeRatio, 3)
    $mdd = [math]::Round([double]$r.maxDrawdown, 4)
    $pf = [math]::Round([double]$r.profitFactor, 3)
    $win = [math]::Round([double]$r.winRate, 4)
    $noEdge = [math]::Round([double]$r.probabilityOfNoEdge, 3)
    $line = "$t,$($sw.Elapsed.TotalSeconds.ToString('F0')),$ret,$sharpe,$mdd,$pf,$win,$($r.totalTrades),$noEdge"
    Add-Content -Path $out -Value $line
    Write-Host "   done in $($sw.Elapsed.TotalSeconds.ToString('F0'))s : IS ret=$ret% PF=$pf trades=$($r.totalTrades) noEdge=$noEdge"
}

Write-Host "== backfill report saved: $out =="
if (-not $LoadOnly) {
    Write-Host "Дальше: /validate с folds=8 по этим тикерам (research_wfa_diversification.ps1)."
}
