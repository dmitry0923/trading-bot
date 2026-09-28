# Research: WFA-калибровка четырёх детерминированных ядер (2026-09-28).
#
# Протокол (пользователь, 2026-09-28): WFA-OOS на ПОЛНЫХ 730 днях, folds=8,
# conf=0.60, калибровочный риск-профиль riskPerTradePercent=30 &
# futuresMaxContractsPerPosition=100 (НЕ live-параметры: live maxC=1, Kelly).
# Последние 30% НЕ отделяются как holdout - подбор идёт по всей истории,
# поэтому итоговый вердикт подтверждается отдельно (см. research_oos70.ps1
# и /deployment-gate). Здесь - честная выборка OOS по всем folds.
#
# Ядра (дефолт off в коде, включаются только query-override):
#   S1 CNYRUBF M10 - EMA20 x EMA50 + объём > SMA(Vol,20) * 1.5.
#   S2 GLDRUBF M10 - VWAP-отклонение в ATR14 (vwapMrDeviationAtr).
#   S3 IMOEXF  H1  - panic-reversal БЕЗ session-drop: RSI(14) < 30 + бычий бар.
#   S4 CNYRUBF M10 - range-squeeze (R20 < 0.5 * ATR50) + объёмный пробой.
#
# ВАЖНО (harness-баги, зафиксированы в AGENTS.md - не повторять):
#   1. maxHoldBars передаётся ТОЛЬКО в query конфига. Если продублировать его
#      в базовом URL, Spring берёт первое значение и max-hold молча выключается.
#   2. ConfigCsv/ConfigsFile формат "Имя;Ticker;Query" - разделитель `;`
#      обязателен, иначе элемент молча уходит в baseline.
#   3. Поле Sharpe в ответе - `oosSharpe` (НЕ `oosSharpeRatio`).
#   4. wfaSlPoints/wfaTpPoints - одиночные Int (override in-sample сетки);
#      если их не задать, SL/TP выбирает in-sample сетка.
#
# Запуск:
#   .\scripts\research_wfa_four_cores.ps1 -ConfigsFile .\scripts\configs_four_cores.csv
#   -SkipBaseline                                  # без baseline-строк
#   -ConfigCsv "имя;тикер;emaCrossEnabled=true"     # одиночный прогон
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$Conf = 0.60,
    [string]$RiskPct = "30",
    [string]$MaxC = "100",
    [int]$TimeoutSec = 10800,
    # "Имя;Ticker;Query" - каждый элемент = один замороженный конфиг.
    # "Имя;Ticker" (без третьего поля) = baseline этого тикера.
    [string[]]$ConfigCsv = @(),
    [string]$ConfigsFile = "",
    [switch]$SkipBaseline,
    [string]$OutCsv = "",
    [string]$StatusFile = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $OutCsv) { $OutCsv = Join-Path $env:TEMP "wfa4-$stamp.csv" }
if (-not $StatusFile) { $StatusFile = Join-Path $env:TEMP "wfa4-$stamp.status" }

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

# Конфиги: -ConfigCsv важнее -ConfigsFile.
$raw = @()
if ($ConfigCsv.Count -gt 0) {
    $raw = $ConfigCsv
} elseif ($ConfigsFile) {
    if (-not (Test-Path -LiteralPath $ConfigsFile)) { throw "ConfigsFile не найден: $ConfigsFile" }
    $raw = @(Get-Content -LiteralPath $ConfigsFile -Encoding UTF8 | Where-Object { $_ -and -not $_.StartsWith("#") })
} else {
    throw "Укажи -ConfigCsv или -ConfigsFile"
}

# Pre-flight: строка БЕЗ разделителя `;` молча уехала бы в baseline (harness-баг).
$configs = @()
foreach ($c in $raw) {
    if (-not $c) { continue }
    $parts = $c.Split(";", 2)
    if ($parts.Count -lt 2 -or -not $parts[1]) {
        throw "Некорректный конфиг (нужно 'Имя;Ticker;Query'): $c"
    }
    $tickerParts = $parts[1].Split(";", 2)
    $query = if ($tickerParts.Count -gt 1) { $tickerParts[1] } else { "" }
    $configs += @{ Name = $parts[0]; Ticker = $tickerParts[0]; Query = $query }
}
# maxHoldBars не должен попадать в базовый URL (Spring берёт первое значение).
foreach ($cfg in $configs) {
    if ($cfg.Query -match "maxHoldBars" -and $cfg.Query -notmatch "maxHoldBars=\d+") {
        throw "maxHoldBars без значения в конфиге $($cfg.Name)"
    }
}

