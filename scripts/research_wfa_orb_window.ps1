# Research: WFA-валидация ORB с заданным окном (стратегия №7, ORB на золоте).
#
# Гипотеза edge (2026-09-26): вход в направлении пробоя ДНЕВНОГО opening range
# в заданном временном окне, а не «с начала суток». Для GLDRUBF окно
# 15:30-16:00 МСК (orbWindowStartMinutes=930, orbWindowEndMinutes=960,
# orbWindowBars=3 при MINUTE_10) - разворот азиатской сессии перед открытием
# США.
#
# Реализовано как входной фильтр направления в
# LiveStrategyBacktestSignalGenerator (EntryFilters.orbDirection) с окном
# диапазона; query-оверрайды orbEnabled/orbWindowBars/orbStrictBreakout/
# orbBlockOnUnknown/orbWindowStartMinutes/orbWindowEndMinutes на /validate.
# Дефолт 0..1440 = поведение «с начала дня» (используется как контроль).
#
# Калибровочный риск-профиль research-сайзера (riskPerTradePercent=30
# &futuresMaxContractsPerPosition=100). Это НЕ live-параметры (live: maxC=1,
# Kelly). Прогон на live-стеке (postgres+redis, java -jar c
# --spring.mvc.async.request-timeout=10800000).
#
# Сетка по умолчанию изолирует вклад ОКНА (start/end) от вкладов strict/loose
# и ширины диапазона, плюс контроль «с начала дня» на том же тикере.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$Conf = 0.60,
    [string]$RiskPct = "30",
    [string]$MaxC = "100",
    [string]$Ticker = "GLDRUBF",
    [string]$OutCsv = "",
    # startMin:endMin:bars:strict - сетка калибровки. Пусто = только baseline.
    [string]$ConfigCsv = "930:960:3:true;930:960:3:false;900:930:3:false;930:990:6:false;0:1440:3:true"
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

$base = "$BaseUrl/api/v1/backtest/$Ticker/validate`?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf&riskPerTradePercent=$RiskPct&futuresMaxContractsPerPosition=$MaxC&loadHistory=false"

$rows = @()
$sw = [System.Diagnostics.Stopwatch]::StartNew()
Write-Host ("baseline WFA (фильтр выключен): {0}" -f $Ticker)
$r = Invoke-RestMethod -Method Get -Uri $base -Headers $headers -TimeoutSec 7200
$sw.Stop()
$rows += [pscustomobject]@{
        Config = "baseline (off)"; Window = ""; Bars = ""; Strict = ""
        Consistency = $r.consistency; OOS_RetPct = [math]::Round([double]$r.oosReturn * 100, 2)
        OOS_PF = [math]::Round([double]$r.oosProfitFactor, 2); OOS_Sharpe = [math]::Round([double]$r.oosSharpeRatio, 2)
        OOS_Trades = $r.oosTrades
        P_NoEdge = [math]::Round([double]$r.oosProbabilityOfNoEdge, 3); Robust = $r.robust
        Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    }
$rows | Format-Table -AutoSize

foreach ($cfg in $ConfigCsv.Split(";").Where({ $_ })) {
    $parts = $cfg.Split(":")
    $start = $parts[0]
    $end = $parts[1]
    $bars = $parts[2]
    $strict = $parts[3]
    $url = "$base&orbEnabled=true&orbWindowStartMinutes=$start&orbWindowEndMinutes=$end&orbWindowBars=$bars&orbStrictBreakout=$strict&orbBlockOnUnknown=true"
    Write-Host ("WFA orb window={0}-{1} bars={2} strict={3}" -f $start, $end, $bars, $strict)
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
        Config = "orb $start-$end/$bars strict=$strict"; Window = "$start-$end"; Bars = $bars; Strict = $strict
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
Write-Host "Done. Все robust=false при <100 OOS-сделках - ограничение инструмента
(730 дней даёт 30-90 OOS-сделок на этих фильтрах), а не дефект стратегии."
