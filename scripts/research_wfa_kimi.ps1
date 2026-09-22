# Research: WFA-валидация LLM-сигналов на CNYRUBF MINUTE_10 через moonshotai/kimi-k3.
#
# Прогон конвейера tech -> fund -> strategy -> contrarian -> arbitrator через
# AgentBacktestSignalGenerator. Для смены модели приложение должно быть
# перезапущено с ROUTER_AI_MODEL=moonshotai/kimi-k3 и
# LLM_DISABLE_REASONING=true (thinking-модели без этого упираются в
# llm.timeout-sec: 30 -> CALL_ERROR). Параметры bt.agent.* задаются при старте:
#   --bt.agent.enabled=true --bt.agent.live-strategies=false
#   --bt.agent.sample-every=240 --bt.agent.prompt-version=aggressive
#   --bt.agent.confidence-threshold=0.40 --bt.agent.tech-min-signal-strength=0.0
#
# ВАЖНО: adaptiveConfidenceThreshold/ML/entry-оверрайды в query НЕ передаются —
# иначе ApiController подменит генератор на LiveStrategyBacktestSignalGenerator
# и LLM-конвейер не будет использоваться.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 365,
    [int]$Folds = 6,
    [string]$Timeframe = "MINUTE_10"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"

function Resolve-EnvValue([string]$name, [string]$def = "") {
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}

function Get-Headers {
    $authUser = Resolve-EnvValue "AUTH_USER"
    $authPassword = Resolve-EnvValue "AUTH_PASSWORD"
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body (@{ username = $authUser; password = $authPassword } | ConvertTo-Json)
    return @{ Authorization = "Bearer $($login.accessToken)" }
}

$headers = Get-Headers
$url = "$BaseUrl/api/v1/backtest/$Ticker/validate`?days=$Days&folds=$Folds&timeframe=$Timeframe&loadHistory=false"
Write-Host ("WFA kimi-k3 {0} {1}д folds={2}: {3}" -f $Ticker, $Days, $Folds, $url)
$sw = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 10800
    $sw.Stop()
    "secs={0}" -f [math]::Round($sw.Elapsed.TotalSeconds, 0)
    "consistency={0}" -f $r.consistency
    "robust={0}" -f $r.robust
    "oosTrades={0}" -f $r.oosTrades
    "oosReturn={0}" -f [math]::Round([double]$r.oosReturn * 100, 2) + "%"
    "oosSharpe={0}" -f [math]::Round([double]$r.oosSharpe, 3)
    "oosPF={0}" -f [math]::Round([double]$r.oosProfitFactor, 3)
    "oosEdgeSignificant={0}" -f $r.oosEdgeStatisticallySignificant
    "P(noEdge)={0}" -f [math]::Round([double]$r.oosProbabilityOfNoEdge, 4)
    "CI95=[{0:N1};{1:N1}]" -f [double]$r.oosMeanTradeCI95Low, [double]$r.oosMeanTradeCI95High
    $r | ConvertTo-Json -Depth 5 | Set-Content -Path (Join-Path $PSScriptRoot "kimi_wfa_result.json")
    "saved: scripts/kimi_wfa_result.json"
} catch {
    $sw.Stop()
    Write-Host ("FAILED ({0}s): {1}" -f [math]::Round($sw.Elapsed.TotalSeconds, 0), $_.Exception.Message)
    if ($_.ErrorDetails) { Write-Host "DETAILS: $($_.ErrorDetails.Message)" }
    exit 1
}