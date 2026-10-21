# Research P4 (docs/27-funding-window-capture.md): funding-window capture на CNYRUBF.
#
# Особенность: P4 реализован **config-only** (без query-оверрайдов), поэтому каждая точка
# сетки порогов/флагов требует перезапуска сервера с нужными --bt.* аргументами. Скрипт
# делает это сам: kill -> start -> wait-ready -> /validate -> запись строки. Сетка порогов
# и контроли (direction-aware off, случайная сторона) задаются -ConfigCsv.
#
# Формат -ConfigCsv: "имя;enabled;long;short;dirAware;randomSide"
#   имя        — метка строки отчёта
#   enabled    — true|false (funding-window-enabled)
#   long       — порог LONG в ₽ (funding-window-long-threshold-rub)
#   short      — порог SHORT в ₽ (funding-window-short-threshold-rub)
#   dirAware   — true|false (funding-direction-aware)
#   randomSide — true|false (funding-window-random-side; placebo §7.2), опционально
#
# Пример:
#   .\scripts\research_funding_window.ps1 -ConfigCsv @(
#       "thr2.0-dir-aware;true;2.0;2.0;true;false",
#       "thr2.0-legacy;true;2.0;2.0;false;false")
#
# Прогон: 730д, folds=8, live-риск (maxC=1, Kelly), SL/TP пиннятся wfaSlPoints=300/600
# (правило F1 §4 docs/27: стопы фиксированы, не подбираются).
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$Conf = 0.60,
    [int]$FuturesMaxC = 1,
    [int]$SlPoints = 300,
    [int]$TpPoints = 600,
    [int]$TimeoutSec = 7200,
    [switch]$RunGate,
    [double]$HoldoutFraction = 0.30,
    [string]$OutDir = "artifacts",
    [string]$OutCsv = "",
    [string]$OutJson = "",
    [string[]]$ConfigCsv = @(),
    # Инфраструктура запуска сервера
    [string]$JarPath = "build\libs\trading-bot-2.0.0.jar",
    [string]$TrustStore = "build\cbr-certs\cbr-truststore.jks",
    [string]$TrustStorePassword = "changeit",
    [int]$AsyncTimeoutMs = 10800000,
    [string]$LogDir = "build",
    [int]$ReadyTimeoutSec = 240
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $OutCsv) { $OutCsv = Join-Path $OutDir "funding_window_$stamp.csv" }
if (-not $OutJson) { $OutJson = Join-Path $OutDir "funding_window_$stamp.json" }
$statusFile = Join-Path $OutDir "funding_window_$stamp.status"

function Write-Status([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
    Write-Host $line
    Add-Content -LiteralPath $statusFile -Value $line -Encoding UTF8
}

function Resolve-EnvValue([string]$name, [string]$def = "") {
    if (-not (Test-Path $envPath)) { return $def }
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}

# ---- env для дочернего java (Spring читает DB_PASS/JWT_SECRET/AUTH_*/TRADING_MODE) ----
$env:DB_PASS = if ($env:DB_PASS) { $env:DB_PASS } else { "backtest123" }
$env:JWT_SECRET = if ($env:JWT_SECRET) { $env:JWT_SECRET } else { "research-funding-window-secret-0123456789" }
$env:TRADING_MODE = "SIMULATION"
$env:AUTH_USER = Resolve-EnvValue "AUTH_USER" "admin"
$env:AUTH_PASSWORD = Resolve-EnvValue "AUTH_PASSWORD" "admin123456"

function Stop-TradingServer {
    $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine -like "*trading-bot-2.0.0.jar*" }
    foreach ($p in $procs) {
        Write-Status ("stop: pid={0}" -f $p.ProcessId)
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if ($procs) { Start-Sleep -Seconds 5 }
}

function Start-TradingServer([string]$tag, [string[]]$extraArgs) {
    $log = Join-Path $LogDir "server_fw_$tag.log"
    $err = Join-Path $LogDir "server_fw_$tag.err.log"
    $trustAbs = (Resolve-Path $TrustStore).Path
    $args = @(
        "-Djavax.net.ssl.trustStore=$trustAbs",
        "-Djavax.net.ssl.trustStorePassword=$TrustStorePassword",
        "-jar", $JarPath,
        "--spring.mvc.async.request-timeout=$AsyncTimeoutMs"
    ) + $extraArgs
    Write-Status ("start pid-file log={0} args={1}" -f $log, ($extraArgs -join " "))
    $p = Start-Process -FilePath "java" -ArgumentList $args -PassThru `
        -RedirectStandardOutput $log -RedirectStandardError $err -WindowStyle Hidden
    Set-Content -Path (Join-Path $LogDir "server_fw.pid") -Value "pid=$($p.Id)"
    return $p.Id
}

function Wait-Ready {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $ReadyTimeoutSec) {
        Start-Sleep -Seconds 5
        try {
            $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" `
                -ContentType "application/json" `
                -Body (@{ username = $env:AUTH_USER; password = $env:AUTH_PASSWORD } | ConvertTo-Json) `
                -TimeoutSec 15
            if ($login.accessToken) {
                Write-Status ("ready in {0}s" -f [math]::Round($sw.Elapsed.TotalSeconds, 0))
                return $login.accessToken
            }
        } catch { }
    }
    throw "server not ready within ${ReadyTimeoutSec}s (see $LogDir\server_fw_*.log)"
}

