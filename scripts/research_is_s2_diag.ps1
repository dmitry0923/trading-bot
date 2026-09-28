# Диагностика S2 (VWAP-отклонение в ATR на GLDRUBF): почему 0 сделок.
#
# Конфигурация S2 из ТЗ противоречива сама по себе:
#   * порог = |close - VWAP| >= 1.2 * ATR14  (нужно ДАЛЬКОЕ отклонение => тренд/волатильность)
#   * ADX(HOUR_1) <= vwapMrMaxAdx (по умолчанию 25) (нужен РОВНЫЙ рынок)
# Эти условия взаимоисключающи, поэтому фильтр почти всегда HOLD -> 0 входов.
#
# Здесь снимаем вход/выход по одному параметру на IS (/backtest, 730д):
#   1) расширить ADX-гейт (25 -> 40 -> 100, т.е. выключить)
#   2) сузить полосу отклонения (1.2 -> 0.8 -> 0.5 ATR)
# Считаем totalTrades (НЕ trades - это массив сделок).
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 730,
    [string]$Ticker = "GLDRUBF"
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
$script:headers = @{}
function Login {
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body (@{ username = $authUser; password = $authPassword } | ConvertTo-Json)
    $script:headers = @{ Authorization = "Bearer $($login.accessToken)" }
}
Login

$rows = @()
foreach ($adx in 25, 40, 100) {
    foreach ($atr in 1.2, 0.8, 0.5) {
        $q = "vwapMrEnabled=true&vwapMrDeviationAtr=$atr&vwapMrAtrPeriod=14" +
            "&vwapMrMaxAdx=$adx&vwapMrBlockOnUnknown=true&maxHoldBars=12" +
            "&wfaSlPoints=300&wfaTpPoints=600"
        $url = "$BaseUrl/api/v1/backtest/$Ticker`?days=$Days&adaptiveConfidenceThreshold=0.60" +
            "&riskPerTradePercent=30&futuresMaxContractsPerPosition=100&loadHistory=false&$q"
        $label = "adx<=$adx atr>=$atr"
        foreach ($attempt in 1..3) {
            try {
                $r = Invoke-RestMethod -Method Get -Uri $url -Headers $script:headers -TimeoutSec 1800
                $n = [int]$r.totalTrades
                $rows += [pscustomobject]@{
                    Config = $label; Trades = $n
                    PF = [math]::Round([double]$r.profitFactor, 2)
                    RetPct = [math]::Round([double]$r.totalReturn * 100, 2)
                    MDDPct = [math]::Round([math]::Abs([double]$r.maxDrawdown) * 100, 2)
                    Sharpe = [math]::Round([double]$r.sharpeRatio, 2)
                }
                Write-Host ("{0,-18} trades={1,4} PF={2,6} ret={3,8}% MDD={4,7}%" -f `
                        $label, $n, [math]::Round([double]$r.profitFactor, 2),
                    [math]::Round([double]$r.totalReturn * 100, 2),
                    [math]::Round([math]::Abs([double]$r.maxDrawdown) * 100, 2))
                break
            } catch {
                if ($_.Exception.Message -match "401") { Login; continue }
                Write-Host "$label -> FAILED: $($_.Exception.Message)"
                $rows += [pscustomobject]@{ Config = $label; Trades = $null; PF = $null; RetPct = $null; MDDPct = $null; Sharpe = $null }
                break
            }
        }
    }
}

$csv = Join-Path $env:TEMP ("s2-diag-$Ticker-{0}.csv" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
$rows | Export-Csv -LiteralPath $csv -NoTypeInformation -Encoding UTF8
Write-Host ""
Write-Host "=== trades >= 30 ==="
$rows | Where-Object { $_.Trades -ge 30 } | Sort-Object Trades -Descending | Format-Table -AutoSize
Write-Host "csv: $csv"
