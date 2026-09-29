# Watchdog для тяжёлых research-прогонов: каждые 5 минут фиксирует, идёт ли работа
# или процесс завис. Отличить "долго считает" от "завис" можно только по признакам:
#   - процесс жив (не упал);
#   - HTTP отвечает (иначе WFA держит event loop и всё приложение недоступно);
#   - растёт число строк в CSV (прогресс), а не только время;
#   - CPU/RSS приложения меняются (иначе цикл ушёл в deadlock).
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$AppPid = 24228,
    [string]$Csv = "scripts\riskgrid_wfa_730d.csv",
    [int]$IntervalSec = 300,
    [int]$MaxTicks = 240
)

$ErrorActionPreference = "Continue"
[System.Threading.Thread]::CurrentThread.CurrentCulture = [System.Globalization.CultureInfo]::InvariantCulture

$root = Split-Path -Parent $PSScriptRoot
$heartbeat = "C:\Users\User\AppData\Local\Temp\opencode\watchdog.log"
$appLog = "C:\Users\User\AppData\Local\Temp\opencode\app-veto3.log"

function Probe-Api {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $null = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 20
        $sw.Stop()
        return "http_ok_$([math]::Round($sw.Elapsed.TotalMilliseconds, 0))ms"
    } catch {
        $sw.Stop()
        $code = $_.Exception.Response.StatusCode.value__
        if ($code) { return "http_$code_$([math]::Round($sw.Elapsed.TotalMilliseconds, 0))ms" }
        return "unreachable_$([math]::Round($sw.Elapsed.TotalMilliseconds, 0))ms"
    }
}

function Count-Lines($path) {
    if (-not (Test-Path $path)) { return -1 }
    return (Get-Content $path -Encoding UTF8 | Measure-Object -Line).Lines
}

"=== watchdog start $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') appPid=$AppPid csv=$Csv interval=${IntervalSec}s ===" |
    Add-Content -Path $heartbeat -Encoding UTF8

$lastRows = -2
$stale = 0
for ($tick = 1; $tick -le $MaxTicks; $tick++) {
    $proc = Get-Process -Id $AppPid -ErrorAction SilentlyContinue
    $api = Probe-Api

    if ($proc) {
        $cpu = [math]::Round($proc.CPU, 1)
        $rssMb = [math]::Round($proc.WorkingSet64 / 1MB, 0)
    } else { $cpu = "dead"; $rssMb = "dead" }

    $rows = Count-Lines (Join-Path $root $Csv)
    $appLogLines = Count-Lines $appLog
    $progress = if ($rows -gt $lastRows) { "PROGRESS" } else { "no-new-row" }
    if ($rows -eq $lastRows) { $stale++ } else { $stale = 0 }
    $lastRows = $rows

    $line = "{0} tick={1} app={2} cpu={3} rssMb={4} api={5} csvRows={6} {7} staleTicks={8} appLogLines={9}" -f `
        (Get-Date -Format "HH:mm:ss"), $tick, ($(if ($proc) { "alive" } else { "DEAD" })), $cpu, $rssMb,
        $api, $rows, $progress, $stale, $appLogLines
    $line | Add-Content -Path $heartbeat -Encoding UTF8
    Write-Host $line

    if (-not $proc) {
        "WATCHDOG: app process dead, stopping" | Add-Content -Path $heartbeat -Encoding UTF8
        break
    }
    if ($stale -ge 3) {
        "WATCHDOG: CSV без изменений 15 мин, но процессы живы - вероятно долгий прогон" | Add-Content -Path $heartbeat -Encoding UTF8
    }
    Start-Sleep -Seconds $IntervalSec
}
"WATCHDOG finished $(Get-Date -Format 'HH:mm:ss')" | Add-Content -Path $heartbeat -Encoding UTF8
