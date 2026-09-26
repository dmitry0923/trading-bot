# Research: WFA-валидация VWAP mean-reversion входа (стратегия №1).
#
# Гипотеза edge «контртрендовый вход от сессионного VWAP» (2026-09-26):
# вход ТОЛЬКО когда |close - VWAP| >= N*sigma (сессионный VWAP со сбросом по
# дате) И ADX старшего ТФ (по умолчанию HOUR_1) <= порога, т.е. рынок в
# боковике. Направление - к VWAP (close ниже VWAP -> BUY, выше -> SELL).
#
# Реализовано как входной фильтр направления в
# LiveStrategyBacktestSignalGenerator (EntryFilters.vwapMrDirection);
# query-оверрайды vwapMrEnabled/vwapMrDeviationSigma/vwapMrMaxAdx/
# vwapMrTimeframe/vwapMrMinSessionBars/vwapMrBlockOnUnknown на /validate.
#
# ВАЖНО про SL/TP: mean-reversion требует узкого стопа, а штатная futuresGrid
# (/validate без override) начинается от 25 пт и заканчивается 600 пт. Поэтому
# здесь обязательно передаётся wfaSlPoints/wfaTpPoints - override in-sample
# сетки (commit a92bdcb). Значения по умолчанию - узкий MR-стоп.
#
# Калибровочный риск-профиль research-сайзера (riskPerTradePercent=30
# &futuresMaxContractsPerPosition=100). Это НЕ live-параметры (live: maxC=1,
# Kelly). Прогон на live-стеке (postgres+redis, java -jar c
# --spring.mvc.async.request-timeout=10800000).
#
# ВНИМАНИЕ: вход у VWAP-MR контрттрендовый, поэтому в комбинации с
#Funding-veto (блокирует дорогой вход) и time-direction возможны нюансы - их
#комбинации считаются отдельными конфигами.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$Conf = 0.60,
    [string]$RiskPct = "30",
    [string]$MaxC = "100",
    [string]$Ticker = "CNYRUBF",
    [int]$SlPoints = 15,
    [int]$TpPoints = 15,
    [string]$Timeframe = "HOUR_1",
    [int]$MinSessionBars = 6,
    [string]$OutCsv = "",
    # sigma;adx - сетка калибровки. Пусто = только baseline.
    [string]$ConfigCsv = "1.0;1.5;2.0;2.5;3.0"
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

function Get-Headers {
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body (@{ username = $authUser; password = $authPassword } | ConvertTo-Json)
    return @{ Authorization = "Bearer $($login.accessToken)" }
}
$headers = Get-Headers

$base = "$BaseUrl/api/v1/backtest/$Ticker/validate`?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf&riskPerTradePercent=$RiskPct&futuresMaxContractsPerPosition=$MaxC&loadHistory=false&wfaSlPoints=$SlPoints&wfaTpPoints=$TpPoints"

$rows = @()
$sw = [System.Diagnostics.Stopwatch]::StartNew()
Write-Host ("baseline WFA (фильтр выключен): SL/TP={0}/{1} пт" -f $SlPoints, $TpPoints)
$r = Invoke-RestMethod -Method Get -Uri $base -Headers $headers -TimeoutSec 7200
$sw.Stop()
$rows += [pscustomobject]@{
        Config = "baseline (off)"; Sigma = ""; MaxAdx = ""
        Consistency = $r.consistency; OOS_RetPct = [math]::Round([double]$r.oosReturn * 100, 2)
        OOS_PF = [math]::Round([double]$r.oosProfitFactor, 2); OOS_Sharpe = [math]::Round([double]$r.oosSharpeRatio, 2)
        OOS_Trades = $r.oosTrades
        P_NoEdge = [math]::Round([double]$r.oosProbabilityOfNoEdge, 3); Robust = $r.robust
        Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    }
$rows | Format-Table -AutoSize

foreach ($cfg in $ConfigCsv.Split(";").Where({ $_ })) {
    $parts = $cfg.Split(":", 2)
    $sigma = $parts[0]
    $maxAdx = if ($parts.Count -gt 1) { $parts[1] } else { "25" }
    $url = "$base&vwapMrEnabled=true&vwapMrDeviationSigma=$sigma&vwapMrMaxAdx=$maxAdx&vwapMrTimeframe=$Timeframe&vwapMrMinSessionBars=$MinSessionBars&vwapMrBlockOnUnknown=true"
    Write-Host ("WFA vwapMr sigma={0} adx<={1}: {2}" -f $sigma, $maxAdx, $url)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 7200
        $sw.Stop()
    } catch {
        $sw.Stop()
        $headers = Get-Headers
        $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 7200
    }
    $rows += [pscustomobject]@{
        Config = "vwapMr sigma=$sigma adx<=$maxAdx"; Sigma = $sigma; MaxAdx = $maxAdx
        Consistency = $r.consistency; OOS_RetPct = [math]::Round([double]$r.oosReturn * 100, 2)
        OOS_PF = [math]::Round([double]$r.oosProfitFactor, 2); OOS_Sharpe = [math]::Round([double]$r.oosSharpeRatio, 2)
        OOS_Trades = $r.oosTrades
        P_NoEdge = [math]::Round([double]$r.oosProbabilityOfNoEdge, 3); Robust = $r.robust
        Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    }
    Write-Host ""
    $rows | Format-Table -AutoSize
}

Write-Host ""
$rows | Format-Table -AutoSize
if ($OutCsv) {
    $rows | Export-Csv -Path $OutCsv -NoTypeInformation -Encoding UTF8
    Write-Host ("CSV: {0}" -f $OutCsv)
}
Write-Host "Done. Все robust=false при <100 OOS-сделках - доехать до 100 нельзя, это
ограничение инструмента, а не дефект стратегии (см. AGENTS.md)."