if (-not $SkipBaseline) {
    $seen = @{}
    $base = @()
    foreach ($cfg in $configs) {
        if (-not $seen.ContainsKey($cfg.Ticker)) {
            $seen[$cfg.Ticker] = $true
            $tf = if ($cfg.Query -match "timeframe=(\w+)") { $Matches[1] } else { "MINUTE_10" }
            $base += @{ Name = "baseline $($cfg.Ticker)/$tf"; Ticker = $cfg.Ticker; Query = "timeframe=$tf" }
        }
    }
    $configs = $base + $configs
}

Write-Status ("start: days={0} folds={1} conf={2} risk={3}% maxC={4} configs={5}" -f `
        $Days, $Folds, $Conf, $RiskPct, $MaxC, $configs.Count)
Write-Status ("csv: {0}" -f $OutCsv)

$rows = @()
$i = 0
$swAll = [System.Diagnostics.Stopwatch]::StartNew()
foreach ($cfg in $configs) {
    $i++
    # days/folds/conf/risk/maxC - в базовом URL; maxHoldBars и SL/TP - в query.
    $url = "$BaseUrl/api/v1/backtest/$($cfg.Ticker)/validate`?days=$Days&folds=$Folds" +
        "&adaptiveConfidenceThreshold=$Conf&riskPerTradePercent=$RiskPct" +
        "&futuresMaxContractsPerPosition=$MaxC&loadHistory=false"
    if ($cfg.Query) { $url += "&$($cfg.Query)" }
    Write-Status ("[{0}/{1}] {2} :: {3}" -f $i, $configs.Count, $cfg.Name, $cfg.Ticker)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $r = $null
    try {
        $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec $TimeoutSec
    } catch {
        # Токен мог протухнуть - пере-логин и одна попытка повтора.
        Write-Status "  retry после re-login: $($_.Exception.Message)"
        try {
            $headers = Get-Headers
            $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec $TimeoutSec
        } catch {
            $sw.Stop()
            $rows += [pscustomobject]@{
                Name = $cfg.Name; Ticker = $cfg.Ticker; OOS_RetPct = $null; OOS_PF = $null
                OOS_Sharpe = $null; OOS_Trades = $null; Consistency = $null; P_NoEdge = $null
                CI95 = $null; Robust = $null; Verdict = "FAIL"; Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
            }
            Write-Status "  FAILED"
            continue
        }
    }
    $sw.Stop()
    $pf = [double]$r.oosProfitFactor
    $trades = [int]$r.oosTrades
    # Вердикт по WFA-OOS: тонкая выборка -> INCONCLUSIVE (порог 30, как в
    # MIN_WALK_FORWARD_TRADES/DeploymentGate), слабый PF -> REJECTED.
    $verdict = if ($pf -lt 1.3) { "REJECTED" } elseif ($trades -lt 30) { "INCONCLUSIVE" } else { "PASS" }
    $rows += [pscustomobject]@{
        Name = $cfg.Name
        Ticker = $cfg.Ticker
        OOS_RetPct = [math]::Round([double]$r.oosReturn * 100, 2)
        OOS_PF = [math]::Round($pf, 2)
        OOS_Sharpe = [math]::Round([double]$r.oosSharpe, 2)
        OOS_Trades = $trades
        Consistency = [math]::Round([double]$r.consistency, 3)
        P_NoEdge = [math]::Round([double]$r.oosProbabilityOfNoEdge, 3)
        CI95 = "[{0:N1}; {1:N1}]" -f [double]$r.oosMeanTradeCI95Low, [double]$r.oosMeanTradeCI95High
        Robust = $r.robust
        Verdict = $verdict
        Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    }
    $rows[-1] | Format-Table -AutoSize | Out-String | Write-Host
    $rows | Export-Csv -LiteralPath $OutCsv -NoTypeInformation -Encoding UTF8
}
$swAll.Stop()

Write-Status ("done: {0} конфигов за {1:N1} мин" -f $configs.Count, $swAll.Elapsed.TotalMinutes)
Write-Status "--- итог (сортировка по OOS_PF) ---"
$rows | Where-Object { $_.OOS_PF } | Sort-Object OOS_PF -Descending | Format-Table -AutoSize
$rows | Export-Csv -LiteralPath $OutCsv -NoTypeInformation -Encoding UTF8
Write-Status "csv: $OutCsv"
