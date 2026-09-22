# Research: WFA-валидация фьючерсных перпетуалов MOEX (диверсификация, 730д).
#
# Проверка бессрочных контрактов (LASTDELDATE 2100, SECID == ticker — загрузка истории
# без склейки контрактов) на OOS-edge. Калибровочный риск-профиль research-сайзера
# (riskPerTradePercent=30&futuresMaxContractsPerPosition=100), futuresGrid (SL/TP в пунктах).
#
# История 730д + funding_history (SWAPRATE) донакачиваются через API до прогона:
#   GET /api/v1/backtest/{ticker}?days=730&loadHistory=true
#   GET /api/v1/backtest/{ticker}/funding-history?days=730
#
# Прогон на live-стеке (postgres+redis, java -jar --spring.mvc.async.request-timeout=3600000).
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$Conf = 0.60,
    [string]$RiskPct = "30",
    [string]$MaxC = "100",
    [string]$TickerCsv = "USDRUBF,EURRUBF,GLDRUBF,IMOEXF",
    [switch]$LoadHistory
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

foreach ($t in $Tickers) {
    if ($LoadHistory) {
        Write-Host "[$t] loading history (days=$Days)..."
        $null = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/backtest/$t?days=$Days&loadHistory=true" -Headers $headers -TimeoutSec 3600
        Write-Host "[$t] loading funding-history (days=$Days)..."
        $null = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/backtest/$t/funding-history?days=$Days" -Headers $headers -TimeoutSec 1800
    }
}

$rows = @()
foreach ($t in $Tickers) {
    $url = "$BaseUrl/api/v1/backtest/$t/validate`?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf&riskPerTradePercent=$RiskPct&futuresMaxContractsPerPosition=$MaxC&loadHistory=false"
    Write-Host ("WFA {0}: {1}" -f $t, $url)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 7200
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
            $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 7200
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