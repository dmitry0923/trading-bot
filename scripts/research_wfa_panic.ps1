# Research: WFA-валидация panic-reversal входа (стратегия №7, IMOEXF).
#
# Гипотеза edge (2026-09-26): резкоеpanic-падение сессии на фоне перепроданности
# по старшему ТФ разворачивается — вход ТОЛЬКО в LONG:
#   - просадка текущей сессии (open -> low) >= panicMinSessionDropPercent;
#   - RSI(panicRsiPeriod) старшего ТФ <= panicMaxRsi;
#   - последний бар bullish (close > open), если panicRequireBullishBar.
# Фильтр: EntryFilters.panicReversalDirection, research, default off.
#
# Почему IMOEXF, а не оригинальный «Si/BR»: в БД нет BR ( Brent) и нет
# исторического стакана; IMOEXF — индексный фьючерс с полной историей
# MINUTE_10 (~50k свечей, с 2024-09-19) и funding_history для P&L.
# Это ПОДСТАНОВКА ядра стратегии, а не оригинальный инструмент — в вердикте
# это учитывается (см. docs/19-strategies-audit.md).
#
# Query-оверрайды panicReversalEnabled/panicMinSessionDropPercent/panicRsiPeriod/
# panicMaxRsi/panicTimeframe/panicRequireBullishBar/panicMinBars/
# panicBlockOnUnknown на /backtest /validate /robustness /holdout /deployment-gate.
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
    [string]$Ticker = "IMOEXF",
    [string]$Timeframe = "HOUR_1",
    [int]$MaxHoldBars = 0,
    [string]$OutCsv = "",
    # Конфиги через "|": "имя;query". Пусто = baseline + дефолтный panic-reversal.
    [string]$ConfigCsv = "panic rsi25 drop3|panicReversalEnabled=true&panicMinSessionDropPercent=3.0&panicRsiPeriod=14&panicMaxRsi=25&panicTimeframe=HOUR_1&panicBlockOnUnknown=true|panic rsi30 drop3|panicReversalEnabled=true&panicMinSessionDropPercent=3.0&panicRsiPeriod=14&panicMaxRsi=30&panicTimeframe=HOUR_1&panicBlockOnUnknown=true|panic rsi25 drop5|panicReversalEnabled=true&panicMinSessionDropPercent=5.0&panicRsiPeriod=14&panicMaxRsi=25&panicTimeframe=HOUR_1&panicBlockOnUnknown=true"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$statusPath = Join-Path $env:TEMP "wfa-panic-$Ticker-$stamp.status"
if (-not $OutCsv) { $OutCsv = Join-Path $env:TEMP "wfa-panic-$Ticker-$stamp.csv" }

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
Write-Status ("start: panic WFA ticker={0} days={1} folds={2} tf={3}" -f $Ticker, $Days, $Folds, $Timeframe)

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
Write-Host "Редкий сигнал (RSI<=25 + drop>=3% на H1) даст единицы OOS-сделок —"
Write-Host "это ожидаемо, а не повод смягчать пороги (см. 'чем чаще торгуешь — тем хуже' в AGENTS.md)."