$configs = @()
foreach ($c in $ConfigCsv) {
    if (-not $c) { continue }
    $p = $c.Split(";")
    if ($p.Count -lt 5) { throw "ConfigCsv element must be 'name;enabled;long;short;dirAware[;randomSide]': $c" }
    $configs += @{
        Name = $p[0]; Enabled = $p[1]; Long = $p[2]; Short = $p[3]
        DirAware = $p[4]
        RandomSide = if ($p.Count -ge 6 -and $p[5]) { $p[5] } else { "false" }
    }
}
if ($configs.Count -eq 0) { throw "no -ConfigCsv entries" }

Write-Status ("start: ticker={0} days={1} folds={2} conf={3} maxC={4} sl={5} tp={6} gate={7} configs={8}" -f `
        $Ticker, $Days, $Folds, $Conf, $FuturesMaxC, $SlPoints, $TpPoints, [bool]$RunGate, $configs.Count)

$rows = @()
$results = @()
foreach ($cfg in $configs) {
    $tag = ($cfg.Name -replace '[^A-Za-z0-9._-]', '_')
    Write-Status ("config: {0} (enabled={1} long={2} short={3} dirAware={4} randomSide={5})" -f `
            $cfg.Name, $cfg.Enabled, $cfg.Long, $cfg.Short, $cfg.DirAware, $cfg.RandomSide)

    Stop-TradingServer
    $extra = @(
        "--bt.funding-window-enabled=$($cfg.Enabled)",
        "--bt.funding-window-long-threshold-rub=$($cfg.Long)",
        "--bt.funding-window-short-threshold-rub=$($cfg.Short)",
        "--bt.funding-direction-aware=$($cfg.DirAware)",
        "--bt.funding-window-random-side=$($cfg.RandomSide)"
    )
    Start-TradingServer -tag $tag -extraArgs $extra | Out-Null
    $token = Wait-Ready
    $headers = @{ Authorization = "Bearer $token" }

    $base = "$BaseUrl/api/v1/backtest/$Ticker/validate?days=$Days&folds=$Folds" +
        "&adaptiveConfidenceThreshold=$Conf&futuresMaxContractsPerPosition=$FuturesMaxC" +
        "&wfaSlPoints=$SlPoints&wfaTpPoints=$TpPoints&loadHistory=false"

    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-RestMethod -Method Get -Uri $base -Headers $headers -TimeoutSec $TimeoutSec
    } catch {
        Write-Status ("validate retry after re-auth: {0}" -f $_.Exception.Message)
        Start-Sleep -Seconds 5
        $token = Wait-Ready
        $headers = @{ Authorization = "Bearer $token" }
        $r = Invoke-RestMethod -Method Get -Uri $base -Headers $headers -TimeoutSec $TimeoutSec
    }
    $sw.Stop()

    $gateStatus = ""
    $liveAllowed = $null
    if ($RunGate) {
        $gBase = "$BaseUrl/api/v1/backtest/$Ticker/deployment-gate?days=$Days&folds=$Folds" +
            "&holdoutFraction=$HoldoutFraction&adaptiveConfidenceThreshold=$Conf" +
            "&futuresMaxContractsPerPosition=$FuturesMaxC&loadHistory=false"
        Write-Status ("gate: {0} (MC + stress, долго)" -f $cfg.Name)
        $g = Invoke-RestMethod -Method Get -Uri $gBase -Headers $headers -TimeoutSec $TimeoutSec
        $gateStatus = [string]$g.status
        $liveAllowed = [bool]$g.liveAllowed
    }

    $trades = [int]$r.oosTrades
    $pf = [double]$r.oosProfitFactor
    $pNoEdge = [double]$r.oosProbabilityOfNoEdge
    $ci0 = [double]$r.oosMeanTradeCI95Low
    $ci1 = [double]$r.oosMeanTradeCI95High
    $verdict = if ($trades -lt 30) { "INCONCLUSIVE (OOS trades $trades < 30)" }
    elseif ($trades -lt 100) { "INCONCLUSIVE (OOS trades $trades < 100, §4)" }
    elseif ($pf -lt 1.30) { "REJECTED (OOS PF $([math]::Round($pf,3)) < 1.30)" }
    elseif ($pNoEdge -ge 0.05 -or ($ci0 -le 0 -and $ci1 -ge 0)) { "INCONCLUSIVE (edge не значим)" }
    else { "PASS (research-кандидат)" }

    $rows += [pscustomobject]@{
        Config = $cfg.Name
        Enabled = $cfg.Enabled
        Long = $cfg.Long
        Short = $cfg.Short
        DirAware = $cfg.DirAware
        RandomSide = $cfg.RandomSide
        OOS_Trades = $trades
        OOS_PF = [math]::Round($pf, 3)
        OOS_RetPct = [math]::Round([double]$r.oosReturn * 100, 2)
        OOS_Sharpe = [math]::Round([double]$r.oosSharpe, 2)
        Consistency = $r.consistency
        Robust = $r.robust
        P_NoEdge = [math]::Round($pNoEdge, 4)
        CI95 = "$([math]::Round($ci0,1))..$([math]::Round($ci1,1))"
        SL_TP = "$($r.paramsUsed.slPoints)/$($r.paramsUsed.tpPoints)"
        GateStatus = $gateStatus
        LiveAllowed = $liveAllowed
        Verdict = $verdict
        Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    }
    $results += [pscustomobject]@{
        config = $cfg.Name; enabled = $cfg.Enabled; long = $cfg.Long; short = $cfg.Short
        directionAware = $cfg.DirAware; randomSide = $cfg.RandomSide
        oosTrades = $trades; oosProfitFactor = $pf
        oosReturnPct = [math]::Round([double]$r.oosReturn * 100, 2)
        oosSharpe = [math]::Round([double]$r.oosSharpe, 2)
        consistency = $r.consistency; robust = $r.robust
        probabilityOfNoEdge = $pNoEdge; ci95Low = $ci0; ci95High = $ci1
        slPoints = $r.paramsUsed.slPoints; tpPoints = $r.paramsUsed.tpPoints
        verdict = $verdict; gateStatus = $gateStatus; liveAllowed = $liveAllowed
        url = $base; elapsedSecs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    }

    $rows | Format-Table -AutoSize
    Write-Status ("done: {0} -> {1} PF={2} trades={3} {4}s" -f `
            $cfg.Name, $verdict, [math]::Round($pf, 3), $trades, [math]::Round($sw.Elapsed.TotalSeconds, 0))
}

$rows | Export-Csv -Path $OutCsv -NoTypeInformation -Encoding UTF8
$results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutJson -Encoding UTF8
Write-Status ("csv: {0}" -f $OutCsv)
Write-Status ("json: {0}" -f $OutJson)
Write-Host ""
$rows | Format-Table -AutoSize
Write-Host "Сервер остался запущен с ПОСЛЕДНИМ конфигом. Вердикт — по §4 docs/27 (OOS, live-риск)."
