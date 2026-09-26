# Research: протокол честной валидации IS 70% / OOS 30% (2026-09-26).
#
# Зачем: предыдущие калибровки подбирали параметры на ПОЛНОЙ истории, а гейт
# гоняли на её же dev-части — это не исключает selection bias (см. AGENTS.md,
# combo730: плато P(noEdge)=0.058 на полной истории давало OOS PF 0.78-0.92 на
# dev-части той же истории). Поэтому здесь явная дисциплина:
#
#   1. holdoutFraction=0.30 -> WFA обучается на первых 70% истории, последние
#      30% (holdout) НЕ участвуют ни в подборе, ни в walk_forward.
#   2. Вердикт по OOS = holdout-сегменту: PF < 1.3 -> REJECTED независимо от
#      красоты IS/dev-метрик. Дополнительно требуется >= 30 сделок holdout,
#      иначе решение статистически незначимо (тот же ограничитель, что и
#      MIN_WALK_FORWARD_TRADES=100 в DeploymentGate).
#   3. Всё, что печатается в колонках IS_*/DEV_*, — диагностика, а не вердикт.
#
# Скрипт НЕ подбирает параметры: он проверяет заранее замороженные конфиги
# (ConfigCsv = список "имя;query-параметры") и печатает вердикт по каждому.
# Сетку подбора гоняют research_wfa_*.ps1 через /validate (быстро), сюда
# попадают только кандидаты, прошедшие IS-скрининг.
#
# Данные берутся с /holdout (компактная карта: holdoutProfitFactor/Trades/
# Return/Sharpe/MaxDrawdown + wfaConsistency/wfaOosTrades). Формальный вердикт
# гейта (LIVE/PAPER/RESEARCH_ONLY) — по -RunGate, он пересчитывает ту же
# связку и добавляет Monte Carlo + стресс-сценарии (долго: ~45 мин на конфиг).
#
# Фоновый запуск с 5-минутным heartbeat — как research_gate.ps1:
#   Start-Job -ScriptBlock { & .\scripts\research_oos70.ps1 -ConfigCsv "..." }
# Скрипт сам пишет прогресс в status-файл (см. -StatusFile, печатается путь).
#
# Калибровочный риск-профиль research-сайзера (riskPerTradePercent=30
# &futuresMaxContractsPerPosition=100) — НЕ live-параметры (live: maxC=1, Kelly).
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$Conf = 0.60,
    [string]$RiskPct = "30",
    [string]$MaxC = "100",
    [double]$HoldoutFraction = 0.30,
    [double]$MinOosPf = 1.3,
    [int]$MinOosTrades = 30,
    [int]$TimeoutSec = 10800,
    # Дополнительно прогнать /deployment-gate (Monte Carlo + стресс, долго).
    [switch]$RunGate,
    [string]$OutCsv = "",
    [string]$OutJson = "",
    [string]$StatusFile = "",
    # "имя;query-параметры" — каждый элемент = один замороженный конфиг.
    # Пусто = baseline (все research-фильтры выключены).
    [string[]]$ConfigCsv = @()
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $OutCsv) { $OutCsv = Join-Path $env:TEMP "oos70-$Ticker-$stamp.csv" }
if (-not $OutJson) { $OutJson = Join-Path $env:TEMP "oos70-$Ticker-$stamp.json" }
if (-not $StatusFile) { $StatusFile = Join-Path $env:TEMP "oos70-$Ticker-$stamp.status" }

