# S4 (единственное ядро с реальным шансом): CNYRUBF/M10, range-squeeze + объёмный
# пробой. Baseline 85 сделок, PF 0.93 - значит в отличие от S1/S2/S3 фильм может
# отбирать из существующей выборки, а не пытаться создать новую.
#
# Сетка снимает по одному измерению:
#   * объёмный порог (1.0 = требование снято, чистый squeeze как в docs/19 -> изоляция
#     вклада объёма; 1.2/1.5 - требование есть);
#   * lookback сжатия (сколько баров назад искать сжатие: 3/5/10);
#   * множитель сжатия 0.5 против 0.8.
# Порог кандидата: 30 сделок (IS), иначе WFA не запускается (INCONCLUSIVE по построению).
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
$Threshold = 30
$rows = @()

function Run-Probe {
    param([string]$Label, [string]$Query)
    $url = "$BaseUrl/api/v1/backtest/CNYRUBF`?days=$Days&adaptiveConfidenceThreshold=0.60" +
        "&riskPerTradePercent=30&futuresMaxContractsPerPosition=100&loadHistory=false&$Query"
    foreach ($attempt in 1..3) {
        try {
            $r = Invoke-RestMethod -Method Get -Uri $url -Headers $script:headers -TimeoutSec 1800
            $n = [int]$r.totalTrades
            $verdict = if ($n -ge $Threshold) { "CANDIDATE" } else { "drop" }
            $script:rows += [pscustomobject]@{
                Label = $Label; Trades = $n
                PF = [math]::Round([double]$r.profitFactor, 2)
                RetPct = [math]::Round([double]$r.totalReturn * 100, 2)
                MDDPct = [math]::Round([math]::Abs([double]$r.maxDrawdown) * 100, 2)
                Sharpe = [math]::Round([double]$r.sharpeRatio, 2); Verdict = $verdict
            }
            Write-Host ("{0,-30} trades={1,4} PF={2,6} ret={3,8}% MDD={4,7}% -> {5}" -f `
                    $Label, $n, [math]::Round([double]$r.profitFactor, 2),
                [math]::Round([double]$r.totalReturn * 100, 2),
                [math]::Round([math]::Abs([double]$r.maxDrawdown) * 100, 2), $verdict)
            return
        } catch {
            if ($_.Exception.Message -match "401") { Login; continue }
            Write-Host "$Label -> FAILED: $($_.Exception.Message)"
            return
        }
    }
}

# База: сжатие 0.5*ATR50, lookback 5, объём 1.5 (эталон ТЗ).
$base = "maxHoldBars=24&wfaSlPoints=40&wfaTpPoints=80&rangeSqueezeEnabled=true" +
    "&rangeSqueezeRangePeriod=20&rangeSqueezeAtrPeriod=50&rangeSqueezeBlockOnUnknown=true" +
    "&volumeSpikeEnabled=true&volumeSpikePeriod=20"

# Изоляция вклада объёма: multiplier 1.0 vs 1.5 при прочих равных.
foreach ($vol in 1.5, 1.0) {
    Run-Probe -Label "s4 base vol$vol" -Query "$base&rangeSqueezeMultiplier=0.5&rangeSqueezeLookbackBars=5&rangeSqueezeRequireVolume=true&volumeSpikeMultiplier=$vol"
}
# Чувствительность к lookback сжатия (объём 1.5).
foreach ($lb in 3, 10) {
    Run-Probe -Label "s4 lookback$lb" -Query "$base&rangeSqueezeMultiplier=0.5&rangeSqueezeLookbackBars=$lb&rangeSqueezeRequireVolume=true&volumeSpikeMultiplier=1.5"
}
# Более широкое сжатие 0.8 (меньше требований -> больше входов).
foreach ($lb in 5, 10) {
    Run-Probe -Label "s4 mult0.8 lb$lb" -Query "$base&rangeSqueezeMultiplier=0.8&rangeSqueezeLookbackBars=$lb&rangeSqueezeRequireVolume=true&volumeSpikeMultiplier=1.5"
}

$csv = Join-Path $env:TEMP ("s4-prescreen-{0}.csv" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
$rows | Export-Csv -LiteralPath $csv -NoTypeInformation -Encoding UTF8
Write-Host ""
$cands = @($rows | Where-Object { $_.Verdict -eq "CANDIDATE" })
Write-Host "=== кандидаты на WFA: $($cands.Count) из $($rows.Count) ==="
$cands | Format-Table -AutoSize
Write-Host "csv: $csv"
