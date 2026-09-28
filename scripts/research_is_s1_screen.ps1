# IS-скрининг ядра S1 (EMA20x50 + объём) на CNYRUBF: где живая выборка?
#
# Зачем: WFA показал, что EMA20x50 + Vol>SMA20*1.5 на CNYRUBF даёт 3 OOS-сделки
# (INCONCLUSIVE по построению). Но maxHoldBars/SL/TP не меняют ЧИСЛО входов -
# значит все 27 S1-конфигов дали бы одну и ту же тонкую выборку. Здесь быстро
# (один /backtest = секунды, не WFA-минуты) ищем параметры, дающие >= 30 входов.
#
# Считаем входы при riskPerTradePercent=30 & maxC=100 (калибровочный профиль).
# $r.trades - число сделок; $r.passable - встроенный порог BacktestResult.
# Сетка диагностики: разная строгость EMA-пары и порога объёма.
# Узкое место - сам EMA-кросс: на CNYRUBF EMA20/50 даёт 3-5 входов на 730 дней,
# а порог объёма почти не связывает (x1.0 и x1.2 -> одинаковые 5 сделок).
# Поэтому по умолчанию гоняем 5 EMA-пар на объёме x1.0 - этого хватает, чтобы
# найти пару с >= 30 входами. Полная сетка объёма: -FullVolGrid.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 730,
    [string]$Ticker = "CNYRUBF",
    [switch]$FullVolGrid
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

# JWT живёт недолго - при 401 обязателен re-login, иначе весь скрининг
# молча уходит в FAILED после первых двух запросов.
$script:headers = @{}
function Login {
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body (@{ username = $authUser; password = $authPassword } | ConvertTo-Json)
    $script:headers = @{ Authorization = "Bearer $($login.accessToken)" }
}
Login

# Сетка диагностики: разная строгость EMA-пары и порога объёма.
# pnlDirection - строка (BUY/SELL); плюс 0 = только BUY (как задано в ТЗ).
$emaPairs = @(@(20, 50), @(10, 30), @(20, 100), @(9, 26), @(5, 20))
$volMults = if ($FullVolGrid) { @(1.0, 1.2, 1.5, 2.0) } else { @(1.0) }

$rows = @()
foreach ($pair in $emaPairs) {
    $fast = $pair[0]
    $slow = $pair[1]
    foreach ($vm in $volMults) {
        $q = "emaCrossEnabled=true&emaCrossFastPeriod=$fast&emaCrossSlowPeriod=$slow" +
            "&emaCrossBlockOnUnknown=true&volumeSpikeEnabled=true&volumeSpikePeriod=20" +
            "&volumeSpikeMultiplier=$vm&volumeSpikeBlockOnUnknown=true&maxHoldBars=36" +
            "&wfaSlPoints=40&wfaTpPoints=80"
        $url = "$BaseUrl/api/v1/backtest/$Ticker`?days=$Days&adaptiveConfidenceThreshold=0.60" +
            "&riskPerTradePercent=30&futuresMaxContractsPerPosition=100&loadHistory=false&$q"
        $label = "EMA$fast/$slow vol x$vm"
        # ВНИМАНИЕ: у /backtest поле числа сделок - `totalTrades`; `trades` -
        # это МАССИВ сделок (выводился как System.Object[]).
        $n = $null
        foreach ($attempt in 1..3) {
            try {
                $r = Invoke-RestMethod -Method Get -Uri $url -Headers $script:headers -TimeoutSec 900
                $n = [int]$r.totalTrades
                $pf = if ($null -ne $r.profitFactor) { [math]::Round([double]$r.profitFactor, 2) } else { $null }
                $ret = [math]::Round([double]$r.totalReturn * 100, 2)
                $mdd = [math]::Round([math]::Abs([double]$r.maxDrawdown) * 100, 2)
                $sh = [math]::Round([double]$r.sharpeRatio, 2)
                $pass = $r.passable
                break
            } catch {
                if ($_.Exception.Message -match "401") {
                    Write-Host "  (401 -> re-login, попытка $attempt)"
                    Login
                    continue
                }
                Write-Host "$label -> FAILED: $($_.Exception.Message)"
                $n = $null
                break
            }
        }
        if ($null -ne $n) {
            $rows += [pscustomobject]@{
                Config = $label; Trades = $n; PF = $pf; RetPct = $ret
                MDDPct = $mdd; Sharpe = $sh; Passable = $pass
            }
            Write-Host ("{0,-22} trades={1,4} PF={2,6} ret={3,8}% MDD={4,6}%" -f $label, $n, $pf, $ret, $mdd)
        } else {
            $rows += [pscustomobject]@{
                Config = $label; Trades = $null; PF = $null; RetPct = $null
                MDDPct = $null; Sharpe = $null; Passable = $null
            }
        }
    }
}

$csv = Join-Path $env:TEMP ("s1-screen-$Ticker-{0}.csv" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
$rows | Export-Csv -LiteralPath $csv -NoTypeInformation -Encoding UTF8
Write-Host ""
Write-Host "=== trades >= 30 (порог INCONCLUSIVE) ==="
$rows | Where-Object { $_.Trades -ge 30 } | Sort-Object Trades -Descending | Format-Table -AutoSize
Write-Host "csv: $csv"
