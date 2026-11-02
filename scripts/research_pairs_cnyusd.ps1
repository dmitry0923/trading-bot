# Pairs CNYRUBF/USDRUBF на DAY_1 (docs/47, 2026-11-02).
# /pairs/backtest (IS70/OOS30) + /pairs/validate (WFA folds=3).
#
# Запуск: & .\scripts\research_pairs_cnyusd.ps1
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$User = 'admin',
    [string]$Password = 'admin123456',
    [string]$LegA = 'CNYRUBF',
    [string]$LegB = 'USDRUBF',
    [int]$Days = 1600,
    [string]$Timeframe = 'DAY_1',
    [int]$LookbackBars = 240,
    [double]$EntryZ = 2.0,
    [double]$ExitZ = 0.5,
    [double]$StopZ = 4.0,
    [double]$OosFraction = 0.30,
    [double]$CapitalSlice = 0.50,
    [double]$InitialCapital = 100000,
    [int]$Folds = 3,
    [string]$OutPrefix = 'pairs_cnyusd_2026-11-02',
    [string]$OutDir = 'artifacts'
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$login = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body (@{ username = $User; password = $Password } | ConvertTo-Json) -TimeoutSec 60
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

$common = "timeframe=$Timeframe&days=$Days&lookbackBars=$LookbackBars&entryZ=$EntryZ&exitZ=$ExitZ&stopZ=$StopZ&capitalSlice=$CapitalSlice&initialCapital=$InitialCapital"
$backtestUrl = "$BaseUrl/api/v1/pairs/backtest?legA=$LegA&legB=$LegB&$common&oosFraction=$OosFraction"
$wfaUrl = "$BaseUrl/api/v1/pairs/validate?legA=$LegA&legB=$LegB&$common&folds=$Folds"

Write-Host "GET $backtestUrl"
$bt = Invoke-RestMethod -Uri $backtestUrl -Headers $headers -TimeoutSec 900
$bt | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $OutDir "${OutPrefix}_backtest.json") -Encoding utf8

Write-Host "=== BACKTEST ==="
[pscustomobject]@{
    bars = $bt.bars
    spreadCorr = $bt.spreadReturnCorrelation
    meanBeta = $bt.meanBeta
    verdict = $bt.verdict
    fullPF = $bt.full.profitFactor
    fullTrades = $bt.full.totalTrades
    isPF = $bt.inSample.profitFactor
    isTrades = $bt.inSample.totalTrades
    oosPF = $bt.outOfSample.profitFactor
    oosTrades = $bt.outOfSample.totalTrades
    oosP = $bt.outOfSample.probabilityOfNoEdge
    oosCiLow = $bt.outOfSample.meanTradeCI95Low
    oosCiHigh = $bt.outOfSample.meanTradeCI95High
    oosMdd = $bt.outOfSample.maxDrawdown
} | Format-List

Write-Host "GET $wfaUrl"
$wfa = Invoke-RestMethod -Uri $wfaUrl -Headers $headers -TimeoutSec 900
$wfa | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $OutDir "${OutPrefix}_wfa.json") -Encoding utf8
Write-Host "=== WFA ==="
[pscustomobject]@{
    folds = $wfa.folds
    consistency = $wfa.consistency
    robust = $wfa.robust
    verdict = $wfa.verdict
    aggPF = $wfa.aggregate.profitFactor
    aggTrades = $wfa.aggregate.totalTrades
    aggP = $wfa.aggregate.probabilityOfNoEdge
} | Format-List

