# Research: WFA-валидация тикеров/таймфреймов (диверсификация портфеля).
#
# Цель: проверить OOS-edge у кандидатов на диверсификацию капитала.
#  - Акции GAZP/NVTK/PLZL/SBER (stockGrid SL%/TP%, leverage x5)
#  - CNYRUBF на старших таймфреймах (HOUR_1/DAY_1 ресемплинг, futuresGrid SL/TP в пунктах)
# CNYRUBF MINUTE_10 уже отклонён на 730д (OOS -80%), 730д/365д edge для фьючерса НЕ найден.
#
# Прогон на live-стеке (postgres+redis, java -jar --spring.mvc.async.request-timeout=3600000).
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 365,
    [int]$Folds = 6,
    [double]$Conf = 0.60,
    [double]$Leverage = 5.0,
    [string]$TickerCsv = "GAZP,NVTK,PLZL,SBER",
    [string]$Timeframe = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
$Tickers = $TickerCsv.Split(",").Where({ $_ })

function Resolve-EnvValue([string]$name, [string]$def = "") {
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}
$authUser = Resolve-EnvValue "AUTH_USER"
$authPassword = Resolve-EnvValue "AUTH_PASSWORD"
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body (@{ username = $authUser; password = $authPassword } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

$rows = @()
foreach ($t in $Tickers) {
    $url = "$BaseUrl/api/v1/backtest/$t/validate`?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf&leverage=$Leverage&loadHistory=false"
    if ($Timeframe) { $url += "&timeframe=$Timeframe" }

    # Для фьючерсного кандидата (CNYRUBF) leverage в query игнорируется:
    # сайзинг идёт riskPerTradePercent/futuresMaxContractsPerPosition (см. BacktestConfig/BT_*).

    # Логируем (полезно при долгом прогоне >60 мин).
    Write-Host ("WFA {0}: {1}" -f $t, $url)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 3600
        $sw.Stop()
        $rows += [pscustomobject]@{
            Ticker = $t; Robust = $r.robust; Consistency = $r.consistency
            OOS_RetPct = [math]::Round([double]$r.oosReturn * 100, 2); OOS_Sharpe = [math]::Round([double]$r.oosSharpe, 2)
            OOS_PF = [math]::Round([double]$r.oosProfitFactor, 2); OOS_Trades = $r.oosTrades
            Edge = $r.oosEdgeStatisticallySignificant; P_NoEdge = [math]::Round([double]$r.oosProbabilityOfNoEdge, 3)
            CI95 = "[{0:N1}; {1:N1}]" -f [double]$r.oosMeanTradeCI95Low, [double]$r.oosMeanTradeCI95High
            Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
        }
    } catch {
        $sw.Stop()
        try {
            $reLogin = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body (@{ username = $authUser; password = $authPassword } | ConvertTo-Json)
            $headers = @{ Authorization = "Bearer $($reLogin.accessToken)" }
            $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 3600
            $rows += [pscustomobject]@{
                Ticker = $t; Robust = $r.robust; Consistency = $r.consistency
                OOS_RetPct = [math]::Round([double]$r.oosReturn * 100, 2); OOS_Sharpe = [math]::Round([double]$r.oosSharpe, 2)
                OOS_PF = [math]::Round([double]$r.oosProfitFactor, 2); OOS_Trades = $r.oosTrades
                Edge = $r.oosEdgeStatisticallySignificant; P_NoEdge = [math]::Round([double]$r.oosProbabilityOfNoEdge, 3)
                CI95 = "[{0:N1}; {1:N1}]" -f [double]$r.oosMeanTradeCI95Low, [double]$r.oosMeanTradeCI95High
                Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
            }
        } catch {
            $rows += [pscustomobject]@{
                Ticker = $t; Robust = $null; Consistency = $null; OOS_RetPct = $null; OOS_Sharpe = $null
                OOS_PF = $null; OOS_Trades = $null; Edge = $null; P_NoEdge = $null; CI95 = $null; Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
            }
            Write-Host "  FAILED: $($_.Exception.Message)"
        }
    }
}

Write-Host ""
$rows | Format-Table -AutoSize
Write-Host "Done."