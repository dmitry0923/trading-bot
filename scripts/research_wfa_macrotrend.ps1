# Research: WFA-валидация macro-trend входа (стратегия №1, CNYRUBF).
#
# Гипотеза edge (2026-09-26): в трендовой среде (старший ТФ EMA_f > EMA_s)
# вход по откату к базовой EMA даёт лучшее отношение выигрыш/убыток, чем вход
# на пробое. Фильтр: EntryFilters.macroTrendDirection — BUY только если
#   - EMA(macroTrendFastEma) > EMA(macroTrendSlowEma) на macroTrendTimeframe;
#   - базовый close отклонён от EMA(macroTrendPullbackEmaPeriod) не больше чем
#     на macroTrendMaxDeviationPercent (т.е. это откат, а не разгон);
#   - данных старшего ТФ достаточно (macroTrendMinHigherBars), иначе —
#     fail-closed при macroTrendBlockOnUnknown=true.
#
# СОСТАВ СЦЕНАРИЯ (согласованный пользователем, 2026-09-26): macro-тренд +
# VWAP pullback + funding plateau 5-8 руб. + maxHoldBars=1095 (~18 дней).
# Оговорка: VWAP-pullback в этом ядре реализован как откат к базовой EMA
# (macroTrendPullbackEmaPeriod) — отдельный VWAP-откат проверяется
# research_wfa_vwapmr.ps1 (VWAP-MR) и не смешивается с трендовым входом
# здесь: контртрендовый фильтр в трендовом ядре логически противоречив.
# Funding-veto (fundingVetoEnabled) и maxHoldBars — переиспользуемые
# существующие гейты, подключаются query-оверрайдами, НЕ дублируются кодом.
#
# Query-оверрайды: macroTrendEnabled/macroTrendTimeframe/macroTrendFastEma/
# macroTrendSlowEma/macroTrendMinHigherBars/macroTrendPullbackEmaPeriod/
# macroTrendMaxDeviationPercent/macroTrendBlockOnUnknown + maxHoldBars +
# fundingVeto* на /backtest /validate /robustness /holdout /deployment-gate.
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
    [string]$Timeframe = "HOUR_1",
    [int]$MaxHoldBars = 1095,
    [string]$OutCsv = "",
    # Конфиги через "|": "имя;query". Пусто = baseline + дефолтный macro-trend.
    [string]$ConfigCsv = "macro ema20/50 dev0.5|macroTrendEnabled=true&macroTrendTimeframe=HOUR_1&macroTrendFastEma=20&macroTrendSlowEma=50&macroTrendMaxDeviationPercent=0.5&macroTrendBlockOnUnknown=true&maxHoldBars=1095|macro ema20/50 dev0.5 + fv6|macroTrendEnabled=true&macroTrendTimeframe=HOUR_1&macroTrendFastEma=20&macroTrendSlowEma=50&macroTrendMaxDeviationPercent=0.5&macroTrendBlockOnUnknown=true&maxHoldBars=1095&fundingVetoEnabled=true&fundingVetoLongThresholdRub=6&fundingVetoShortThresholdRub=6|macro ema20/50 dev0.5 + fv5|macroTrendEnabled=true&macroTrendTimeframe=HOUR_1&macroTrendFastEma=20&macroTrendSlowEma=50&macroTrendMaxDeviationPercent=0.5&macroTrendBlockOnUnknown=true&maxHoldBars=1095&fundingVetoEnabled=true&fundingVetoLongThresholdRub=5&fundingVetoShortThresholdRub=5|macro ema20/50 dev0.5 + fv8|macroTrendEnabled=true&macroTrendTimeframe=HOUR_1&macroTrendFastEma=20&macroTrendSlowEma=50&macroTrendMaxDeviationPercent=0.5&macroTrendBlockOnUnknown=true&maxHoldBars=1095&fundingVetoEnabled=true&fundingVetoLongThresholdRub=8&fundingVetoShortThresholdRub=8|macro ema20/50 dev1.0|macroTrendEnabled=true&macroTrendTimeframe=HOUR_1&macroTrendFastEma=20&macroTrendSlowEma=50&macroTrendMaxDeviationPercent=1.0&macroTrendBlockOnUnknown=true&maxHoldBars=1095"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$statusPath = Join-Path $env:TEMP "wfa-macro-$Ticker-$stamp.status"
if (-not $OutCsv) { $OutCsv = Join-Path $env:TEMP "wfa-macro-$Ticker-$stamp.csv" }

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
Write-Status ("start: macro WFA ticker={0} days={1} folds={2} mh={3}" -f $Ticker, $Days, $Folds, $MaxHoldBars)

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
Write-Host "Funding plateau 5-8 руб. — это проверка ПЛАСТО, а не поиск пика:"
Write-Host "смотри, ведёт ли себя поверхность гладко (5/6/8 рядом) или 'всё или ничего'."
Write-Host "Кандидатов гоняй форвард через research_oos70.ps1 (OOS PF >= 1.3)."
