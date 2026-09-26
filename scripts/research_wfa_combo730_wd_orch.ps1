# Watchdog-оркестратор параллельной WFA-сетки комбо-фильтров на 730д (CNYRUBF, folds=8, conf=0.6).
# - Параллельно не более $Parallel прогонов.
# - Каждые ~60с: проверка живых раннеров. Упавший без результата -> перезапуск.
# - "Зависший" раннер (попытка дольше MaxAttemptSec + 300) -> kill + перезапуск.
# - До $MaxAttempts попыток на конфиг, потом конфиг в failed.
# - Если сервер недоступен (login не работает $ServerDownThreshold циклов) -> перезапуск java (pid из ServerPidFile).
# - Итог: сводка правится в файл $OutDir/summary.txt + лог orch_wd.log.
param(
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$Conf = 0.60,
    [string]$RiskPct = "30",
    [string]$MaxC = "100",
    [int]$Parallel = 2,
    [int]$MaxAttempts = 4,
    [int]$ServerDownThreshold = 6,
    [string]$OutDir = "",
    [string]$ServerPidFile = "",
    [string]$JarPath = "",
    [string]$ServerWorkDir = "",
    [string]$ConfigFile = ""
)

$ErrorActionPreference = "Stop"
if (-not $OutDir) { $OutDir = Join-Path $env:TEMP "opencode\combo730" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$log = Join-Path $OutDir "orch_wd.log"
$sum = Join-Path $OutDir "summary.txt"
function Log([string]$msg) { Add-Content -Path $log -Value "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $msg" }

$base = "http://localhost:8080/api/v1/backtest/$Ticker/validate?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf&riskPerTradePercent=$RiskPct&futuresMaxContractsPerPosition=$MaxC&loadHistory=false"

$configs = @(
    @{ name = "baseline"; params = "" },
    @{ name = "fv9-2"; params = "fundingVetoEnabled=true&fundingVetoLongThresholdRub=9&fundingVetoShortThresholdRub=2&fundingVetoBlockOnUnknown=true" },
    @{ name = "mh368"; params = "maxHoldBars=368" },
    @{ name = "tdL9"; params = "timeDirectionEnabled=true&timeDirectionLongBlockUntilHour=9" },
    @{ name = "tdL9s13-16"; params = "timeDirectionEnabled=true&timeDirectionLongBlockUntilHour=9&timeDirectionShortBlockStartHour=13&timeDirectionShortBlockEndHour=16" },
    @{ name = "orb12l"; params = "orbEnabled=true&orbWindowBars=12&orbStrictBreakout=false&orbBlockOnUnknown=true" },
    @{ name = "tdL9-fv9-2"; params = "timeDirectionEnabled=true&timeDirectionLongBlockUntilHour=9&fundingVetoEnabled=true&fundingVetoLongThresholdRub=9&fundingVetoShortThresholdRub=2&fundingVetoBlockOnUnknown=true" },
    @{ name = "tdL9-mh368"; params = "timeDirectionEnabled=true&timeDirectionLongBlockUntilHour=9&maxHoldBars=368" },
    @{ name = "fv9-2-mh368"; params = "fundingVetoEnabled=true&fundingVetoLongThresholdRub=9&fundingVetoShortThresholdRub=2&fundingVetoBlockOnUnknown=true&maxHoldBars=368" },
    @{ name = "orb12l-fv9-2"; params = "orbEnabled=true&orbWindowBars=12&orbStrictBreakout=false&orbBlockOnUnknown=true&fundingVetoEnabled=true&fundingVetoLongThresholdRub=9&fundingVetoShortThresholdRub=2&fundingVetoBlockOnUnknown=true" },
    @{ name = "orb12l-mh368"; params = "orbEnabled=true&orbWindowBars=12&orbStrictBreakout=false&orbBlockOnUnknown=true&maxHoldBars=368" },
    @{ name = "orb12l-tdL9"; params = "orbEnabled=true&orbWindowBars=12&orbStrictBreakout=false&orbBlockOnUnknown=true&timeDirectionEnabled=true&timeDirectionLongBlockUntilHour=9" },
    @{ name = "tdL9-fv9-2-mh368"; params = "timeDirectionEnabled=true&timeDirectionLongBlockUntilHour=9&fundingVetoEnabled=true&fundingVetoLongThresholdRub=9&fundingVetoShortThresholdRub=2&fundingVetoBlockOnUnknown=true&maxHoldBars=368" },
    @{ name = "orb12l-fv9-2-mh368"; params = "orbEnabled=true&orbWindowBars=12&orbStrictBreakout=false&orbBlockOnUnknown=true&fundingVetoEnabled=true&fundingVetoLongThresholdRub=9&fundingVetoShortThresholdRub=2&fundingVetoBlockOnUnknown=true&maxHoldBars=368" },
    @{ name = "orb12l-tdL9-fv9-2-mh368"; params = "orbEnabled=true&orbWindowBars=12&orbStrictBreakout=false&orbBlockOnUnknown=true&timeDirectionEnabled=true&timeDirectionLongBlockUntilHour=9&fundingVetoEnabled=true&fundingVetoLongThresholdRub=9&fundingVetoShortThresholdRub=2&fundingVetoBlockOnUnknown=true&maxHoldBars=368" },
    @{ name = "pull0_3"; params = "pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=0.3" }
)

if ($ConfigFile) {
    $configs = @()
    foreach ($line in (Get-Content $ConfigFile -Encoding UTF8)) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
        $parts = $trimmed -split "\|", 2
        $name = $parts[0].Trim()
        $params = if ($parts.Count -gt 1) { $parts[1].Trim() } else { "" }
        $configs += @{ name = $name; params = $params }
    }
}

