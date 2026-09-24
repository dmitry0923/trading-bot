# Research: WFA-валидация time×direction фильтра входа (365д, CNYRUBF).
#
# Гипотеза из декомпозиции сделок CNYRUBF (2026-09-23, 730д IS, maxC=1):
# утренние LONG (7-11ч, avg -21.3, TP 1/15) и дневные SHORT (13-16ч, avg -35.8,
# TP 0/10) генерируют основную часть убытка; вечер (18-23ч) - основную прибыль.
# Реализовано как входной фильтр направления EntryFilters.blocksDirection
# (longBlockUntilHour / shortBlockStartHour..EndHour), query-оверрайды
# timeDirectionEnabled/timeDirectionLongBlockUntilHour/timeDirectionShortBlockStartHour/
# timeDirectionShortBlockEndHour на /backtest /validate /robustness /holdout /deployment-gate.
#
# Калибровочный риск-профиль research-сайзера (riskPerTradePercent=30
# &futuresMaxContractsPerPosition=100), futuresGrid (SL/TP в пунктах).
# Прогон на live-стеке (postgres+redis, java -jar --spring.mvc.async.request-timeout=10800000).
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 365,
    [int]$Folds = 6,
    [double]$Conf = 0.60,
    [string]$RiskPct = "30",
    [string]$MaxC = "100",
    [string]$Ticker = "CNYRUBF",
    [string]$TdConfigCsv = "off;long=11;long=9;short=13-16|long=11;short=14-16|long=11;short=13-16|long=9;long=12;short=17|long=17"
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

foreach ($cfg in $TdConfigCsv.Split(";").Where({ $_ })) {
    # Конфиг вида: off / long=N / short=A-B / long=N-short=A-B
    $longUntil = ""
    $shortWin = ""
    if ($cfg -eq "off") {
        $label = "baseline (off)"
        $url = $base
    } else {
        $parts = $cfg.Split("|")
        foreach ($part in $parts) {
            if ($part -match "^long=(\d+)$") { $longUntil = $Matches[1] }
            elseif ($part -match "^short=(\d+)-(\d+)$") { $shortWin = "$($Matches[1])-$($Matches[2])" }
            elseif ($part -match "^short=(\d+)$") { $shortWin = "$($Matches[1])-$($Matches[1])" }
        }
        $sb = $base
        if ($longUntil) { $sb += "&timeDirectionEnabled=true&timeDirectionLongBlockUntilHour=$longUntil" }
        if ($shortWin) {
            $s, $e = $shortWin.Split("-")
            if (-not $longUntil) { $sb += "&timeDirectionEnabled=true" }
            $sb += "&timeDirectionShortBlockStartHour=$s&timeDirectionShortBlockEndHour=$e"
        }
        $label = "td long=$longUntil short=$shortWin"
        $url = $sb
    }
    Write-Host ("WFA {0}: {1}" -f $label, $url)
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
        Config = $label; LongUntil = $longUntil; ShortWindow = $shortWin
        Consistency = $r.consistency; OOS_RetPct = [math]::Round([double]$r.oosReturn * 100, 2)
        OOS_PF = [math]::Round([double]$r.oosProfitFactor, 2); OOS_Trades = $r.oosTrades
        P_NoEdge = [math]::Round([double]$r.oosProbabilityOfNoEdge, 3); Robust = $r.robust
        Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    }
}

Write-Host ""
$rows | Format-Table -AutoSize
Write-Host "Done."