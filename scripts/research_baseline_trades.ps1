# Baseline 730d CNYRUBF with full trade log (research restart 2026-11-02).
# Цель: декомпозиция P&L baseline-стратегии на новом движке (engineVersion 2026-10-26.2):
# направление, час, день недели, hold, closeReason, комиссия/funding, captured range.
#
# Запуск (сервер уже поднят): & .\scripts\research_baseline_trades.ps1
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$User = 'admin',
    [string]$Password = 'admin123456',
    [string]$Ticker = 'CNYRUBF',
    [int]$Days = 730,
    [string]$OutJson = 'artifacts/baseline_trades_2026-11-02.json',
    [string]$OutCsv = 'artifacts/baseline_trades_2026-11-02.csv',
    [int]$TimeoutSec = 3600
)

$ErrorActionPreference = 'Stop'

function Login {
    $body = @{ username = $User; password = $Password } | ConvertTo-Json
    $r = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 60
    return $r.accessToken
}

$token = Login
$headers = @{ Authorization = "Bearer $token" }

$url = "$BaseUrl/api/v1/backtest/$Ticker" +
    "?days=$Days" +
    "&futuresMaxContractsPerPosition=1" +
    "&mlDirectionEnabled=false" +
    "&fundingVetoEnabled=false" +
    "&includeTrades=true" +
    "&loadHistory=false"

Write-Host "GET $url"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$resp = Invoke-RestMethod -Uri $url -Headers $headers -Method Get -TimeoutSec $TimeoutSec
$sw.Stop()
Write-Host ("elapsed={0:N1}s trades={1}" -f $sw.Elapsed.TotalSeconds, @($resp.trades).Count)

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutJson) | Out-Null
$resp | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutJson -Encoding utf8

# Компактный CSV для анализа.
$rows = @($resp.trades) | ForEach-Object {
    [pscustomobject]@{
        direction      = $_.direction
        quantity       = $_.quantity
        entryPrice     = $_.entryPrice
        exitPrice      = $_.exitPrice
        entryTime      = $_.entryTime
        exitTime       = $_.exitTime
        closeReason    = $_.closeReason
        pnl            = $_.pnl
        commission     = $_.commission
        funding        = $_.funding
        holdBars       = $_.holdBars
        hourOfDay      = $_.hourOfDay
        dayOfWeek      = $_.dayOfWeek
        atrPercent     = $_.atrPercent
        volatilityPercent = $_.volatilityPercent
    }
}
$rows | Export-Csv -LiteralPath $OutCsv -NoTypeInformation -Encoding utf8

[pscustomobject]@{
    ticker = $resp.ticker
    engineVersion = $resp.engineVersion
    totalReturn = $resp.totalReturn
    profitFactor = $resp.profitFactor
    sharpe = $resp.sharpeRatio
    maxDrawdown = $resp.maxDrawdown
    winRate = $resp.winRate
    totalTrades = $resp.totalTrades
    probabilityOfNoEdge = $resp.probabilityOfNoEdge
    meanTradeCI95Low = $resp.meanTradeCI95Low
    meanTradeCI95High = $resp.meanTradeCI95High
    passable = $resp.passable
    elapsedSec = [math]::Round($sw.Elapsed.TotalSeconds, 1)
} | ConvertTo-Json -Depth 4
