# Research: IS-прескрин LLM-veto (docs/20 §10) на kimi-k3.
# Гипотеза: LLM полезен как СОКРАТИТЕЛЬ плохих входов (как источник направления edge не даёт).
#
# ВАЖНО (найдено на первом прогоне): существующие версии промптов арбитра для этой
# задачи вырождены - они решают «выбрать направление», а не «отвергнуть ли вход»:
#   default/conservative -> HOLD всегда (блокируют 100% входов, trades=0),
#   aggressive/signal    -> следуют за draft (блокируют 0%),
#   veto                 -> бинарный вопрос с перечнем конкретных оснований.
# Поэтому меряем версию `veto`, а остальные прогоняем как контроль.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 30,
    [string]$Version = "veto-score",
    [double]$MinScore = -1,
    [switch]$SkipBaseline
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
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body (@{ username = $authUser; password = $authPassword } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

function Show([string]$name, $r, [int]$secs) {
    if (-not $r) { "{0,-22} secs={1} ERROR" -f $name, $secs; return }
    "{0,-22} secs={1,5} trades={2,4} PF={3,7} ret%={4,8} win%={5,6} MDD%={6,6}" -f `
        $name, $secs, $r.totalTrades, [math]::Round([double]$r.profitFactor, 3),
        [math]::Round([double]$r.totalReturn * 100, 3), [math]::Round([double]$r.winRate * 100, 1),
        [math]::Round([double]$r.maxDrawdown * 100, 2)
}

if (-not $SkipBaseline) {
    $u = "$BaseUrl/api/v1/backtest/$Ticker`?days=$Days&timeframe=MINUTE_10&loadHistory=false"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-RestMethod -Method Get -Uri $u -Headers $headers -TimeoutSec 7200
    $sw.Stop()
    Show "baseline(no veto)" $r ([math]::Round($sw.Elapsed.TotalSeconds, 0))
}

$scoreParam = if ($MinScore -ge 0) { "&llmVetoMinScore=$MinScore" } else { "" }
$u = "$BaseUrl/api/v1/backtest/$Ticker`?days=$Days&timeframe=MINUTE_10&loadHistory=false&llmVetoEnabled=true&llmVetoPromptVersion=$Version&llmVetoBlockOnUnknown=true$scoreParam"
Write-Host ("veto: {0}" -f $u)
$sw = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $r = Invoke-RestMethod -Method Get -Uri $u -Headers $headers -TimeoutSec 7200
    $sw.Stop()
    Show "veto[$Version]" $r ([math]::Round($sw.Elapsed.TotalSeconds, 0))
    $out = Join-Path $PSScriptRoot "llmveto_is_${Version}_${Days}d.json"
    $r | ConvertTo-Json -Depth 5 | Set-Content -Path $out
    Write-Host "saved: $out"
} catch {
    $sw.Stop()
    Write-Host ("FAILED ({0}s): {1}" -f [math]::Round($sw.Elapsed.TotalSeconds, 0), $_.Exception.Message)
    if ($_.ErrorDetails) { Write-Host "DETAILS: $($_.ErrorDetails.Message)" }
    exit 1
}

# Распределение score - главный диагностический признак: константа = вырождение.
try {
    $m = Invoke-RestMethod -Uri "$BaseUrl/actuator/metrics/bt_llm_veto_score" -Headers $headers -TimeoutSec 20
    Write-Host "score metrics:"
    $m.measurements | ForEach-Object { "  {0} = {1}" -f $_.statistic, $_.value }
    $b = Invoke-RestMethod -Uri "$BaseUrl/actuator/metrics/bt_llm_veto_blocked_total" -Headers $headers -TimeoutSec 20
    $b.measurements | ForEach-Object { "  blocked {0} = {1} {2}" -f $_.statistic, $_.value, ($_.tags | ConvertTo-Json -Compress) }
} catch {
    Write-Host "metrics unavailable: $($_.Exception.Message)"
}
