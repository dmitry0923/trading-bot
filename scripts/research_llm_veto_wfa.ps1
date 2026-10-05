# Research: OOS-валидация LLM-veto (WFA /validate), docs/20 10.
#
# ��ель: измерить OOS profit factor детерминированного сигнала CNYRUBF
# с LLM-вето поверх входа (Contrarian -> Arbitrator).
#
# Отличие от research_llm_veto_is.ps1: там in-sample /backtest на N днях,
# здесь walk-forward /validate — OOS-метрики на каждом фолде.
#
# ВНИМАНИЕ: каждый кандидат на вход = 2 вызова LLM (challenge + adjudicate).
# На 365д x folds=6 это сотни платных вызовов, поэтому Days/Folds задаются
# параметрами и по умолчанию взяты скромными.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 30,
    [int]$Folds = 2,
    [double]$Conf = 0.60,
    [string]$Version = "veto-score",
    [double]$MinScore = -1,
    [switch]$NoVeto,
    [switch]$NoBlockOnUnknown,
    [string]$Out = ""
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
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" `
    -Body (@{ username = $authUser; password = $authPassword } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

$block = if ($NoBlockOnUnknown) { "false" } else { "true" }
$veto = if ($NoVeto) { "" } else { "&llmVetoEnabled=true&llmVetoPromptVersion=$Version&llmVetoBlockOnUnknown=$block" }
$scoreParam = if ($MinScore -ge 0) { "&llmVetoMinScore=$MinScore" } else { "" }

$u = "$BaseUrl/api/v1/backtest/$Ticker/validate`?days=$Days&folds=$Folds" +
     "&timeframe=MINUTE_10&loadHistory=false&adaptiveConfidenceThreshold=$Conf$veto$scoreParam"
Write-Host ("request: {0}" -f $u)

$sw = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $r = Invoke-RestMethod -Method Get -Uri $u -Headers $headers -TimeoutSec 7200
} catch {
    $sw.Stop()
    Write-Host ("ERROR after {0}s: {1}" -f [math]::Round($sw.Elapsed.TotalSeconds, 0), $_.Exception.Message)
    exit 1
}
$sw.Stop()

# /validate отдаёт плоский DTO: oosTrades / oosProfitFactor / oosReturn / oosSharpe ...
"{0,-10} days={1,4} folds={2} conf={3} veto={4} secs={5}" -f `
    $Ticker, $Days, $Folds, $Conf, (-not $NoVeto), [math]::Round($sw.Elapsed.TotalSeconds, 0)
"  OOS trades={0} PF={1} ret%={2} Sharpe={3} consistency={4} robust={5} P(noEdge)={6}" -f `
    $r.oosTrades, $r.oosProfitFactor, [math]::Round([double]$r.oosReturn * 100, 3),
    [math]::Round([double]$r.oosSharpe, 3), [math]::Round([double]$r.consistency, 3),
    $r.robust, [math]::Round([double]$r.oosProbabilityOfNoEdge, 3)

if ($Out -eq "") { $Out = Join-Path $PSScriptRoot "llmveto_wfa_${Ticker}_${Days}d_f${Folds}.json" }
$r | ConvertTo-Json -Depth 6 | Set-Content -Path $Out
Write-Host "saved: $Out"