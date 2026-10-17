# Research a3 (PLAN-001 §5.1): сравнительный WFA 730д/folds=8 на LIVE-риске
# (maxC=1 / Kelly, без risk-override) для детерминированной базы CNYRUBF:
#   taker (bt.execution-passive-fill-enabled=false, дефолт) vs
#   passive/maker (query executionPassiveFill=true).
#
# Гипотеза направления (а): пассивное исполнение снижает стоимость входа/рыночного
# выхода, и net OOS может пересечь гейт §4 (PF >= 1.30). Если PF 0.92 -> не >= 1.30,
# направление (а) закрывается честным REJECTED, платный архив не покупается.
#
# Протокол:
#   1) обе конфигурации на одной истории (loadHistory=false);
#   2) набор сделок обязан совпасть (пассив не меняет решения, только цену филла);
#   3) при PF >= 1.30 у passive — deployment-gate (-RunGate) для формального вердикта
#      (>= 100 OOS-сделок, edge, holdout, MC).
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$Conf = 0.60,
    [string]$OutDir = "",
    [switch]$RunGate,
    [switch]$SkipBaseline
)

$ErrorActionPreference = "Stop"
[System.Threading.Thread]::CurrentThread.CurrentCulture = [System.Globalization.CultureInfo]::InvariantCulture
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
if (-not $OutDir) { $OutDir = Join-Path $root "artifacts" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Resolve-EnvValue([string]$name, [string]$def = "") {
    if (-not (Test-Path $envPath)) { return $def }
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}

$script:accessToken = $null
function Get-Auth {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" `
        -Body (@{ username = (Resolve-EnvValue "AUTH_USER" "admin"); password = (Resolve-EnvValue "AUTH_PASSWORD" "admin123456") } | ConvertTo-Json)
}

function Invoke-WithAuth($uri, [int]$TimeoutSec = 21600) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        if (-not $script:accessToken) { $script:accessToken = (Get-Auth).accessToken }
        try {
            return Invoke-RestMethod -Method Get -Uri $uri -Headers @{ Authorization = "Bearer $script:accessToken" } -TimeoutSec $TimeoutSec
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

$hb = Join-Path $OutDir "a3_passive_fill.heartbeat"
function Write-Hb([string]$msg) {
    Set-Content -Path $hb -Value ("t={0} {1}" -f [DateTimeOffset]::UtcNow.ToUnixTimeSeconds(), $msg)
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg)
}

$csv = Join-Path $OutDir "a3_passive_fill_${Days}d_f${Folds}.csv"
"config,secs,oos_ret_pct,oos_pf,oos_sharpe,oos_trades,consistency,prob_no_edge,edge_sig" | Set-Content -Path $csv

function Run-Config([string]$name, [string]$extra) {
    $u = "$BaseUrl/api/v1/backtest/$Ticker/validate`?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf&loadHistory=false$extra"
    Write-Hb "START $name"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-WithAuth $u
    $sw.Stop()
    $secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    $json = Join-Path $OutDir "a3_${name}_${Days}d_f${Folds}.json"
    $r | ConvertTo-Json -Depth 8 | Set-Content -Path $json
    $line = "{0},{1},{2},{3},{4},{5},{6},{7},{8}" -f `
        $name, $secs,
        [math]::Round([double]$r.oosReturn * 100, 4), [math]::Round([double]$r.oosProfitFactor, 4),
        [math]::Round([double]$r.oosSharpe, 3), $r.oosTrades, $r.consistency,
        [math]::Round([double]$r.oosProbabilityOfNoEdge, 4), $r.oosEdgeStatisticallySignificant
    $line | Add-Content -Path $csv
    Write-Hb "DONE $name secs=$secs ret=$([math]::Round([double]$r.oosReturn*100,3))% pf=$([math]::Round([double]$r.oosProfitFactor,3)) trades=$($r.oosTrades) probNoEdge=$([math]::Round([double]$r.oosProbabilityOfNoEdge,3))"
    return $r
}

Write-Host "== a3 passive-fill WFA: ticker=$Ticker days=$Days folds=$Folds conf=$Conf live-risk =="
$taker = $null
if (-not $SkipBaseline) {
    $taker = Run-Config "taker" ""
}
$passive = Run-Config "passive" "&executionPassiveFill=true"

if ($taker) {
    Write-Host ("== сравнение: taker PF={0} trades={1} -> passive PF={2} trades={3} ==" -f `
        [math]::Round([double]$taker.oosProfitFactor, 3), $taker.oosTrades,
        [math]::Round([double]$passive.oosProfitFactor, 3), $passive.oosTrades)
}

$passPf = [double]$passive.oosProfitFactor
$passTrades = [int]$passive.oosTrades
$passP = [double]$passive.oosProbabilityOfNoEdge

if ($RunGate) {
    # Если раннер будет убит (нехватка ОЗУ на хосте) до записи JSON — результат гейта
    # восстанавливается из лога сервера (строки `Holdout ...`/`Backtest ... -> REJECT`/
    # `Robustness ... -> FRAGILE`) + БД `deployment_approval` (см.
    # artifacts/a3_passive_gate_730d_f8.recovered.txt — так и было сделано 2026-10-17).
    if ($passPf -ge 1.30 -and $passTrades -ge 1) {
        Write-Hb "GATE passive (pf=$([math]::Round($passPf,3)))"
        $gUrl = "$BaseUrl/api/v1/backtest/$Ticker/deployment-gate`?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf&executionPassiveFill=true&loadHistory=false"
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $g = Invoke-WithAuth $gUrl
            $sw.Stop()
            $gateFile = Join-Path $OutDir "a3_passive_gate_${Days}d_f${Folds}.json"
            $g | ConvertTo-Json -Depth 8 | Set-Content -Path $gateFile
            Write-Hb ("GATE DONE verdict={0} liveAllowed={1} ({2}s) -> {3}" -f $g.verdict, $g.liveAllowed, [math]::Round($sw.Elapsed.TotalSeconds, 0), $gateFile)
        } catch {
            $sw.Stop()
            Write-Hb ("GATE FAILED ({0}s): {1}" -f [math]::Round($sw.Elapsed.TotalSeconds, 0), $_.Exception.Message)
        }
    } else {
        Write-Hb ("GATE SKIPPED: passive PF {0} < 1.30" -f [math]::Round($passPf, 3))
    }
}

Write-Hb ("FINISHED csv=$csv")
Write-Output "RESULT_CSV=$csv"
