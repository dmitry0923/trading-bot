# Research: WFA-валидация Opening Range Breakout фильтра входа (365д, CNYRUBF).
#
# Гипотеза edge «иное время удержания» (2026-09-23): вход ТОЛЬКО в направлении
# пробоя дневного opening range (диапазон первых orbWindowBars баров дня).
# Реализовано как входной фильтр направления в LiveStrategyBacktestSignalGenerator
# (EntryFilters.orbDirection), query-оверрайды orbEnabled/orbWindowBars/
# orbStrictBreakout/orbBlockOnUnknown на /backtest /validate /deployment-gate.
#
# Калибровочный риск-профиль research-сайзера (riskPerTradePercent=30
# &futuresMaxContractsPerPosition=100), futuresGrid (SL/TP в пунктах).
# Прогон на live-стеке (postgres+redis, java -jar --spring.mvc.async.request-timeout=10800000).
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 365,
    [int]$Folds = 6,
    [double]$Conf = 0.60,
    [string]$RiskPct = "30",
    [string]$MaxC = "100",
    [string]$Ticker = "CNYRUBF",
    [string]$OrbConfigCsv = "window=6&strict=true;window=12&strict=true;window=24&strict=true;window=6&strict=false;window=12&strict=false;window=36&strict=false"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"

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

$base = "$BaseUrl/api/v1/backtest/$Ticker/validate`?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf&riskPerTradePercent=$RiskPct&futuresMaxContractsPerPosition=$MaxC&loadHistory=false"

Write-Host ("baseline WFA: {0}" -f $base)
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$r = Invoke-RestMethod -Method Get -Uri $base -Headers $headers -TimeoutSec 7200
$sw.Stop()
$rows = @([pscustomobject]@{
        Config = "baseline (off)"; Window = ""; Strict = ""
        Consistency = $r.consistency; OOS_RetPct = [math]::Round([double]$r.oosReturn * 100, 2)
        OOS_PF = [math]::Round([double]$r.oosProfitFactor, 2); OOS_Trades = $r.oosTrades
        P_NoEdge = [math]::Round([double]$r.oosProbabilityOfNoEdge, 3); Robust = $r.robust
        Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    })

foreach ($cfg in $OrbConfigCsv.Split(";").Where({ $_ })) {
    $params = @{}
    foreach ($kv in $cfg.Split("&").Where({ $_ })) {
        $k, $v = $kv.Split("=", 2)
        $params[$k] = $v
    }
    $window = $params["window"]
    $strict = $params["strict"]
    $url = "$base&orbEnabled=true&orbWindowBars=$window&orbStrictBreakout=$strict&orbBlockOnUnknown=true"
    Write-Host ("WFA orb window={0} strict={1}: {2}" -f $window, $strict, $url)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 7200
        $sw.Stop()
    } catch {
        $sw.Stop()
        $headers = Get-Headers
        $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 7200
    }
    $rows += [pscustomobject]@{
        Config = "orb w$window strict=$strict"; Window = $window; Strict = $strict
        Consistency = $r.consistency; OOS_RetPct = [math]::Round([double]$r.oosReturn * 100, 2)
        OOS_PF = [math]::Round([double]$r.oosProfitFactor, 2); OOS_Trades = $r.oosTrades
        P_NoEdge = [math]::Round([double]$r.oosProbabilityOfNoEdge, 3); Robust = $r.robust
        Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    }
}

Write-Host ""
$rows | Format-Table -AutoSize
Write-Host "Done."