# Research: параллельная WFA-сетка комбо-фильтров на 730д (CNYRUBF, folds=8).
#
# Каждый конфиг = отдельный фоновый pwsh (логин + /validate), пишет свой файл результата;
# оркестратор держит не более $Parallel активных процессов. Сводка — в конец скрипта.
# Риск-профиль: riskPerTradePercent=30 & futuresMaxContractsPerPosition=100, conf=0.60.
#
# Запуск (фоново):
#   pwsh -NoProfile -File scripts\research_wfa_combo_730.ps1 > combo_730.log 2>&1
param(
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$Conf = 0.60,
    [string]$RiskPct = "30",
    [string]$MaxC = "100",
    [int]$Parallel = 4,
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
if (-not $OutDir) { $OutDir = Join-Path $env:TEMP "opencode\combo730" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$base = "http://localhost:8080/api/v1/backtest/$Ticker/validate?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf&riskPerTradePercent=$RiskPct&futuresMaxContractsPerPosition=$MaxC&loadHistory=false"

# Конфиги: name=params (доп. query-параметры; пусто = baseline)
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

# Один прогон: файл runner
$runner = Join-Path $OutDir "runner.ps1"
@"
param(`$name, `$url)
`$out = Join-Path `$env:TEMP "opencode\combo730\`$name.json"
`$login = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/auth/login" -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 15
`$h = @{ Authorization = "Bearer `$(`$login.accessToken)" }
`$sw = [Diagnostics.Stopwatch]::StartNew()
function Invoke-WithRetry {
    param([string]`$u, [hashtable]`$hh)
    for (`$i = 0; `$i -lt 3; `$i++) {
        try { return Invoke-RestMethod -Method Get -Uri `$u -Headers `$hh -TimeoutSec 10800 }
        catch {
            `$resp = `$_.Exception.Response
            ``if (`$resp -and [int]`$resp.StatusCode -eq 401) {
                `$login = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/auth/login" -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 15
                `$hh.Authorization = "Bearer `$(`$login.accessToken)"
                continue
            }
            ``throw
        }
    }
    ``throw "failed after 3 retries"
}
try { `$r = Invoke-WithRetry -u `$url -hh `$h; `$sw.Stop() }
catch { `$sw.Stop(); @{error = "`$(`$_.Exception.Message)"; secs = [math]::Round(`$sw.Elapsed.TotalSeconds,0) } | ConvertTo-Json | Set-Content `$out; exit 1 }
@{ name = `$name; consistency = `$r.consistency; oosReturn = [math]::Round([double]`$r.oosReturn*100,2); oosPF = [math]::Round([double]`$r.oosProfitFactor,2); oosTrades = `$r.oosTrades; probNoEdge = [math]::Round([double]`$r.oosProbabilityOfNoEdge,3); robust = `$r.robust; secs = [math]::Round(`$sw.Elapsed.TotalSeconds,0) } | ConvertTo-Json | Set-Content `$out
"@ | Set-Content -Path $runner -Encoding utf8

Write-Host ("START $(Get-Date -Format HH:mm:ss)  configs={0} parallel={1} days={2} folds={3}" -f $configs.Count, $Parallel, $Days, $Folds)

$queue = [System.Collections.Queue]::new()
foreach ($c in $configs) { $queue.Enqueue($c) }
$running = @{}

while ($queue.Count -gt 0 -or $running.Count -gt 0) {
    while ($running.Count -lt $Parallel -and $queue.Count -gt 0) {
        $c = $queue.Dequeue()
        $url = if ($c.params) { "$base&$($c.params)" } else { $base }
        $out = Join-Path $OutDir "$($c.name).json"
        Remove-Item $out -ErrorAction SilentlyContinue
        $p = Start-Process -FilePath "pwsh" -ArgumentList '-NoProfile','-File',$runner,'-name',$c.name,'-url',"`"$url`"" -PassThru -WindowStyle Hidden
        $running[$p.Id] = $c.name
        Write-Host ("  {0} started as pid {1} ({2})" -f $c.name, $p.Id, (Get-Date -Format HH:mm:ss))
    }
    Start-Sleep -Seconds 15
    foreach ($procId in @($running.Keys)) {
        if (-not (Get-Process -Id $procId -ErrorAction SilentlyContinue)) {
            Write-Host ("  {0} done ({1})" -f $running[$procId], (Get-Date -Format HH:mm:ss))
            $running.Remove($procId)
        }
    }
}

Write-Host ""
Write-Host "SUMMARY (days=$Days folds=$Folds conf=$Conf)"
$rows = foreach ($c in $configs) {
    $f = Join-Path $OutDir "$($c.name).json"
    if (Test-Path $f) {
        $r = Get-Content $f -Raw | ConvertFrom-Json
        [pscustomobject]@{ Config=$c.name; Consistency=$r.consistency; OOS_RetPct=$r.oosReturn; OOS_PF=$r.oosPF; OOS_Trades=$r.oosTrades; P_NoEdge=$r.probNoEdge; Robust=$r.robust; Secs=$r.secs }
    } else {
        [pscustomobject]@{ Config=$c.name; Consistency="-"; OOS_RetPct="-"; OOS_PF="-"; OOS_Trades="-"; P_NoEdge="-"; Robust="-"; Secs="-" }
    }
}
$rows | Sort-Object { if ($_.OOS_PF -eq "-") { -1 } else { -[double]$_.OOS_PF } } | Format-Table -AutoSize
Write-Host "ALL_DONE $(Get-Date -Format HH:mm:ss)"