# Research: честная сетка риск-параметров на dev-сплите (WFA 730д, folds=8, conf 0.60).
#
# Вопрос, который эта сетка отвечает: можно ли получить higher returns просто
# увеличением риска? Ожидание по истории проекта - нет: risk-параметры масштабируют
# P&L и MDD одновременно, а OOS PF базы на 730д лежит около 0.92-1.65, то есть
# леверидж масштабирует монетку. Тот же калибровочный риск (30%/maxC 100) давал и
# -80.4% (baseline), и +193.6% (отобранный лидер) - разница в отборе, не в риске.
#
# Протокол (как у combo730, 2026-09-26):
#   1) WFA 730д folds=8 на каждом сочетании - это dev-часть, где подбора не было;
#   2) deployment-gate только для ЛУЧШЕГО сочетания - формальный вердикт
#      (OOS >= 100 сделок, edge, holdout, MC). Без этого PF на /validate остаётся
#      артефактом подбора (так развалились max-hold, ORB, time-direction).
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$Conf = 0.60,
    [string]$Profiles = "5:20,10:50,15:75,20:100,30:100",
    [switch]$SkipGate
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

$out = Join-Path $PSScriptRoot "riskgrid_wfa_${Days}d.csv"
if (Test-Path $out) { Remove-Item -LiteralPath $out -Force }
"risk_pct,max_contracts,secs,oos_ret_pct,oos_pf,oos_sharpe,oos_trades,consistency" | Set-Content -Path $out

Write-Host "== risk grid: ticker=$Ticker days=$Days folds=$Folds conf=$Conf profiles=$Profiles =="
$results = @()
foreach ($p in $Profiles.Split(",")) {
    $parts = $p.Split(":")
    $riskPct = [double]$parts[0]
    $maxC = [int]$parts[1]
    $u = "$BaseUrl/api/v1/backtest/$Ticker/validate`?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf" +
    "&riskPerTradePercent=$riskPct&futuresMaxContractsPerPosition=$maxC&loadHistory=false"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-WithAuth $u
        $sw.Stop()
        $secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
        $line = "{0},{1},{2},{3},{4},{5},{6},{7}" -f `
            $riskPct, $maxC, $secs,
            [math]::Round([double]$r.oosReturn * 100, 2), [math]::Round([double]$r.oosProfitFactor, 3),
            [math]::Round([double]$r.oosSharpe, 2), $r.oosTrades, $r.consistency
        $line | Add-Content -Path $out
        $results += [pscustomobject]@{ RiskPct = $riskPct; MaxC = $maxC; Row = $line; OosPf = [double]$r.oosProfitFactor }
        Write-Host $line
    } catch {
        $sw.Stop()
        Write-Host ("FAILED risk={0} maxC={1} ({2}s): {3}" -f $riskPct, $maxC, [math]::Round($sw.Elapsed.TotalSeconds, 0), $_.Exception.Message)
    }
}

Write-Host "== WFA grid done: $out =="
if ($SkipGate) { return }

$best = $results | Sort-Object -Property OosPf -Descending | Select-Object -First 1
if (-not $best) { Write-Host "нет успешных прогонов, gate пропущен"; return }
Write-Host ("== deployment-gate для лучшего профиля risk={0} maxC={1} (oosPF={2}) ==" -f $best.RiskPct, $best.MaxC, $best.OosPf)
$gateUrl = "$BaseUrl/api/v1/backtest/$Ticker/deployment-gate`?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf" +
"&riskPerTradePercent=$($best.RiskPct)&futuresMaxContractsPerPosition=$($best.MaxC)&loadHistory=false"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $g = Invoke-WithAuth $gateUrl
    $sw.Stop()
    $gateFile = Join-Path $PSScriptRoot "riskgrid_gate_best.json"
    $g | ConvertTo-Json -Depth 6 | Set-Content -Path $gateFile
    Write-Host ("verdict={0} liveAllowed={1} ({2}s) -> {3}" -f $g.verdict, $g.liveAllowed, [math]::Round($sw.Elapsed.TotalSeconds, 0), $gateFile)
} catch {
    $sw.Stop()
    Write-Host ("GATE FAILED ({0}s): {1}" -f [math]::Round($sw.Elapsed.TotalSeconds, 0), $_.Exception.Message)
}