function Write-Status([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
    Write-Host $line
    Add-Content -LiteralPath $StatusFile -Value $line -Encoding UTF8
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

$base = "$BaseUrl/api/v1/backtest/$Ticker/holdout?days=$Days&folds=$Folds&holdoutFraction=$HoldoutFraction" +
    "&adaptiveConfidenceThreshold=$Conf&riskPerTradePercent=$RiskPct&futuresMaxContractsPerPosition=$MaxC&loadHistory=false"
$gateBase = "$BaseUrl/api/v1/backtest/$Ticker/deployment-gate?days=$Days&folds=$Folds&holdoutFraction=$HoldoutFraction" +
    "&adaptiveConfidenceThreshold=$Conf&riskPerTradePercent=$RiskPct&futuresMaxContractsPerPosition=$MaxC&loadHistory=false"

$configs = if ($ConfigCsv.Count -eq 0) {
    @(@{ Name = "baseline (все фильтры off)"; Query = "" })
} else {
    $out = @()
    foreach ($c in $ConfigCsv) {
        if (-not $c) { continue }
        $parts = $c.Split(";", 2)
        $out += @{ Name = $parts[0]; Query = if ($parts.Count -gt 1) { $parts[1] } else { "" } }
    }
    $out
}

Write-Status ("start: ticker={0} days={1} folds={2} holdout={3} gate={4} configs={5}" -f `
        $Ticker, $Days, $Folds, $HoldoutFraction, [bool]$RunGate, $configs.Count)
Write-Status ("csv: {0}" -f $OutCsv)

$rows = @()
$results = @()
$swAll = [System.Diagnostics.Stopwatch]::StartNew()
foreach ($cfg in $configs) {
    $suffix = if ($cfg.Query) { "&$($cfg.Query)" } else { "" }
    $url = "$base$suffix"
    Write-Status ("run: {0}" -f $cfg.Name)
    Write-Status ("url: {0}" -f $url)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec $TimeoutSec
    } catch {
        Write-Status ("retry after re-auth: {0}" -f $_.Exception.Message)
        Start-Sleep -Seconds 10
        $headers = Get-Headers
        $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec $TimeoutSec
    }
    $sw.Stop()

    # OOS = holdout-сегмент (последние HoldoutFraction истории).
    $oosPf = [double]$r.holdoutProfitFactor
    $oosTrades = [int]$r.holdoutTrades
    $oosRetPct = [math]::Round([double]$r.holdoutReturn * 100, 2)
    $oosSharpe = [math]::Round([double]$r.holdoutSharpe, 2)
    $oosMddPct = [math]::Round([double]$r.holdoutMaxDrawdown * 100, 2)

    $verdict = if ($oosPf -lt $MinOosPf) { "REJECTED (OOS PF < $MinOosPf)" }
    elseif ($oosTrades -lt $MinOosTrades) { "INCONCLUSIVE (OOS trades $oosTrades < $MinOosTrades)" }
    else { "PASS (OOS) — research-кандидат" }

    $gateStatus = ""
    $liveAllowed = $null
    if ($RunGate) {
        Write-Status ("gate: {0} (Monte Carlo + стресс, долго)" -f $cfg.Name)
        $gUrl = "$gateBase$suffix"
        try {
            $g = Invoke-RestMethod -Method Get -Uri $gUrl -Headers $headers -TimeoutSec $TimeoutSec
        } catch {
            Write-Status ("gate retry after re-auth: {0}" -f $_.Exception.Message)
            $headers = Get-Headers
            $g = Invoke-RestMethod -Method Get -Uri $gUrl -Headers $headers -TimeoutSec $TimeoutSec
        }
        $gateStatus = [string]$g.status
        $liveAllowed = [bool]$g.liveAllowed
    }

    $rows += [pscustomobject]@{
        Config = $cfg.Name
        Verdict = $verdict
        OOS_PF = [math]::Round($oosPf, 3)
        OOS_RetPct = $oosRetPct
        OOS_Sharpe = $oosSharpe
        OOS_MDD_Pct = $oosMddPct
        OOS_Trades = $oosTrades
        OOS_Passable = $r.holdoutPassable
        Dev_Consistency = $r.wfaConsistency
        Dev_OOS_Trades = $r.wfaOosTrades
        Dev_WFA_Passable = $r.wfaPassable
        SL_Points = $r.paramsUsed.slPoints
        TP_Points = $r.paramsUsed.tpPoints
        GateStatus = $gateStatus
        LiveAllowed = $liveAllowed
        Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    }
    $results += [pscustomobject]@{
        config = $cfg.Name
        query = $cfg.Query
        verdict = $verdict
        oosProfitFactor = $oosPf
        oosReturnPct = $oosRetPct
        oosSharpe = $oosSharpe
        oosMaxDrawdownPct = $oosMddPct
        oosTrades = $oosTrades
        oosPassable = $r.holdoutPassable
        devConsistency = $r.wfaConsistency
        devOosTrades = $r.wfaOosTrades
        devWfaPassable = $r.wfaPassable
        slPoints = $r.paramsUsed.slPoints
        tpPoints = $r.paramsUsed.tpPoints
        gateStatus = $gateStatus
        liveAllowed = $liveAllowed
        elapsedSecs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
        url = $url
    }
    Write-Host ""
    $rows | Format-Table -AutoSize
    Write-Status ("done: {0} -> {1} PF={2} trades={3} ret={4}% {5}s" -f `
            $cfg.Name, $verdict, [math]::Round($oosPf, 3), $oosTrades, $oosRetPct, [math]::Round($sw.Elapsed.TotalSeconds, 0))
}
$swAll.Stop()

Write-Host ""
$rows | Format-Table -AutoSize
$rows | Export-Csv -Path $OutCsv -NoTypeInformation -Encoding UTF8
$results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutJson -Encoding UTF8
Write-Status ("csv: {0}" -f $OutCsv)
Write-Status ("json: {0}" -f $OutJson)
Write-Status ("total: {0}s" -f [math]::Round($swAll.Elapsed.TotalSeconds, 0))
Write-Host ""
Write-Host "Вердикт построен ТОЛЬКО на holdout-сегменте (последние $HoldoutFraction истории)."
Write-Host "dev-колонки (consistency/wfaOosTrades) — диагностика, не вердикт."
Write-Host "Даже PASS = research-кандидат, не live: живой сайзинг (maxC=1, Kelly),"
Write-Host "LIVE-guard (allowlist CNYRUBF) и funding-veto (off) не менялись."
