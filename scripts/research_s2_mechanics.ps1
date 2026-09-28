# Решающий тест на механику S2: почти нулевая полоса отклонения.
# Если atr=0.05 (заведомо всегда выполняется) тоже даёт 0 входов, то механизм
# согласования направлений сломан (mr != bestAction -> HOLD), а не калибровка.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 730,
    [string]$Ticker = "GLDRUBF"
)

$ErrorActionPreference = "Stop"
$envPath = Join-Path (Split-Path -Parent $PSScriptRoot) ".env"
function Resolve-EnvValue([string]$name, [string]$def = "") {
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}
$script:headers = @{}
function Login {
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body (@{ username = (Resolve-EnvValue "AUTH_USER"); password = (Resolve-EnvValue "AUTH_PASSWORD") } | ConvertTo-Json)
    $script:headers = @{ Authorization = "Bearer $($login.accessToken)" }
}
Login

# Контроль без фильтра (baseline должен дать ~86 входов на CNYRUBF; на GLDRUBF
# baseline из docs/20 дал 118) - если и он 0, то сломано что-то ещё.
$probes = @(
    @{ name = "NO-FILTER(baseline)"; q = "" },
    @{ name = "atr=0.05 adx<=25"; q = "vwapMrEnabled=true&vwapMrDeviationAtr=0.05&vwapMrAtrPeriod=14&vwapMrMaxAdx=25&vwapMrBlockOnUnknown=false&maxHoldBars=12&wfaSlPoints=300&wfaTpPoints=600" },
    @{ name = "atr=0.05 adx<=100"; q = "vwapMrEnabled=true&vwapMrDeviationAtr=0.05&vwapMrAtrPeriod=14&vwapMrMaxAdx=100&vwapMrBlockOnUnknown=false&maxHoldBars=12&wfaSlPoints=300&wfaTpPoints=600" },
    @{ name = "atr=0.2 adx<=100"; q = "vwapMrEnabled=true&vwapMrDeviationAtr=0.2&vwapMrAtrPeriod=14&vwapMrMaxAdx=100&vwapMrBlockOnUnknown=false&maxHoldBars=12&wfaSlPoints=300&wfaTpPoints=600" }
)

foreach ($p in $probes) {
    $url = "$BaseUrl/api/v1/backtest/$Ticker`?days=$Days&adaptiveConfidenceThreshold=0.60" +
        "&riskPerTradePercent=30&futuresMaxContractsPerPosition=100&loadHistory=false&$($p.q)"
    foreach ($attempt in 1..3) {
        try {
            $r = Invoke-RestMethod -Method Get -Uri $url -Headers $script:headers -TimeoutSec 1800
            Write-Host ("{0,-24} trades={1,4} PF={2,6} ret={3,8}%" -f $p.name,
                [int]$r.totalTrades, [math]::Round([double]$r.profitFactor, 2),
                [math]::Round([double]$r.totalReturn * 100, 2))
            break
        } catch {
            if ($_.Exception.Message -match "401") { Login; continue }
            Write-Host "$($p.name) -> FAILED: $($_.Exception.Message)"
            break
        }
    }
}
