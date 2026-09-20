# Research: 730-дневная WFA-валидация детерминированной стратегии CNYRUBF
# (MINUTE_10, conf 0.60, folds=8) — цель «100% в год» (пункт 1 плана).
#
# История 730д в БД уже есть (candles 2024-09-19..2026-09-19, 46k MINUTE_10;
# funding_history 509 дат клерингов). Прогон на live-стеке (postgres+redis).
#
# Последовательность:
#   1) IS base: /backtest?days=730&loadHistory=false
#   2) WFA OOS: /validate?days=730&folds=8&adaptiveConfidenceThreshold=0.60&
#      riskPerTradePercent=30&futuresMaxContractsPerPosition=100
#      (калибровочный риск-профиль research-сайзера; SL/TP подбирает futuresGrid)
#
# Требования: приложение на $BaseUrl с --spring.mvc.async.request-timeout>=3600000.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$Conf = 0.60,
    [string]$RiskPct = "30",
    [string]$MaxC = "100"
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
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body (@{ username = $authUser; password = $authPassword } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

# ---- [1/2] IS base ----
Write-Host "[1/2] IS base: /backtest?days=$Days&loadHistory=false"
$islab = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $base = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/backtest/$Ticker`?days=$Days&loadHistory=false" -Headers $headers -TimeoutSec 3600
    $islab.Stop()
    Write-Host ("  IS: passable={0} totalReturn={1:P1} sharpe={2} maxDrawdown={3:P1} trades={4} pf={5} edge={6}" -f `
            $base.passable, $base.totalReturn, $base.sharpeRatio, $base.maxDrawdown, $base.totalTrades, $base.profitFactor, $base.edgeStatisticallySignificant)
    Write-Host ("     elapsed {0}s" -f [math]::Round($islab.Elapsed.TotalSeconds, 0))
} catch {
    $islab.Stop()
    Write-Host "  IS FAILED: $($_.Exception.Message)"
}

# ---- [2/2] WFA OOS ----
$url = "$BaseUrl/api/v1/backtest/$Ticker/validate`?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf&riskPerTradePercent=$RiskPct&futuresMaxContractsPerPosition=$MaxC&loadHistory=false"
Write-Host "[2/2] WFA OOS: $url"
$wf = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $wfa = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 3600
    $wf.Stop()
    Write-Host ("  WFA: robust={0} consistency={1} oosRet={2:P1} oosSharpe={3} oosPF={4} oosTrades={5} edge={6} p(noEdge)={7}" -f `
            $wfa.robust, $wfa.consistency, $wfa.oosReturn, $wfa.oosSharpe, $wfa.oosProfitFactor, $wfa.oosTrades, $wfa.oosEdgeStatisticallySignificant, $wfa.oosProbabilityOfNoEdge)
    Write-Host ("     CI95 {0:N2}..{1:N2}; elapsed {2}s" -f $wfa.oosMeanTradeCI95Low, $wfa.oosMeanTradeCI95High, [math]::Round($wf.Elapsed.TotalSeconds, 0))
} catch {
    $wf.Stop()
    Write-Host "  WFA FAILED after $([math]::Round($wf.Elapsed.TotalSeconds,0))s: $($_.Exception.Message)"
}
Write-Host "Done."