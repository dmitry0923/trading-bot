# Research: калибровка порога score-режима LLM-veto (docs/20 §10).
#
# Контекст: бинарный вопрос «отвергнуть ли вход?» LLM отвечает вырожденно
# (0% или 100% блокировок на всех версиях промпта). Score-режим переносит решение
# на порог: арбитр ставит signalStrength = качество входа 0..1, блок при score < порога.
# Здесь снимается зависимость PF/сделок от порога, чтобы проверить плато, а не пик
# (так же проверялись funding-veto 5-8 руб. и max-hold 368 баров).
#
# Каждый прогон - отдельный HTTP-запрос, поэтому мемоизация вердиктов в LlmVeto
# не переиспользуется между порогами: LLM вызывается заново на всех кандидатах.
# Это медленно (около 8 минут на порог при 33 кандидатах), но воспроизводимо.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 365,
    [string]$Version = "veto-score",
    [double[]]$Thresholds = @(0.35, 0.40, 0.45, 0.50, 0.55),
    [string]$OutCsv = ""
)

$ErrorActionPreference = "Stop"
# Инвариантная культура обязательна: в русской локали PowerShell печатает 0.35 как
# "0,35", и порог уезжал бы в URL/CSV с запятой (Spring такое разбирает, CSV - нет).
[System.Threading.Thread]::CurrentThread.CurrentCulture = [System.Globalization.CultureInfo]::InvariantCulture
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"

function Resolve-EnvValue([string]$name, [string]$def = "") {
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}

function Get-Auth {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" `
        -Body (@{ username = (Resolve-EnvValue "AUTH_USER"); password = (Resolve-EnvValue "AUTH_PASSWORD") } | ConvertTo-Json)
}

# Токен живёт недолго, а свип идёт дольше: 401 = обновить токен и повторить попытку,
# иначе пороги молча выпадают из таблицы (ровно тот harness-баг, что уже ловили
# с maxHoldBars и разделителем ConfigCsv).
function Invoke-WithAuth($uri) {
    $script:accessToken = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        if (-not $script:accessToken) {
            $script:accessToken = (Get-Auth).accessToken
        }
        try {
            return Invoke-RestMethod -Method Get -Uri $uri -Headers @{ Authorization = "Bearer $script:accessToken" } -TimeoutSec 7200
        } catch {
            if ($_.Exception.Response.StatusCode.value__ -eq 401) {
                Write-Host "  token expired, re-login (attempt $attempt)"
                $script:accessToken = $null
                continue
            }
            throw
        }
    }
    throw "401 x3: $uri"
}

function BlockedCount {
    try {
        $b = Invoke-RestMethod -Uri "$BaseUrl/actuator/metrics/bt_llm_veto_blocked_total" -Headers @{ Authorization = "Bearer $script:accessToken" } -TimeoutSec 20
        $sum = 0.0
        foreach ($msr in $b.measurements) { if ($msr.statistic -eq "COUNT") { $sum += [double]$msr.value } }
        return $sum
    } catch { return 0.0 }
}

$out = if ($OutCsv) { $OutCsv } else { Join-Path $PSScriptRoot "llmveto_score_sweep_${Days}d.csv" }
if (Test-Path $out) { Remove-Item -LiteralPath $out -Force }
"threshold,secs,trades,pf,ret_pct,win_pct,mdd_pct,blocked_run" | Set-Content -Path $out

Write-Host "== score sweep: ticker=$Ticker days=$Days version=$Version thresholds=$($Thresholds -join ',') =="
foreach ($t in $Thresholds) {
    $before = BlockedCount
    $u = "$BaseUrl/api/v1/backtest/$Ticker`?days=$Days&timeframe=MINUTE_10&loadHistory=false&llmVetoEnabled=true&llmVetoPromptVersion=$Version&llmVetoBlockOnUnknown=true&llmVetoMinScore=$t"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-WithAuth $u
        $sw.Stop()
        $secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
        $blocked = [math]::Round((BlockedCount) - $before, 0)
        if ($blocked -lt 0) { $blocked = "n/a" }
        $line = "{0},{1},{2},{3},{4},{5},{6},{7}" -f `
            $t, $secs, $r.totalTrades, [math]::Round([double]$r.profitFactor, 3),
            [math]::Round([double]$r.totalReturn * 100, 3), [math]::Round([double]$r.winRate * 100, 1),
            [math]::Round([double]$r.maxDrawdown * 100, 3), $blocked
        $line | Add-Content -Path $out
        Write-Host $line
    } catch {
        $sw.Stop()
        Write-Host ("FAILED threshold={0} ({1}s): {2}" -f $t, [math]::Round($sw.Elapsed.TotalSeconds, 0), $_.Exception.Message)
    }
}
Write-Host "== sweep done: $out =="
