# A/B passive (maker) исполнения CNYRUBF (docs/49, 2026-11-02).
# Одинаковые фолды/параметры, отличается только executionPassiveFill.
#
# Запуск: & .\scripts\research_passive_fill.ps1
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$User = 'admin',
    [string]$Password = 'admin123456',
    [string]$Ticker = 'CNYRUBF',
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$RiskPerTradePercent = 1.0,
    [int]$MaxContracts = 1,
    [double]$Confidence = 0.60,
    [string]$OutDir = 'artifacts'
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$login = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body (@{ username = $User; password = $Password } | ConvertTo-Json) -TimeoutSec 60
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

function Run-Validate([bool]$passive) {
    $flag = if ($passive) { 'true' } else { 'false' }
    $url = "$BaseUrl/api/v1/backtest/$Ticker/validate" +
        "?days=$Days&folds=$Folds&timeframe=MINUTE_10" +
        "&riskPerTradePercent=$RiskPerTradePercent&futuresMaxContractsPerPosition=$MaxContracts" +
        "&adaptiveConfidenceThreshold=$Confidence" +
        "&mlDirectionEnabled=false&fundingVetoEnabled=false&maxHoldBars=0" +
        "&executionPassiveFill=$flag&loadHistory=false&includeOosTrades=false"
    Write-Host "GET $url"
    return Invoke-RestMethod -Uri $url -Headers $headers -TimeoutSec 1800
}

$taker = Run-Validate -passive $false
$passive = Run-Validate -passive $true
$taker | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $OutDir 'passive_fill_2026-11-02_taker.json') -Encoding utf8
$passive | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $OutDir 'passive_fill_2026-11-02_passive.json') -Encoding utf8

function Metrics($r) {
    [pscustomobject]@{
        trades = $r.oosTrades
        ret = $r.oosReturn
        sharpe = $r.oosSharpe
        pf = $r.oosProfitFactor
        pNoEdge = $r.oosProbabilityOfNoEdge
        ciLow = $r.oosMeanTradeCI95Low
        ciHigh = $r.oosMeanTradeCI95High
        consistency = $r.consistency
        robust = $r.robust
    }
}

$mT = Metrics $taker
$mP = Metrics $passive
$improved = ($mP.pf -gt $mT.pf) -and ($mP.ret -gt $mT.ret)
$pass = $mP.trades -ge 30 -and $mP.pf -ge 1.30 -and $mP.pNoEdge -lt 0.05 -and $mP.ciLow -gt 0 -and $improved
$verdict = if ($pass) { 'PASS' } elseif ($mP.pf -lt 1.30 -or -not $improved) { 'REJECTED' } else { 'INCONCLUSIVE' }

$summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    ticker = $Ticker
    days = $Days
    folds = $Folds
    taker = $mT
    passive = $mP
    passiveImproved = $improved
    verdict = $verdict
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutDir 'passive_fill_2026-11-02_summary.json') -Encoding utf8

Write-Host "=== TAKER ==="
$mT | Format-List
Write-Host "=== PASSIVE ==="
$mP | Format-List
Write-Host "improved=$improved verdict=$verdict"