$runner = Join-Path $PSScriptRoot "research_wfa_combo730_wd_runner.ps1"
$attempts = @{}
$failed = @{}

function Test-ServerUp {
    try {
        $null = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/auth/login" -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 30
        return $true
    } catch { return $false }
}

function Restart-JavaServer {
    if (-not $ServerPidFile -or -not $JarPath -or -not $ServerWorkDir) { return $false }
    try {
        if (Test-Path $ServerPidFile) {
            $oldPid = [int](Get-Content $ServerPidFile -Raw).Trim()
            Get-Process -Id $oldPid -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        }
        Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object { $_.Path -match 'java' } | Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 5
        $logdir = Join-Path $env:TEMP "opencode\server"
        $out = Join-Path $logdir "server.out.log"
        $err = Join-Path $logdir "server.err.log"
        $env:DB_PASS = 'backtest123'; $env:DB_USER = 'trader'; $env:DB_NAME = 'trading_bot'
        $env:AUTH_USER = 'admin'; $env:AUTH_PASSWORD = 'admin123'
        $env:JWT_SECRET = 'supersecretkey minlength32 bytes!!'; $env:TRADING_MODE = 'SIMULATION'
        $p = Start-Process -FilePath "java" -ArgumentList '-Xmx4g', '-jar', "`"$JarPath`"", '--spring.mvc.async.request-timeout=10800000' -WorkingDirectory $ServerWorkDir -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Hidden
        $p.Id | Set-Content $ServerPidFile
        Log "SERVER RESTARTED pid=$($p.Id)"
        Start-Sleep -Seconds 60
        return $true
    } catch {
        Log "SERVER RESTART FAILED: $($_.Exception.Message)"
        return $false
    }
}

function Get-RunnerResult($name) {
    $jf = Join-Path $OutDir "$name.json"
    if (Test-Path $jf) {
        $j = Get-Content $jf -Raw | ConvertFrom-Json
        if (-not $j.error) { return @{ ok = $true; data = $j } }
        return @{ ok = $false; data = $j }
    }
    return $null
}

Log "START  configs=$($configs.Count) parallel=$Parallel days=$Days folds=$Folds attempts=$MaxAttempts"

$queue = [System.Collections.Queue]::new()
foreach ($c in $configs) { $queue.Enqueue($c) }
$running = @{}
$serverDown = 0
$finishedOk = @{}
$lastSummary = ""

while ($queue.Count -gt 0 -or $running.Count -gt 0) {
    # --- server up? ---
    if (-not (Test-ServerUp)) {
        $serverDown++
        Log "SERVER DOWN ($serverDown)"
        if ($serverDown -ge $ServerDownThreshold) {
            if (Restart-JavaServer) { $serverDown = 0 } else { $serverDown = 0 }
        }
    } else {
        $serverDown = 0
    }

    # --- дозапуск свободных слотов ---
    while ($running.Count -lt $Parallel -and $queue.Count -gt 0) {
        $c = $queue.Dequeue()
        $url = if ($c.params) { "$base&$($c.params)" } else { $base }
        Remove-Item (Join-Path $OutDir "$($c.name).json") -ErrorAction SilentlyContinue
        Remove-Item (Join-Path $OutDir "$($c.name).heartbeat") -ErrorAction SilentlyContinue
        $attempts[$c.name] = [int]$attempts[$c.name] + 1
        $p = Start-Process -FilePath "pwsh" -ArgumentList '-NoProfile','-File',$runner,'-Name',$c.name,'-Url',"`"$url`"",'-OutDir',"`"$OutDir`"" -PassThru -WindowStyle Hidden
        $running[$p.Id] = @{ name = $c.name; started = (Get-Date); params = $c.params }
        Log "STARTED $($c.name) pid=$($p.Id) attempt=$($attempts[$c.name])"
    }

    # --- проверка активных ---
    foreach ($procId in @($running.Keys)) {
        $info = $running[$procId]
        $name = $info.name
        $alive = Get-Process -Id $procId -ErrorAction SilentlyContinue
        $res = Get-RunnerResult $name
        $elapsedMin = [math]::Round(((Get-Date) - $info.started).TotalMinutes, 0)

        if (-not $res -and $alive) {
            # жив, результата нет. Проверить "завис" по heartbeat (не обновляется > 20 мин) ИЛИ бюджет попытки превышен
            $hb = Join-Path $OutDir "$name.heartbeat"
            $hbOld = $false
            if (Test-Path $hb) {
                try {
                    $ts = [long]((Get-Content $hb -Raw) -replace 't=(\d+)', '${1}')
                    $age = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - $ts
                    $hbOld = $age -gt 1200
                } catch { $hbOld = $true }
            }
            $attemptBudgetExceeded = $elapsedMin -gt 178
            $graceOver = $elapsedMin -gt 5
            if (($hbOld -and $graceOver) -or $attemptBudgetExceeded) {
                Log "STUCK $name pid=$procId alive hbOld=$hbOld minutes=$elapsedMin -> kill+restart"
                Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
                Start-Sleep -Seconds 3
                $running.Remove($procId)
                $queue.Enqueue(@{ name = $name; params = $info.params })
            }
            continue
        }

        if (-not $alive) {
            # процесс завершился
            $running.Remove($procId)
            if ($res -and $res.ok) {
                $finishedOk[$name] = $res.data
                Log "DONE  $name attempt=$($attempts[$name]) ret=$($res.data.oosReturn)% pf=$($res.data.oosPF) trades=$($res.data.oosTrades) consistency=$($res.data.consistency) secs=$($res.data.secs)"
            } else {
                $errMsg = if ($res) { $res.data.error } else { "no result file" }
                Log "FAIL  $name attempt=$($attempts[$name]) err=$errMsg minutes=$elapsedMin -> requeue"
                if ($attempts[$name] -lt $MaxAttempts) {
                    $queue.Enqueue(@{ name = $name; params = $info.params })
                } else {
                    $failed[$name] = $true
                    Log "GIVEUP $name after $MaxAttempts attempts"
                }
            }
        }
    }

    $doneCount = $finishedOk.Count
    $failCount = $failed.Count
    $summaryLine = "STATUS $(Get-Date -Format 'HH:mm:ss') done=$doneCount failed=$failCount running=$($running.Count) queue=$($queue.Count)"
    if ($summaryLine -ne $lastSummary) {
        Log $summaryLine
        $lastSummary = $summaryLine
    }

    Start-Sleep -Seconds 60
}

Log "ALL_CYCLES_FINISHED"
$lines = foreach ($c in $configs) {
    if ($finishedOk[$c.name]) {
        $r = $finishedOk[$c.name]
        $annualized = if ($Days -gt 0) { [math]::Round(([math]::Pow(1 + [double]$r.oosReturn / 100, 365 / $Days) - 1) * 100, 1) } else { 0 }
        [pscustomobject]@{ Config = $c.name; Consistency = $r.consistency; OOS_RetPct = $r.oosReturn; AnnualPct = $annualized; OOS_PF = $r.oosPF; OOS_Trades = $r.oosTrades; P_NoEdge = $r.probNoEdge; Robust = $r.robust; Secs = $r.secs }
    } elseif ($failed[$c.name]) {
        [pscustomobject]@{ Config = $c.name; Consistency = "FAIL"; AnnualPct = $null; OOS_PF = $null }
    }
}
$tbl = $lines | Sort-Object { if ($_.OOS_PF -eq $null) { -1 } else { -[double]$_.OOS_PF } } | Format-Table -AutoSize | Out-String
Log $tbl
Set-Content -Path $sum -Value $tbl
Log "ALL_DONE $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"