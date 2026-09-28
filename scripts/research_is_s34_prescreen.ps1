# IS-прескрин S3/S4: НЕ запускать многочасовой WFA на ядра, которые не набирают
# >=30 сделок на IS. Это прямое повторение ошибки с S2 (WFA без прескрина = 25 мин
# впустую) и повторение урока ML-фильтра из docs/20.
#
# S3: IMOEXF/HOUR_1, panic-reversal = RSI(14) < порог И бычий бар, только LONG.
#     НОВОЕ против docs/19: порог RSI снимается по сетке (там был зафиксирован
#     RSI<=30 -> 0 сигналов, то есть сетап не встречается 2 года).
# S4: CNYRUBF/MINUTE_10, range-squeeze (R(20) < k*ATR50) + объёмный пробой
#     (Vol > SMA(Vol,20)*mult). НОВОЕ против docs/19: там был чистый squeeze
#     (REJECTED, PF 0.84) без требования объёмного пробоя.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 730
)

$ErrorActionPreference = "Stop"
$envPath = Join-Path (Split-Path -Parent $PSScriptRoot) ".env"
function Resolve-EnvValue([string]$name, [string]$def = "") {
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}
$script:headers = @{}
function Login {
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body (@{ username = (Resolve-EnvValue "AUTH_USER"); password = (Resolve-EnvValue "AUTH_PASSWORD") } | ConvertTo-Json)
    $script:headers = @{ Authorization = "Bearer $($login.accessToken)" }
}
Login

# Порог IS-скрина: 30 сделок (тот же, что MIN_WALK_FORWARD_TRADES и
# критерий INCONCLUSIVE в research_oos70.ps1).
$Threshold = 30
$rows = @()

function Run-Probe {
    param([string]$Label, [string]$Ticker, [string]$Query)
    $url = "$BaseUrl/api/v1/backtest/$Ticker`?days=$Days&adaptiveConfidenceThreshold=0.60" +
        "&riskPerTradePercent=30&futuresMaxContractsPerPosition=100&loadHistory=false&$Query"
    foreach ($attempt in 1..3) {
        try {
            $r = Invoke-RestMethod -Method Get -Uri $url -Headers $script:headers -TimeoutSec 1800
            $n = [int]$r.totalTrades
            $script:rows += [pscustomobject]@{
                Core = $Label; Ticker = $Ticker; Trades = $n
                PF = [math]::Round([double]$r.profitFactor, 2)
                RetPct = [math]::Round([double]$r.totalReturn * 100, 2)
                MDDPct = [math]::Round([math]::Abs([double]$r.maxDrawdown) * 100, 2)
                Sharpe = [math]::Round([double]$r.sharpeRatio, 2)
                Verdict = if ($n -ge $Threshold) { "CANDIDATE" } else { "drop" }
            }
            Write-Host ("{0,-34} trades={1,4} PF={2,6} ret={3,8}% MDD={4,7}% -> {5}" -f `
                    $Label, $n, [math]::Round([double]$r.profitFactor, 2),
                [math]::Round([double]$r.totalReturn * 100, 2),
                [math]::Round([math]::Abs([double]$r.maxDrawdown) * 100, 2),
                $(if ($n -ge $Threshold) { "CANDIDATE" } else { "drop" }))
            return
        } catch {
            if ($_.Exception.Message -match "401") { Login; continue }
            Write-Host "$Label -> FAILED: $($_.Exception.Message)"
            $script:rows += [pscustomobject]@{ Core = $Label; Ticker = $Ticker; Trades = $null; PF = $null; RetPct = $null; MDDPct = $null; Sharpe = $null; Verdict = "error" }
            return
        }
    }
}

# Контроль: без фильтров. Если baseline пуст, то тикер/конфиг сломаны, а не фильтр.
Run-Probe -Label "S3 baseline IMOEXF/H1" -Ticker "IMOEXF" -Query "timeframe=HOUR_1&maxHoldBars=48&wfaSlPoints=200&wfaTpPoints=400"
Run-Probe -Label "S4 baseline CNYRUBF/M10" -Ticker "CNYRUBF" -Query "maxHoldBars=24&wfaSlPoints=40&wfaTpPoints=80"

# S3: сетка по RSI и по требуемой просадке сессии.
# panicUseSessionDrop=false - новая фича этого прогона (в docs/19 она была
# обязательной и давала 0 сигналов).
foreach ($rsi in 40, 35, 30) {
    foreach ($drop in 0, 1, 2) {
        Run-Probe -Label "S3 rsi$rsi drop$drop" -Ticker "IMOEXF" -Query (
            "timeframe=HOUR_1&maxHoldBars=48&wfaSlPoints=200&wfaTpPoints=400" +
            "&panicReversalEnabled=true&panicUseSessionDrop=false&panicMaxRsi=$rsi" +
            "&panicMinSessionDropPercent=$drop&panicRequireBullishBar=true&panicBlockOnUnknown=true"
        )
    }
}

# S4: сетка по множителю сжатия и по порогу объёма.
foreach ($mult in 0.5, 0.8) {
    foreach ($vol in 1.5, 1.2) {
        Run-Probe -Label "S4 squeeze$mult vol$vol" -Ticker "CNYRUBF" -Query (
            "maxHoldBars=24&wfaSlPoints=40&wfaTpPoints=80" +
            "&rangeSqueezeEnabled=true&rangeSqueezeRangePeriod=20&rangeSqueezeAtrPeriod=50" +
            "&rangeSqueezeMultiplier=$mult&rangeSqueezeLookbackBars=5" +
            "&rangeSqueezeRequireVolume=true&rangeSqueezeBlockOnUnknown=true" +
            "&volumeSpikeEnabled=true&volumeSpikePeriod=20&volumeSpikeMultiplier=$vol"
        )
    }
}

$csv = Join-Path $env:TEMP ("s34-prescreen-{0}.csv" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
$rows | Export-Csv -LiteralPath $csv -NoTypeInformation -Encoding UTF8
Write-Host ""
Write-Host "=== кандидаты на WFA (trades >= $Threshold) ==="
$rows | Where-Object { $_.Verdict -eq "CANDIDATE" } | Format-Table -AutoSize
Write-Host "=== итог: $($rows.Count) зондов, кандидатов: $(($rows | Where-Object { $_.Verdict -eq 'CANDIDATE' }).Count) ==="
Write-Host "csv: $csv"
