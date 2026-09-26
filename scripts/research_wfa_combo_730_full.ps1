# Research: параллельная WFA-сетка комбо-фильтров на 730д (CNYRUBF, folds=8).
# Робастная версия: файл-лог, skip успешных (json без error), перезапуск упавших,
# пометка RUNNING в progress.txt, чтобы не дублировать активные прогоны.
#
# Запуск (фоново):
#   pwsh -NoProfile -File scripts\research_wfa_combo_730_full.ps1
param(
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$Conf = 0.60,
    [string]$RiskPct = "30",
    [string]$MaxC = "100",
    [int]$Parallel = 2,
    [string]$SkipNames = "" # через запятую, если прогон уже активен
)

$ErrorActionPreference = "Stop"
$OutDir = Join-Path $env:TEMP "opencode\combo730"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$log = Join-Path $OutDir "orch_log.txt"
$prog = Join-Path $OutDir "progress.txt"
function Log([string]$msg) { Add-Content -Path $log -Value "$(Get-Date -Format 'HH:mm:ss')  $msg" }

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

$runner = Join-Path $OutDir "runner.ps1"
@"
param(`$name, `$url)
`$out = Join-Path `$env:TEMP "opencode\combo730\`$name.json"
`$log = Join-Path `$env:TEMP "opencode\combo730\orch_log.txt"
`$prog = Join-Path `$env:TEMP "opencode\combo730\progress.txt"
function Log([string]`$msg) { Add-Content -Path `$log -Value "`$(Get-Date -Format 'HH:mm:ss')  `$msg" }
function Get-Tok {
    `$l = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/auth/login" -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 15
    `$script:tok = @{ Authorization = "Bearer `$(`$l.accessToken)" }
}
Get-Tok
`$sw = [Diagnostics.Stopwatch]::StartNew()
try {
    `$r = $null
    for (`$i = 0; `$i -lt 4; `$i++) {
        try {
            `$r = Invoke-RestMethod -Method Get -Uri `$url -Headers `$script:tok -TimeoutSec 10700
            break
} catch {
            `$resp = `$_.Exception.Response
            if (`$resp -and [int]`$resp.StatusCode -eq 401) { Get-Tok; continue }
            if (`$i -eq 3) { throw }
            Start-Sleep -Seconds 3
        }
    }
    `$sw.Stop()
    if (`$null -eq `$r) { throw "no result" }
    @{ name = `$name; consistency = `$r.consistency; oosReturn = [math]::Round([double]`$r.oosReturn*100,2); oosPF = [math]::Round([double]`$r.oosProfitFactor,2); oosTrades = `$r.oosTrades; probNoEdge = [math]::Round([double]`$r.oosProbabilityOfNoEdge,3); robust = `$r.robust; secs = [math]::Round(`$sw.Elapsed.TotalSeconds,0) } | ConvertTo-Json | Set-Content `$out
    Add-Content -Path `$prog -Value "`$name DONE OK `$(Get-Date -Format 'HH:mm:ss') secs=[math]::Round(`$sw.Elapsed.TotalSeconds,0)"
} catch {
    `$sw.Stop()
    @{ error = "`$(`$_.Exception.Message)"; secs = [math]::Round(`$sw.Elapsed.TotalSeconds,0) } | ConvertTo-Json | Set-Content `$out
    Add-Content -Path `$prog -Value "`$name DONE ERROR `$(Get-Date -Format 'HH:mm:ss')"
    exit 1
}
"@ | Set-Content -Path $runner -Encoding utf8

$skipSet = @{}
foreach ($s in $SkipNames.Split(",").Where({ $_ })) { $skipSet[$s] = $true }
$todo = [System.Collections.Queue]::new()
foreach ($c in $configs) {
    if ($skipSet[$c.name]) { Log "SKIP $($c.name) (активный прогон)" ; continue }
    $jf = Join-Path $OutDir "$($c.name).json"
    $done = $false
    if (Test-Path $jf) {
        $j = Get-Content $jf -Raw | ConvertFrom-Json
        if (-not $j.error) { $done = $true }
    }
    if ($done) { Log "SKIP $($c.name) (уже успешен)"; continue }
    $todo.Enqueue($c)
}

Log "START  configs_pending=$($todo.Count) parallel=$Parallel days=$Days folds=$Folds"

$running = @{}
while ($todo.Count -gt 0 -or $running.Count -gt 0) {
    while ($running.Count -lt $Parallel -and $todo.Count -gt 0) {
        $c = $todo.Dequeue()
        $url = if ($c.params) { "$base&$($c.params)" } else { $base }
        Remove-Item (Join-Path $OutDir "$($c.name).json") -ErrorAction SilentlyContinue
        $p = Start-Process -FilePath "pwsh" -ArgumentList '-NoProfile','-File',$runner,'-name',$c.name,'-url',"`"$url`"" -PassThru -WindowStyle Hidden
        $running[$p.Id] = $c.name
        Log "STARTED $($c.name) pid=$($p.Id)"
    }
    Start-Sleep -Seconds 30
    foreach ($procId in @($running.Keys)) {
        if (-not (Get-Process -Id $procId -ErrorAction SilentlyContinue)) {
            $nm = $running[$procId]
            $jf = Join-Path $OutDir "$nm.json"
            $j = if (Test-Path $jf) { Get-Content $jf -Raw | ConvertFrom-Json } else { $null }
            $verdict = if ($j -and -not $j.error) { "OK ret=$($j.oosReturn)% pf=$($j.oosPF)" } else { "ERROR" }
            Log "DONE $nm $verdict"
            $running.Remove($procId)
        }
    }
}

Log "ALL_DONE"
$lines = foreach ($c in $configs) {
    $jf = Join-Path $OutDir "$($c.name).json"
    if (Test-Path $jf) {
        $r = Get-Content $jf -Raw | ConvertFrom-Json
        if (-not $r.error) {
            [pscustomobject]@{ Config=$c.name; Consistency=$r.consistency; OOS_RetPct=$r.oosReturn; OOS_PF=$r.oosPF; OOS_Trades=$r.oosTrades; P_NoEdge=$r.probNoEdge; Robust=$r.robust; Secs=$r.secs }
        }
    }
}
if ($lines) {
    $tbl = $lines | Sort-Object { -[double]$_.OOS_PF } | Format-Table -AutoSize | Out-String
    Log $tbl
}