# Research: WFA-калибровка max-hold (время удержания) на CNYRUBF MINUTE_10.
#
# Цель: проверить, даёт ли принудительный выход по числу баров удержания (max-hold)
# устойчивый OOS-edge, когда SL/TP (300/600, широкие) не сработали за долгий горизонт.
# Baseline (maxHoldBars не задан = 0/off) — позиция держится до SL/TP месяцами.
#
# Запуск на live-стеке: --spring.mvc.async.request-timeout=3600000.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 365,
    [int]$Folds = 6,
    [double]$Conf = 0.60,
    [string]$Mode = "WFA"
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

# Сетка max-hold в барах MINUTE_10: 30 (5ч) / 46 (1д) / 92 (2д) / 184 (4д) / 368 (8д) / 736 (16д)
# + baseline (maxHoldBars не передаётся = off).
$holdBars = @(30, 46, 92, 184, 368, 736)
if ($Mode -eq "IS") { $holdBars = @(30, 92, 368, 736) }

$ticker = "CNYRUBF"
$rows = @()

foreach ($h in $holdBars) {
    $url = "$BaseUrl/api/v1/backtest/$ticker/validate`?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf&maxHoldBars=$h&loadHistory=false"
    Write-Host ("WFA {0} (maxHoldBars={1}): {2}" -f $ticker, $h, $url)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 3600
        $sw.Stop()
        $rows += [pscustomobject]@{
            maxHoldBars = $h
            OOS_RetPct = [math]::Round([double]$r.oosReturn * 100, 2)
            OOS_PF = [math]::Round([double]$r.oosProfitFactor, 2)
            OOS_Sharpe = [math]::Round([double]$r.oosSharpe, 2)
            OOS_Trades = $r.oosTrades
            Consistency = [math]::Round([double]$r.consistency, 3)
            Robust = $r.robust
            Edge = $r.oosEdgeStatisticallySignificant
            P_NoEdge = [math]::Round([double]$r.oosProbabilityOfNoEdge, 3)
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
                maxHoldBars = $h; OOS_RetPct = [math]::Round([double]$r.oosReturn * 100, 2)
                OOS_PF = [math]::Round([double]$r.oosProfitFactor, 2); OOS_Sharpe = [math]::Round([double]$r.oosSharpe, 2)
                OOS_Trades = $r.oosTrades; Consistency = [math]::Round([double]$r.consistency, 3)
                Robust = $r.robust; Edge = $r.oosEdgeStatisticallySignificant
                P_NoEdge = [math]::Round([double]$r.oosProbabilityOfNoEdge, 3)
                CI95 = "[{0:N1}; {1:N1}]" -f [double]$r.oosMeanTradeCI95Low, [double]$r.oosMeanTradeCI95High
                Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
            }
        } catch {
            $rows += [pscustomobject]@{ maxHoldBars = $h; OOS_RetPct = $null; OOS_PF = $null; OOS_Sharpe = $null; OOS_Trades = $null; Consistency = $null; Robust = $null; Edge = $null; P_NoEdge = $null; CI95 = $null; Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0) }
            Write-Host "  FAILED: $($_.Exception.Message)"
        }
    }
}

Write-Host ""
$rows | Format-Table -AutoSize
Write-Host "Done."