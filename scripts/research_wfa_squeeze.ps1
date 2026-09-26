# Research: WFA-валидация squeeze-breakout входа (стратегия №8, CNYRUBF).
#
# Гипотеза edge (2026-09-26): после сжатия волатильности (BB(20,2) внутри
# Keltner EMA20 ± 1.5*ATR10) пробой границы Bollinger вверх/вниз даёт
# импульс — вход по направлению пробоя. Фильтр направления:
# EntryFilters.squeezeDirection: пока BB внутри Keltner — HOLD; на первом баре,
# где BB вышел наружу И close за соответствующей границей BB — BUY/SELL.
#
# Реализация: входной фильтр в LiveStrategyBacktestSignalGenerator
# (research, default off). Query-оверрайды squeezeEnabled/squeezeBlockOnUnknown
# на /backtest /validate /robustness /holdout /deployment-gate.
#
# Издержки и funding включены штатно (FundingCosts, funding_history), SL/TP —
# штатная futuresGrid (пункты цены): squeeze-сигнал длинный, узкий стоп не
# имеет смысла, калибруется сеткой как обычно.
#
# maxHoldBars: стратегия подразумевает удержание до выхода по SL/TP; опционально
# проверяется выход по времени (тот же механизм, что в research_wfa_maxhold.ps1).
#
# Калибровочный риск-профиль research-сайзера (riskPerTradePercent=30
# &futuresMaxContractsPerPosition=100) — НЕ live-параметры (live: maxC=1, Kelly).
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$Conf = 0.60,
    [string]$RiskPct = "30",
    [string]$MaxC = "100",
    [string]$Ticker = "CNYRUBF",
    [int]$MaxHoldBars = 0,
    [string]$OutCsv = "",
    # Конфиги squeeze через "|": "имя;squeezeEnabled=..&squeezeBlockOnUnknown=..&maxHoldBars=.."
    # (";" внутри разделяет имя и query, "|" — сами конфиги). Пусто = baseline + дефолтный squeeze.
    [string]$ConfigCsv = "squeeze on|squeezeEnabled=true&squeezeBlockOnUnknown=true|squeeze on + mh368;squeezeEnabled=true&squeezeBlockOnUnknown=true&maxHoldBars=368"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$statusPath = Join-Path $env:TEMP "wfa-squeeze-$Ticker-$stamp.status"
if (-not $OutCsv) { $OutCsv = Join-Path $env:TEMP "wfa-squeeze-$Ticker-$stamp.csv" }

function Write-Status([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
    Write-Host $line
    Add-Content -LiteralPath $statusPath -Value $line -Encoding UTF8
}

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

$base = "$BaseUrl/api/v1/backtest/$Ticker/validate?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf" +
    "&riskPerTradePercent=$RiskPct&futuresMaxContractsPerPosition=$MaxC&loadHistory=false&maxHoldBars=$MaxHoldBars"

$rows = @()
$swAll = [System.Diagnostics.Stopwatch]::StartNew()
Write-Status ("start: squeeze WFA ticker={0} days={1} folds={2}" -f $Ticker, $Days, $Folds)

foreach ($cfg in @("baseline;") + @($ConfigCsv.Split("|").Where({ $_ }))) {
    $parts = $cfg.Split(";", 2)
    $name = $parts[0]
    $query = if ($parts.Count -gt 1) { $parts[1] } else { "" }
    $url = if ($query) { "$base&$query" } else { $base }
    Write-Status ("run: {0}" -f $name)
    Write-Status ("url: {0}" -f $url)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 7200
    } catch {
        Write-Status ("retry after re-auth: {0}" -f $_.Exception.Message)
        $headers = Get-Headers
        $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 7200
    }
    $sw.Stop()
    $rows += [pscustomobject]@{
        Config = $name
        Consistency = $r.consistency; OOS_RetPct = [math]::Round([double]$r.oosReturn * 100, 2)
        OOS_PF = [math]::Round([double]$r.oosProfitFactor, 3); OOS_Sharpe = [math]::Round([double]$r.oosSharpeRatio, 2)
        OOS_Trades = $r.oosTrades
        P_NoEdge = [math]::Round([double]$r.oosProbabilityOfNoEdge, 3); Robust = $r.robust
        Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    }
    Write-Host ""
    $rows | Format-Table -AutoSize
}
$swAll.Stop()

Write-Host ""
$rows | Format-Table -AutoSize
$rows | Export-Csv -Path $OutCsv -NoTypeInformation -Encoding UTF8
Write-Status ("csv: {0}" -f $OutCsv)
Write-Status ("total: {0}s" -f [math]::Round($swAll.Elapsed.TotalSeconds, 0))
Write-Host ""
Write-Host "Кандидатов из этой таблицы гоняй форвард через research_oos70.ps1 (OOS PF >= 1.3)."
Write-Host "Все robust=false при <100 OOS-сделках — ограничение инструмента, не дефект стратегии."
